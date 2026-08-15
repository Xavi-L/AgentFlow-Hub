package com.agentflow.knowledge.vector;

import com.agentflow.knowledge.model.KnowledgeChunk;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * 中文：用 chunk 的精确 UTF-8 正文计算 SHA-256，并把 owner/KB/document/position 与 hash
 * 一起派生为稳定 UUID。只用正文 hash 会让不同文档中的相同文本争用同一个 Qdrant point，
 * 所以范围与位置也是 ID 契约的一部分。
 *
 * <p>English: Computes SHA-256 from exact UTF-8 content, then derives a stable UUID
 * from owner/KB/document/position plus that hash. A content hash alone would make equal
 * text in different documents overwrite one Qdrant point, so scope and position are
 * part of the identity contract too.
 */
public final class ChunkVectorIdentityFactory {
    private static final String VECTOR_ID_NAMESPACE = "agentflow-knowledge-vector-v1";
    private static final HexFormat HEX = HexFormat.of();

    private ChunkVectorIdentityFactory() {
    }

    public static ChunkVectorIdentity create(KnowledgeChunk chunk) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        Long userId = requirePositive(chunk.getUserId(), "userId");
        Long knowledgeBaseId = requirePositive(chunk.getKnowledgeBaseId(), "knowledgeBaseId");
        Long documentId = requirePositive(chunk.getDocumentId(), "documentId");
        Integer chunkIndex = Objects.requireNonNull(chunk.getChunkIndex(), "chunkIndex must not be null");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative");
        }

        String contentHash = contentHash(chunk.getContent());
        String material = String.join(
                "\n",
                VECTOR_ID_NAMESPACE,
                userId.toString(),
                knowledgeBaseId.toString(),
                documentId.toString(),
                chunkIndex.toString(),
                contentHash
        );
        byte[] digest = sha256(material);
        ByteBuffer byteBuffer = ByteBuffer.wrap(digest);
        long mostSignificantBits = byteBuffer.getLong();
        long leastSignificantBits = byteBuffer.getLong();

        // RFC 9562 UUID version 8 reserves this form for application-defined bytes.
        // Qdrant accepts UUID point IDs; the version bits make this deterministic custom
        // derivation explicit rather than pretending it is a random UUID.
        mostSignificantBits = (mostSignificantBits & 0xffffffffffff0fffL) | 0x0000000000008000L;
        leastSignificantBits = (leastSignificantBits & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new ChunkVectorIdentity(contentHash, new UUID(mostSignificantBits, leastSignificantBits).toString());
    }

    public static String contentHash(String content) {
        return HEX.formatHex(sha256(Objects.requireNonNull(content, "content must not be null")));
    }

    private static Long requirePositive(Long value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 must be available in the Java runtime", unavailable);
        }
    }
}
