package com.agentflow.knowledge.chunk;

import com.agentflow.knowledge.parser.ParsedDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 中文：基于既有 estimated-token 配置的确定性分块器。它尽量在段落边界结束一个接近满载的块；
 * 超长或无空格文本才按 token 边界硬切。相邻块精确重叠 {@code chunkOverlap} 个估算 token。
 *
 * <p>English: Deterministic chunker using the existing estimated-token settings. It
 * prefers paragraph boundaries for a near-full chunk and hard-splits only long or
 * whitespace-free text at token boundaries. Adjacent chunks overlap by exactly
 * {@code chunkOverlap} estimated tokens.
 */
@Component
public class DocumentChunker {
    private static final double PREFERRED_BOUNDARY_FILL_RATIO = 0.60d;
    /**
     * Tokenization currently uses one small TokenSpan object per estimated token. Keep
     * the synchronous V4 process below a predictable heap budget before creating that
     * list; larger source files remain durably uploaded as V3 PENDING/FAILED records
     * and can be handled by a later streaming or asynchronous slice.
     */
    static final int MAX_SOURCE_CODE_POINTS = 500_000;
    /**
     * A synchronous V4 request keeps all drafts in memory before its one persistence
     * transaction. This cap prevents a valid but pathological small-step overlap (for
     * example, chunkSize=80 / overlap=79) from expanding one upload into millions of
     * rows and exhausting the process.
     */
    static final int MAX_CHUNKS_PER_DOCUMENT = 10_000;

    private final TokenEstimator tokenEstimator;

    public DocumentChunker(TokenEstimator tokenEstimator) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator must not be null");
    }

    public List<ChunkDraft> chunk(ParsedDocument document, int chunkSize, int chunkOverlap) {
        Objects.requireNonNull(document, "document must not be null");
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be at least 0 and smaller than chunkSize");
        }

        String text = document.text();
        if (text.isBlank()) {
            throw new DocumentChunkingException("Document contains no processable text after normalization");
        }
        int sourceCodePointCount = text.codePointCount(0, text.length());
        if (sourceCodePointCount > MAX_SOURCE_CODE_POINTS) {
            throw new DocumentChunkingException(
                    "Document exceeds the V4 synchronous text limit; split the source before processing"
            );
        }
        if (wouldExceedSafeChunkLimit(sourceCodePointCount, chunkSize, chunkOverlap)) {
            throw new DocumentChunkingException(
                    "Document exceeds the V4 safe chunk limit; choose a larger chunk step or split the source"
            );
        }
        List<TokenSpan> tokens = tokenEstimator.tokenize(text);
        if (tokens.isEmpty()) {
            throw new DocumentChunkingException("Document contains no processable text after normalization");
        }

        List<ChunkDraft> chunks = new ArrayList<>();
        int startTokenIndex = 0;
        while (startTokenIndex < tokens.size()) {
            int hardEndTokenIndex = Math.min(startTokenIndex + chunkSize, tokens.size());
            int endTokenIndex = chooseEndTokenIndex(
                    text,
                    tokens,
                    startTokenIndex,
                    hardEndTokenIndex,
                    chunkSize,
                    chunkOverlap
            );
            TokenSpan firstToken = tokens.get(startTokenIndex);
            TokenSpan lastToken = tokens.get(endTokenIndex - 1);
            String content = text.substring(firstToken.startOffset(), lastToken.endOffset());
            int tokenCount = endTokenIndex - startTokenIndex;
            if (chunks.size() >= MAX_CHUNKS_PER_DOCUMENT) {
                throw new DocumentChunkingException(
                        "Document exceeds the V4 safe chunk limit; choose a larger chunk step or split the source"
                );
            }
            chunks.add(new ChunkDraft(
                    chunks.size(),
                    content,
                    document.titlePathAt(firstToken.startOffset()),
                    content.codePointCount(0, content.length()),
                    tokenCount
            ));

            if (endTokenIndex == tokens.size()) {
                break;
            }
            int nextStartTokenIndex = endTokenIndex - chunkOverlap;
            if (nextStartTokenIndex <= startTokenIndex) {
                throw new IllegalStateException("Chunking made no progress");
            }
            startTokenIndex = nextStartTokenIndex;
        }
        return List.copyOf(chunks);
    }

    /**
     * Uses code-point count as a conservative upper bound: one code point can become
     * at most one lightweight token. Paragraph preference may end at 60% capacity, so
     * the calculation uses the true minimum guaranteed forward progress rather than
     * simply {@code chunkSize - chunkOverlap}. Perform it before allocating token spans
     * or drafts, so adversarial CJK/punctuation-heavy input remains a normal FAILED
     * document rather than an out-of-memory process failure.
     */
    private static boolean wouldExceedSafeChunkLimit(
            int sourceCodePointCount,
            int chunkSize,
            int chunkOverlap
    ) {
        long minimumChunkTokenCount = Math.max(
                (long) Math.ceil(chunkSize * PREFERRED_BOUNDARY_FILL_RATIO),
                (long) chunkOverlap + 1
        );
        long minimumForwardProgress = minimumChunkTokenCount - chunkOverlap;
        long conservativeChunkCount = (sourceCodePointCount + minimumForwardProgress - 1)
                / minimumForwardProgress;
        return conservativeChunkCount > MAX_CHUNKS_PER_DOCUMENT;
    }

    private static int chooseEndTokenIndex(
            String text,
            List<TokenSpan> tokens,
            int startTokenIndex,
            int hardEndTokenIndex,
            int chunkSize,
            int chunkOverlap
    ) {
        if (hardEndTokenIndex == tokens.size()) {
            return hardEndTokenIndex;
        }

        int preferredMinimum = startTokenIndex + (int) Math.ceil(
                chunkSize * PREFERRED_BOUNDARY_FILL_RATIO
        );
        int safeProgressMinimum = startTokenIndex + chunkOverlap + 1;
        int minimumEndTokenIndex = Math.max(preferredMinimum, safeProgressMinimum);
        if (minimumEndTokenIndex >= hardEndTokenIndex) {
            return hardEndTokenIndex;
        }

        for (int candidateEndTokenIndex = hardEndTokenIndex;
                candidateEndTokenIndex >= minimumEndTokenIndex;
                candidateEndTokenIndex--) {
            TokenSpan left = tokens.get(candidateEndTokenIndex - 1);
            TokenSpan right = tokens.get(candidateEndTokenIndex);
            if (hasParagraphBoundary(text, left.endOffset(), right.startOffset())) {
                return candidateEndTokenIndex;
            }
        }
        return hardEndTokenIndex;
    }

    private static boolean hasParagraphBoundary(String text, int leftOffset, int rightOffset) {
        int newlineCount = 0;
        for (int offset = leftOffset; offset < rightOffset; offset++) {
            if (text.charAt(offset) == '\n' && ++newlineCount >= 2) {
                return true;
            }
        }
        return false;
    }
}
