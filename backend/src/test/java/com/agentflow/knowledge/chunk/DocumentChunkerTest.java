package com.agentflow.knowledge.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.knowledge.parser.ParsedDocument;
import com.agentflow.knowledge.parser.ParsedSection;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests make V4's estimated-token windows and overlap directly observable. */
class DocumentChunkerTest {

    private final LightweightTokenEstimator tokenEstimator = new LightweightTokenEstimator();
    private final DocumentChunker chunker = new DocumentChunker(tokenEstimator);

    @Test
    void shouldCreateStableOrderedWindowsWithExactTokenOverlap() {
        ParsedDocument document = new ParsedDocument(
                "one two three four five six seven eight nine ten",
                List.of()
        );

        List<ChunkDraft> chunks = chunker.chunk(document, 4, 2);

        assertThat(chunks).extracting(ChunkDraft::chunkIndex).containsExactly(0, 1, 2, 3);
        assertThat(chunks).extracting(ChunkDraft::content).containsExactly(
                "one two three four",
                "three four five six",
                "five six seven eight",
                "seven eight nine ten"
        );
        assertThat(chunks).extracting(ChunkDraft::tokenCount).containsOnly(4);
    }

    @Test
    void shouldPreferAParagraphBoundaryWhenItKeepsTheChunkMeaningfullyFull() {
        ParsedDocument document = new ParsedDocument(
                "one two three\n\nfour five six seven eight",
                List.of()
        );

        List<ChunkDraft> chunks = chunker.chunk(document, 5, 1);

        assertThat(chunks).extracting(ChunkDraft::content).containsExactly(
                "one two three",
                "three\n\nfour five six seven",
                "seven eight"
        );
        assertThat(chunks).extracting(ChunkDraft::tokenCount).containsExactly(3, 5, 2);
    }

    @Test
    void shouldUseUnicodeCodePointsForCharacterCountAndKeepHeadingContext() {
        ParsedDocument document = new ParsedDocument(
                "中🙂 hello",
                List.of(new ParsedSection(0, "支付 / 退款"))
        );

        ChunkDraft chunk = chunker.chunk(document, 10, 0).getFirst();

        assertThat(chunk.content()).isEqualTo("中🙂 hello");
        assertThat(chunk.charCount()).isEqualTo(8);
        assertThat(chunk.tokenCount()).isEqualTo(3);
        assertThat(chunk.titlePath()).isEqualTo("支付 / 退款");
    }

    @Test
    void shouldHardSplitAnOverlongUnbrokenWordRatherThanPersistOneGiantChunk() {
        List<ChunkDraft> chunks = chunker.chunk(new ParsedDocument("a".repeat(65), List.of()), 8, 0);

        assertThat(chunks).extracting(ChunkDraft::content)
                .containsExactly("a".repeat(64), "a");
        assertThat(chunks).extracting(ChunkDraft::tokenCount).containsExactly(8, 1);
    }

    @Test
    void shouldTreatJapaneseAndHangulCharactersAsIndividuallyBudgetedTokens() {
        assertThat(tokenEstimator.estimate("かな한글")).isEqualTo(4);
    }

    @Test
    void shouldTruncateAnOversizedTitlePathToTheDatabaseContract() {
        String oversizedTitlePath = "题".repeat(ChunkDraft.MAX_TITLE_PATH_CODE_POINTS + 1);
        ParsedDocument document = new ParsedDocument(
                "body",
                List.of(new ParsedSection(0, oversizedTitlePath))
        );

        ChunkDraft chunk = chunker.chunk(document, 8, 0).getFirst();

        assertThat(chunk.titlePath().codePointCount(0, chunk.titlePath().length()))
                .isEqualTo(ChunkDraft.MAX_TITLE_PATH_CODE_POINTS);
        assertThat(chunk.titlePath()).endsWith("…");
    }

    @Test
    void shouldFailForWhitespaceOnlyContentRatherThanPersistAnEmptyChunk() {
        assertThatThrownBy(() -> chunker.chunk(new ParsedDocument(" \n\t ", List.of()), 4, 0))
                .isInstanceOf(DocumentChunkingException.class)
                .hasMessage("Document contains no processable text after normalization");
    }

    @Test
    void shouldRejectAPathologicalOverlapBeforeMaterializingMillionsOfDrafts() {
        String worstCaseCjkText = "中".repeat(DocumentChunker.MAX_CHUNKS_PER_DOCUMENT + 4);

        assertThatThrownBy(() -> chunker.chunk(
                new ParsedDocument(worstCaseCjkText, List.of()),
                4,
                3
        )).isInstanceOf(DocumentChunkingException.class)
                .hasMessageContaining("V4 safe chunk limit");
    }

    @Test
    void shouldRejectAnOversizedSourceBeforeAllocatingTokenSpans() {
        String oversizedText = "中".repeat(DocumentChunker.MAX_SOURCE_CODE_POINTS + 1);

        assertThatThrownBy(() -> chunker.chunk(new ParsedDocument(oversizedText, List.of()), 800, 120))
                .isInstanceOf(DocumentChunkingException.class)
                .hasMessageContaining("V4 synchronous text limit");
    }
}
