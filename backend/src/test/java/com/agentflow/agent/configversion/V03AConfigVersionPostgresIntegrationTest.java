package com.agentflow.agent.configversion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.agentflow.agent.engine.TaskExecutionProperties;
import com.agentflow.agent.task.dispatch.TaskDispatcher;
import com.agentflow.agent.task.service.TaskRequestFingerprint;
import com.agentflow.knowledge.vector.ChunkVectorIdentityFactory;
import com.agentflow.user.model.AppUser;
import com.agentflow.user.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real PostgreSQL migration, JWT and HTTP admission; dispatch is controlled and never calls providers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "agentflow.postgres.integration", matches = "true")
class V03AConfigVersionPostgresIntegrationTest {
    static final long OWNER = 7301, OTHER = 7302, AGENT = 7303, OTHER_AGENT = 7304, SAME_OWNER_AGENT = 7305;
    static final long KB = 7310, KB2 = 7311, DOC = 7320, DOC2 = 7321;
    static final long ORDER_TOOL = 270000000000000001L, PAYMENT_TOOL = 280000000000000001L;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @Autowired TaskExecutionProperties defaults;
    @Autowired TaskRequestFingerprint fingerprints;
    @MockBean TaskDispatcher dispatcher;
    String owner, other;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getProperty("agentflow.postgres.url"));
        r.add("spring.datasource.username", () -> System.getProperty("agentflow.postgres.user"));
        r.add("spring.datasource.password", () -> System.getProperty("agentflow.postgres.password", ""));
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
        r.add("agentflow.security.jwt.secret-base64", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("mybatis-plus.configuration.log-impl", () -> "org.apache.ibatis.logging.nologging.NoLoggingImpl");
        r.add("logging.level.com.agentflow", () -> "WARN");
    }

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE app_user CASCADE");
        jdbc.update("UPDATE tool_definition SET status='ACTIVE',deleted_at=NULL WHERE id IN (?,?)", ORDER_TOOL, PAYMENT_TOOL);
        jdbc.update("UPDATE tool_definition SET config=jsonb_set(config,'{handler}',to_jsonb('orderQueryTool'::text)) WHERE id=?", ORDER_TOOL);
        jdbc.update("INSERT INTO app_user(id,username,email,password_hash,display_name) VALUES (?, 'v03-owner','v03-owner@example.test','hash','Owner'), (?, 'v03-other','v03-other@example.test','hash','Other')", OWNER, OTHER);
        for (long[] pair : List.of(new long[]{AGENT, OWNER}, new long[]{OTHER_AGENT, OTHER}, new long[]{SAME_OWNER_AGENT, OWNER})) {
            jdbc.update("""
                INSERT INTO agent_app(id,user_id,name,system_prompt,model_provider,model_name,max_steps,max_tool_calls,max_tokens,timeout_seconds)
                VALUES (?,?,'Display name','  中文 original\n','openai-compatible','controlled-model',6,4,50000,120)
                """, pair[0], pair[1]);
        }
        for (long[] pair : List.of(new long[]{KB,DOC}, new long[]{KB2,DOC2})) {
            jdbc.update("INSERT INTO knowledge_base(id,user_id,name) VALUES (?,?,'Knowledge')", pair[0], OWNER);
            jdbc.update("""
                INSERT INTO knowledge_document(id,user_id,knowledge_base_id,file_name,file_type,mime_type,file_size,
                    storage_bucket,storage_object_key,parse_status,vector_generation)
                VALUES (?,?,?,'fixture.txt','TXT','text/plain',10,'test',?,'COMPLETED',1)
                """, pair[1], OWNER, pair[0], "v03-"+pair[1]);
            String content = "Controlled knowledge";
            jdbc.update("""
                INSERT INTO knowledge_chunk(id,user_id,knowledge_base_id,document_id,chunk_index,content,char_count,token_count,
                    vectorization_status,content_hash,vector_id,vector_generation,chunk_strategy_version)
                VALUES (?,?,?,?,0,?,?,4,'COMPLETED',?,?,1,'structured-token-v1')
                """, pair[1]+100, OWNER, pair[0], pair[1], content, content.length(),
                    ChunkVectorIdentityFactory.contentHash(content), "73000000-0000-0000-0000-"+String.format("%012d",pair[1]));
        }
        jdbc.update("INSERT INTO agent_knowledge_binding(id,user_id,agent_id,knowledge_base_id,priority) VALUES (7340,?,?,?,0)", OWNER, AGENT, KB);
        jdbc.update("INSERT INTO agent_tool_binding(id,user_id,agent_id,tool_id,priority) VALUES (7350,?,?,?,0),(7351,?,?,?,1)", OWNER, AGENT, ORDER_TOOL, OWNER, AGENT, PAYMENT_TOOL);
        defaults.setDecisionMaxOutputTokens(512);
        defaults.setFinalMaxOutputTokens(null);
        defaults.setDecisionJsonObjectEnabled(false);
        defaults.setDecisionJsonSchemaEnabled(false);
        defaults.setProviderThinkingDisabled(false);
        reset(dispatcher);
        owner = token(OWNER); other = token(OTHER);
    }

    @Test
    void a01FreezesPromptBudgetsEveryOverrideAndBothBindingSelections() throws Exception {
        JsonNode version = publish(201);
        String id = version.path("configVersionId").asText();
        assertThat(version.path("config").path("executionSettingsOverrides")).hasSize(5);
        version.path("config").path("executionSettingsOverrides").forEach(v -> assertThat(v.isNull()).isTrue());
        JsonNode first = task("first", id, " raw input ", 201);
        JsonNode frozen = snapshot(first);
        jdbc.update("""
            UPDATE agent_app SET system_prompt='new prompt',max_steps=8,max_tool_calls=5,max_tokens=60000,timeout_seconds=150,
                decision_max_output_tokens=768,final_max_output_tokens=4096,decision_response_format='PROMPT_ONLY',
                thinking_mode='PROVIDER_DEFAULT',model_call_timeout_seconds=45 WHERE id=?
            """, AGENT);
        jdbc.update("UPDATE agent_knowledge_binding SET knowledge_base_id=? WHERE agent_id=?", KB2, AGENT);
        jdbc.update("DELETE FROM agent_tool_binding WHERE agent_id=? AND tool_id=?", AGENT, ORDER_TOOL);
        JsonNode secondVersion = publish(201);
        assertThat(secondVersion.path("configHash")).isNotEqualTo(version.path("configHash"));
        JsonNode selected = task("selected-old", id, "raw input", 201);
        assertThat(snapshot(selected)).isEqualTo(frozen);
        JsonNode current = task("current", null, "raw input", 201);
        assertThat(snapshot(current).path("agent").path("systemPrompt").asText()).isEqualTo("new prompt");
        assertThat(snapshot(current).path("tools")).hasSize(1);
        assertThat(snapshot(current).path("retrieval").path("knowledgeBases").get(0).path("knowledgeBaseId").asText()).isEqualTo(Long.toString(KB2));
        assertThat(snapshot(first)).isEqualTo(frozen);
        assertThat(data(get(owner, versions()+"/"+id),200)).isEqualTo(version);
        assertThat(first.path("configuration").path("effectiveConfigHash").asText()).isEqualTo(ConfigCanonicalJson.effectiveConfigHash(frozen));
        assertThat(frozen.path("snapshotVersion").asText()).isEqualTo("agent-task-snapshot-v2");
    }

    @Test
    void a02SameVersionTracksChangedDeploymentDefaultsWithoutChangingRawNulls() throws Exception {
        JsonNode version = publish(201);
        String id = version.path("configVersionId").asText();
        JsonNode before = task("defaults-before",id,"input",201);
        defaults.setDecisionMaxOutputTokens(1024);
        JsonNode after = task("defaults-after",id,"input",201);
        assertThat(after.path("configuration").path("configHash")).isEqualTo(before.path("configuration").path("configHash"));
        assertThat(after.path("configuration").path("effectiveConfigHash")).isNotEqualTo(before.path("configuration").path("effectiveConfigHash"));
        assertThat(snapshot(before).path("executionSettings").path("decisionMaxOutputTokens").asInt()).isEqualTo(512);
        assertThat(snapshot(after).path("executionSettings").path("decisionMaxOutputTokens").asInt()).isEqualTo(1024);
        assertThat(data(get(owner, versions()+"/"+id),200).path("config").path("executionSettingsOverrides").path("decisionMaxOutputTokens").isNull()).isTrue();
    }

    @Test
    void a03WaitsForCommittedDraftAndBindingViewAndDeduplicatesConcurrentPublication() throws Exception {
        try (Connection connection = datasource.getConnection(); var pool = Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            connection.createStatement().executeUpdate("UPDATE agent_app SET system_prompt='atomic new' WHERE id="+AGENT);
            connection.createStatement().executeUpdate("UPDATE agent_tool_binding SET priority=priority+5 WHERE agent_id="+AGENT);
            var publication = pool.submit(() -> post(owner,versions(),null,Map.of()));
            var creation = pool.submit(() -> task("atomic",null,"input",201));
            // Wait for an actual PostgreSQL lock wait rather than inferring overlap from scheduling.
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                    jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'", Integer.class) >= 1);
            connection.commit();
            JsonNode version = data(publication.get(20,TimeUnit.SECONDS),200,201);
            JsonNode task = creation.get(20,TimeUnit.SECONDS);
            assertThat(version.path("config").path("systemPrompt").asText()).isEqualTo("atomic new");
            assertThat(version.path("config").path("toolBindings").get(0).path("priority").asInt()).isEqualTo(5);
            assertThat(snapshot(task).path("agent").path("systemPrompt").asText()).isEqualTo("atomic new");
            assertThat(task.path("configuration").path("configHash")).isEqualTo(version.path("configHash"));
        }
        assertThat(count("agent_config_version")).isEqualTo(1);
        var responses = concurrent(6, () -> post(owner,versions(),null,Map.of()));
        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode().value()).isEqualTo(200));
        assertThat(count("agent_config_version")).isEqualTo(1);
    }

    @Test
    void a04VersionSelectionCannotBypassOwnerAgentReadyOrToolAdmission() {
        String id = publish(201).path("configVersionId").asText();
        error(get(other,versions()+"/"+id),404,"COMMON_NOT_FOUND");
        error(get(owner,"/agents/"+SAME_OWNER_AGENT+"/config-versions/"+id),404,"COMMON_NOT_FOUND");
        error(get(other,versions()),404,"COMMON_NOT_FOUND");
        error(post(other,versions(),null,Map.of()),404,"COMMON_NOT_FOUND");
        error(post(owner,"/agents/"+SAME_OWNER_AGENT+"/tasks","wrong-agent",Map.of("userInput","input","configVersionId",id)),404,"COMMON_NOT_FOUND");
        jdbc.update("UPDATE agent_app SET status='DISABLED' WHERE id=?",AGENT);
        error(create("disabled",id,"input"),409,"AGENT_DISABLED");
        jdbc.update("UPDATE agent_app SET status='ACTIVE' WHERE id=?",AGENT);
        jdbc.update("UPDATE knowledge_document SET parse_status='PENDING' WHERE id=?",DOC);
        error(create("not-ready",id,"input"),409,"RAG_KNOWLEDGE_NOT_READY");
        jdbc.update("UPDATE knowledge_document SET parse_status='COMPLETED' WHERE id=?",DOC);
        jdbc.update("UPDATE tool_definition SET config=jsonb_set(config,'{handler}',to_jsonb('wrong'::text)) WHERE id=?",ORDER_TOOL);
        error(create("bad-tool",id,"input"),409,"AGENT_BINDING_INVALID");
        assertThat(count("agent_task")).isZero();
        verifyNoInteractions(dispatcher);
    }

    @Test
    void a05KeepsHistoricalV1V2IdentityMissingAndOldFingerprintsByteExact() throws Exception {
        for (String format : List.of("agent-task-snapshot-v1","agent-task-snapshot-v2")) {
            String key = "historical-"+format;
            JsonNode task = task(key,null,"  历史\n",201);
            ObjectNode oldSnapshot = (ObjectNode) snapshot(task);
            oldSnapshot.put("snapshotVersion",format);
            if (format.endsWith("v1")) oldSnapshot.remove("executionSettings");
            long taskId = task.path("taskId").asLong();
            String fingerprint = jdbc.queryForObject("SELECT request_fingerprint FROM agent_task WHERE id=?",String.class,taskId);
            assertThat(fingerprint).isEqualTo(fingerprints.calculate(AGENT,"  历史\n").sha256());
            jdbc.update("UPDATE agent_task SET config_version_id=NULL,config_hash=NULL,effective_config_hash=NULL,hash_algorithm_version=NULL,execution_snapshot=?::jsonb WHERE id=?",oldSnapshot.toString(),taskId);
            jdbc.update("UPDATE agent_app SET status='DISABLED',system_prompt='today' WHERE id=?",AGENT);
            JsonNode replay = task(key,null,"  历史\n",200);
            assertThat(replay.path("taskId")).isEqualTo(task.path("taskId"));
            assertThat(replay.path("configuration").isMissingNode() || replay.path("configuration").isNull()).isTrue();
            JsonNode trace = data(get(owner,"/tasks/"+taskId+"/trace"),200);
            assertThat(trace.path("executionSnapshot")).isEqualTo(oldSnapshot);
            assertThat(trace.path("configuration").isMissingNode() || trace.path("configuration").isNull()).isTrue();
            assertThat(jdbc.queryForObject("SELECT request_fingerprint FROM agent_task WHERE id=?",String.class,taskId)).isEqualTo(fingerprint);
            jdbc.update("UPDATE agent_app SET status='ACTIVE' WHERE id=?",AGENT);
        }
    }

    @Test
    void a06ConflictsOnExplicitVersionRequestShapeAndOriginalInputWhitespace() {
        String first = publish(201).path("configVersionId").asText();
        JsonNode implicit = task("old-key",null," x ",201);
        error(create("old-key",first," x "),409,"TASK_IDEMPOTENCY_CONFLICT");
        error(create("old-key",null,"x"),409,"TASK_IDEMPOTENCY_CONFLICT");
        JsonNode explicit = task("new-key",first," x ",201);
        error(create("new-key",null," x "),409,"TASK_IDEMPOTENCY_CONFLICT");
        jdbc.update("UPDATE agent_app SET system_prompt='different' WHERE id=?",AGENT);
        String second = publish(201).path("configVersionId").asText();
        error(create("new-key",second," x "),409,"TASK_IDEMPOTENCY_CONFLICT");
        jdbc.update("UPDATE agent_app SET status='DISABLED' WHERE id=?",AGENT);
        assertThat(task("old-key",null," x ",200).path("taskId")).isEqualTo(implicit.path("taskId"));
        assertThat(task("new-key",first," x ",200).path("taskId")).isEqualTo(explicit.path("taskId"));
    }

    @Test
    void a07ConcurrentAndLostResponsesHaveOneVersionOneTaskOneDispatch() throws Exception {
        var versions = concurrent(6, () -> post(owner,versions(),null,Map.of()));
        assertThat(versions.stream().filter(v -> v.getStatusCode().value()==201).count()).isEqualTo(1);
        for (var response : versions) data(response,200,201);
        String id = versions.getFirst().getBody().path("data").path("configVersionId").asText();
        var tasks = concurrent(6, () -> create("same-case",id,"input"));
        assertThat(tasks.stream().filter(v -> v.getStatusCode().value()==201).count()).isEqualTo(1);
        for (var response : tasks) data(response,200,201);
        String taskId = tasks.getFirst().getBody().path("data").path("taskId").asText();
        assertThat(task("same-case",id,"input",200).path("taskId").asText()).isEqualTo(taskId);
        assertThat(publish(200).path("configVersionId").asText()).isEqualTo(id);
        assertThat(count("agent_config_version")).isEqualTo(1);
        assertThat(count("agent_task")).isEqualTo(1);
        assertThat(count("agent_task_event")).isEqualTo(1);
        verify(dispatcher,times(1)).dispatch(Long.valueOf(taskId));
    }

    @Test
    void a12LabelsDoNotChangeIdentityButBindingPriorityAndPromptDo() {
        JsonNode first = publish(201);
        jdbc.update("UPDATE agent_app SET name='renamed',description='label only' WHERE id=?",AGENT);
        assertThat(publish(200).path("configHash")).isEqualTo(first.path("configHash"));
        jdbc.update("UPDATE agent_tool_binding SET priority=1-priority WHERE agent_id=?",AGENT);
        assertThat(publish(201).path("configHash")).isNotEqualTo(first.path("configHash"));
        JsonNode list = data(get(owner,versions()+"?page=1&pageSize=1"),200);
        assertThat(list.path("items")).hasSize(1);
        assertThat(list.path("total").asInt()).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_config_version SET config_hash=repeat('0',64) WHERE id=?", first.path("configVersionId").asLong()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void a13RejectsSecretPublicationAndForeignTraceAndUsesOwnerScopedForeignKey() {
        JsonNode version = publish(201);
        JsonNode task = task("owner-trace",version.path("configVersionId").asText(),"input",201);
        error(get(other,"/tasks/"+task.path("taskId").asText()+"/trace"),404,"COMMON_NOT_FOUND");
        jdbc.update("UPDATE agent_app SET system_prompt='api_key=super-secret-fixture' WHERE id=?",AGENT);
        var rejected = post(owner,versions(),null,Map.of());
        error(rejected,400,"COMMON_PARAM_INVALID");
        assertThat(rejected.getBody().toString()).doesNotContain("super-secret-fixture");
        assertThat(count("agent_config_version")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_task SET agent_id=? WHERE id=?",SAME_OWNER_AGENT,task.path("taskId").asLong()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        error(post(owner,versions(),null,Map.of("provider","arbitrary")),400,"COMMON_PARAM_INVALID");
    }

    @Test
    void failedTaskCreationRollsBackImplicitVersionAndEventBeforeDispatch() {
        jdbc.update("UPDATE knowledge_document SET parse_status='PENDING' WHERE id=?",DOC);
        error(create("rollback",null,"input"),409,"RAG_KNOWLEDGE_NOT_READY");
        assertThat(count("agent_config_version")).isZero();
        assertThat(count("agent_task")).isZero();
        assertThat(count("agent_task_event")).isZero();
        verifyNoInteractions(dispatcher);
    }

    String token(long id) { AppUser user=new AppUser(); user.setId(id); return jwt.issueAccessToken(user).value(); }
    String versions() { return "/agents/"+AGENT+"/config-versions"; }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    JsonNode publish(int status) { return data(post(owner,versions(),null,Map.of()),status); }
    JsonNode task(String key,String version,String input,int status) { return data(create(key,version,input),status); }
    ResponseEntity<JsonNode> create(String key,String version,String input) {
        var body=json.createObjectNode().put("userInput",input);
        if(version!=null) body.put("configVersionId",version);
        return post(owner,"/agents/"+AGENT+"/tasks",key,body);
    }
    JsonNode snapshot(JsonNode task) throws Exception {
        return json.readTree(jdbc.queryForObject("SELECT execution_snapshot::text FROM agent_task WHERE id=?",String.class,task.path("taskId").asLong()));
    }
    ResponseEntity<JsonNode> get(String token,String path) {
        return http.exchange("/api/v1"+path,HttpMethod.GET,new HttpEntity<>(headers(token,null)),JsonNode.class);
    }
    ResponseEntity<JsonNode> post(String token,String path,String key,Object body) {
        return http.exchange("/api/v1"+path,HttpMethod.POST,new HttpEntity<>(body,headers(token,key)),JsonNode.class);
    }
    HttpHeaders headers(String token,String key) {
        var headers=new HttpHeaders(); headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON);
        if(key!=null) headers.set("Idempotency-Key",key); return headers;
    }
    JsonNode data(ResponseEntity<JsonNode> response,int... status) {
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isIn(java.util.Arrays.stream(status).boxed().toList());
        return response.getBody().path("data");
    }
    void error(ResponseEntity<JsonNode> response,int status,String code) {
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
    }
    List<ResponseEntity<JsonNode>> concurrent(int count,java.util.concurrent.Callable<ResponseEntity<JsonNode>> work) throws Exception {
        var barrier=new CyclicBarrier(count); var result=new ArrayList<ResponseEntity<JsonNode>>();
        try(var pool=Executors.newFixedThreadPool(count)) {
            var futures=new ArrayList<java.util.concurrent.Future<ResponseEntity<JsonNode>>>();
            for(int i=0;i<count;i++) futures.add(pool.submit(() -> { barrier.await(10,TimeUnit.SECONDS); return work.call(); }));
            for(var future:futures) result.add(future.get(30,TimeUnit.SECONDS));
        }
        return result;
    }
}
