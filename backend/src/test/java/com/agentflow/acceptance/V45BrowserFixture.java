package com.agentflow.acceptance;

import com.agentflow.AgentFlowApplication;
import com.agentflow.knowledge.vector.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real upload/storage/parser/claims/JWT/PostgreSQL; only provider boundaries are controlled.
 * Inherits the V43 fixture so its browser recovery regression runs in the same acceptance. */
public final class V45BrowserFixture {
    private static final long KB = 450000000000000001L;
    private static final long OWNER = V43BrowserFixture.OWNER;
    private V45BrowserFixture() { }

    public static void main(String[] args) {
        if (!System.getProperty("spring.datasource.url", "").matches(
                "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/agentflow_v43_browser")) {
            throw new IllegalArgumentException("V45 fixture requires the disposable loopback browser database");
        }
        SpringApplication.run(new Class<?>[] {AgentFlowApplication.class, ControlledProviders.class}, args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ControlledProviders extends V43BrowserFixture.ControlledProviders {
        @Bean @Primary @Override
        public EmbeddingGateway browserEmbeddings() {
            return request -> {
                assertNoTransaction();
                if (request.content().contains("V45_EMBED_FAIL")) throw new IllegalStateException("Controlled embedding failure");
                return new EmbeddingVector(Collections.nCopies(1024, 0.1f));
            };
        }

        @Bean @Primary @Override
        public VectorStoreGateway browserVectors() {
            VectorStoreGateway taskVectors = super.browserVectors();
            return new VectorStoreGateway() {
                @Override public void upsert(VectorStoreRecord record) {
                    assertNoTransaction();
                    try {
                        Files.writeString(control().resolve("vector-upserts"), record.vectorId() + "\n",
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                }
                @Override public List<VectorSearchHit> search(VectorSearchRequest request) { return taskVectors.search(request); }
                @Override public void deleteByDocumentScope(VectorDocumentScope scope) { throw new UnsupportedOperationException("V45 excludes deletion"); }
            };
        }

        @Bean @Override
        public ApplicationRunner seedBrowserFixture(JdbcTemplate jdbc, PasswordEncoder encoder) {
            return args -> {
                super.seedBrowserFixture(jdbc, encoder).run(args);
                for (int i = 0; i < 21; i++) {
                    jdbc.update("INSERT INTO knowledge_base(id,user_id,name) VALUES (?,?,?)",
                            KB + i, OWNER, i == 0 ? "Readiness laboratory" : "Pagination knowledge " + i);
                }
                jdbc.update("UPDATE knowledge_base SET embedding_model='legacy-model' WHERE id=?", KB + 19);
                jdbc.update("UPDATE knowledge_base SET status='DISABLED' WHERE id=?", KB + 20);
                for (int i = 0; i < 22; i++) {
                    String parse = i == 0 || i == 4 ? "PENDING" : i == 3 ? "FAILED" : "COMPLETED";
                    document(jdbc, 450000000000000100L + i, KB, "state-" + i + ".txt", parse, 2);
                }
                chunk(jdbc, 101, 0, "COMPLETED", 2);
                chunk(jdbc, 102, 0, "COMPLETED", 2);
                chunk(jdbc, 102, 1, "FAILED", 2);
                chunk(jdbc, 105, 0, "PENDING", 2);
                chunk(jdbc, 105, 1, "PROCESSING", 2);
                chunk(jdbc, 105, 2, "COMPLETED", 2);
                chunk(jdbc, 105, 3, "FAILED", 2);
                // Old successful generation must never make this empty current generation READY.
                chunk(jdbc, 106, 0, "COMPLETED", 1);
                document(jdbc, 450000000000000200L, KB + 19, "incompatible.txt", "COMPLETED", 2);
                chunk(jdbc, 200, 0, "COMPLETED", 2);
                document(jdbc, 450000000000000201L, KB + 20, "disabled.txt", "COMPLETED", 2);
                chunk(jdbc, 201, 0, "COMPLETED", 2);
                // Public numeric long fields must survive browser JSON parsing exactly.
                document(jdbc, 450000000000000202L, KB, "large-generation.txt", "COMPLETED", 9007199254740993L);
                chunk(jdbc, 202, 0, "COMPLETED", 9007199254740993L);
                Files.writeString(control().resolve("knowledge-ready"), Long.toString(KB));
            };
        }
    }

    private static void document(JdbcTemplate jdbc, long id, long kb, String name, String parse, long generation) {
        jdbc.update("""
                INSERT INTO knowledge_document(id,user_id,knowledge_base_id,file_name,file_type,mime_type,file_size,
                  storage_bucket,storage_object_key,parse_status,vector_generation)
                VALUES (?,?,?,?,'TXT','text/plain',100,'test',? ,?,?)
                """, id, OWNER, kb, name, "seed-" + id, parse, generation);
    }
    private static void chunk(JdbcTemplate jdbc, int documentSuffix, int index, String status, long generation) {
        long document = 450000000000000000L + documentSuffix;
        long kb = documentSuffix == 200 ? KB + 19 : documentSuffix == 201 ? KB + 20 : KB;
        String content = "Controlled readiness row " + documentSuffix + " / " + index;
        jdbc.update("""
                INSERT INTO knowledge_chunk(id,user_id,knowledge_base_id,document_id,chunk_index,content,char_count,
                  token_count,vectorization_status,content_hash,vector_id,vector_generation,chunk_strategy_version)
                VALUES (?,?,?,?,?,?,?,15,?,?,?,?, 'structured-token-v1')
                """, 450000000000001000L + documentSuffix * 10L + index, OWNER, kb, document, index,
                content, content.length(), status, ChunkVectorIdentityFactory.contentHash(content),
                "COMPLETED".equals(status) ? java.util.UUID.randomUUID().toString() : null, generation);
    }
    private static Path control() { return Path.of(System.getProperty("v43.control-dir")); }
    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Provider I/O inside a transaction");
    }
}
