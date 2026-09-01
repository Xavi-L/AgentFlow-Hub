package com.agentflow.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.config.OpenAiChatProperties;
import com.agentflow.config.SpringAiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;

class SpringAiOpenAiCompatibleLlmGatewayHttpTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldSerializeTheOpenAiCompatibleWireContractAndParseAllReturnedMetadata() throws Exception {
        try (LocalChatStub stub = new LocalChatStub()) {
            stub.respond(200, successfulResponse(true), Duration.ZERO);
            LlmGateway gateway = gateway(stub.baseUrl(), "  test-openai-key  ", Duration.ofSeconds(2));

            LlmChatResult result = gateway.chat(request());

            assertThat(stub.requestCount()).isEqualTo(1);
            CapturedRequest captured = stub.capturedRequest();
            assertThat(captured.method()).isEqualTo("POST");
            assertThat(captured.path()).isEqualTo("/v1/chat/completions");
            assertThat(captured.header("Authorization")).isEqualTo("Bearer test-openai-key");
            assertThat(captured.header("Content-Type")).startsWith("application/json");

            JsonNode body = OBJECT_MAPPER.readTree(captured.body());
            assertThat(body.path("model").textValue()).isEqualTo("request-model-a");
            assertThat(body.path("temperature").decimalValue()).isEqualByComparingTo("0.2");
            assertThat(body.path("top_p").decimalValue()).isEqualByComparingTo("0.8");
            assertThat(body.path("max_tokens").intValue()).isEqualTo(1024);
            assertThat(body.path("n").intValue()).isEqualTo(1);
            assertThat(body.path("stream").booleanValue()).isFalse();
            assertThat(body.has("tools")).isFalse();
            assertThat(body.has("max_completion_tokens")).isFalse();
            assertThat(body.path("messages")).hasSize(3);
            assertMessage(body, 0, "system", "system line 1\nsystem line 2");
            assertMessage(body, 1, "user", "  exact user body  ");
            assertMessage(body, 2, "assistant", "prior assistant answer");

            assertThat(result.content()).isEqualTo("provider answer");
            assertThat(result.resolvedModel()).isEqualTo("resolved-model-b");
            assertThat(result.finishReason()).isEqualTo("stop");
            assertThat(result.usage()).isEqualTo(LlmTokenUsage.known(17, 5, 22));
            assertThat(result.providerRequestId()).isEqualTo("chatcmpl-provider-42");
            assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void shouldOmitAuthorizationForAnEmptyKeyAndKeepAbsentUsageUnknown() throws Exception {
        try (LocalChatStub stub = new LocalChatStub()) {
            stub.respond(200, successfulResponse(false), Duration.ZERO);
            LlmGateway gateway = gateway(stub.baseUrl(), "  ", Duration.ofSeconds(2));

            LlmChatResult result = gateway.chat(request());

            assertThat(stub.requestCount()).isEqualTo(1);
            assertThat(stub.capturedRequest().header("Authorization")).isNull();
            assertThat(result.usage()).isEqualTo(LlmTokenUsage.unknown());
            assertThat(result.usage().known()).isFalse();
        }
    }

    @Test
    void shouldApplyModelAndSamplingOptionsIndependentlyForEveryCall() throws Exception {
        try (LocalChatStub stub = new LocalChatStub()) {
            stub.respond(200, successfulResponse(true), Duration.ZERO);
            LlmGateway gateway = gateway(stub.baseUrl(), "", Duration.ofSeconds(2));

            gateway.chat(request("first-model", "0.1", "0.9", 111));
            JsonNode firstBody = OBJECT_MAPPER.readTree(stub.capturedRequest().body());
            gateway.chat(request("second-model", "1.7", "0.4", 222));
            JsonNode secondBody = OBJECT_MAPPER.readTree(stub.capturedRequest().body());

            assertThat(firstBody.path("model").textValue()).isEqualTo("first-model");
            assertThat(firstBody.path("temperature").decimalValue()).isEqualByComparingTo("0.1");
            assertThat(firstBody.path("top_p").decimalValue()).isEqualByComparingTo("0.9");
            assertThat(firstBody.path("max_tokens").intValue()).isEqualTo(111);
            assertThat(secondBody.path("model").textValue()).isEqualTo("second-model");
            assertThat(secondBody.path("temperature").decimalValue()).isEqualByComparingTo("1.7");
            assertThat(secondBody.path("top_p").decimalValue()).isEqualByComparingTo("0.4");
            assertThat(secondBody.path("max_tokens").intValue()).isEqualTo(222);
            assertThat(stub.requestCount()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500})
    void shouldClassifyNonSuccessResponsesWithoutRetryingOrLeakingTheBody(int status) throws Exception {
        String sensitiveBody = "secret-key full provider body http://internal-provider.test/v1";
        try (LocalChatStub stub = new LocalChatStub()) {
            stub.respond(status, sensitiveBody, Duration.ZERO);
            LlmGateway gateway = gateway(stub.baseUrl(), "secret-key", Duration.ofSeconds(2));

            assertThatThrownBy(() -> gateway.chat(request()))
                    .isInstanceOfSatisfying(LlmGatewayException.class, failure -> {
                        assertThat(failure.failureType()).isEqualTo(LlmFailureType.PROVIDER_REJECTED);
                        assertThat(failure.getMessage()).doesNotContain(sensitiveBody);
                        assertThat(failure.getMessage()).doesNotContain("secret-key");
                        assertThat(failure.getMessage()).doesNotContain(stub.baseUrl());
                        assertThat(failure).hasNoCause();
                    });
            assertThat(stub.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void shouldClassifyMalformedJsonNoChoiceMultipleChoicesAndBlankContent() throws Exception {
        try (LocalChatStub stub = new LocalChatStub()) {
            LlmGateway gateway = gateway(stub.baseUrl(), "", Duration.ofSeconds(2));

            stub.respond(200, "{not-json", Duration.ZERO);
            assertFailure(gateway, LlmFailureType.MALFORMED_RESPONSE);

            stub.respond(200, """
                    {"id":"empty","model":"m","choices":[]}
                    """, Duration.ZERO);
            assertFailure(gateway, LlmFailureType.MALFORMED_RESPONSE);

            stub.respond(200, """
                    {
                      "id":"multiple",
                      "model":"m",
                      "choices":[
                        {"index":0,"message":{"role":"assistant","content":"one"},"finish_reason":"stop"},
                        {"index":1,"message":{"role":"assistant","content":"two"},"finish_reason":"stop"}
                      ]
                    }
                    """, Duration.ZERO);
            assertFailure(gateway, LlmFailureType.MALFORMED_RESPONSE);

            stub.respond(200, """
                    {
                      "id":"blank",
                      "model":"m",
                      "choices":[
                        {"index":0,"message":{"role":"assistant","content":"  "},"finish_reason":"stop"}
                      ]
                    }
                    """, Duration.ZERO);
            assertFailure(gateway, LlmFailureType.MALFORMED_RESPONSE);

            assertThat(stub.requestCount()).isEqualTo(4);
        }
    }

    @Test
    void shouldClassifyAReadTimeoutAndStillIssueOnlyOneRequest() throws Exception {
        try (LocalChatStub stub = new LocalChatStub()) {
            stub.respond(200, successfulResponse(true), Duration.ofMillis(250));
            LlmGateway gateway = gateway(stub.baseUrl(), "", Duration.ofMillis(30));

            assertFailure(gateway, LlmFailureType.TIMEOUT);

            assertThat(stub.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void shouldClassifyAConnectionFailureAsTransport() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(
                0,
                1,
                InetAddress.getByName("127.0.0.1")
        )) {
            unusedPort = socket.getLocalPort();
        }
        LlmGateway gateway = gateway(
                "http://127.0.0.1:" + unusedPort + "/v1",
                "",
                Duration.ofMillis(200)
        );

        assertFailure(gateway, LlmFailureType.TRANSPORT);
    }

    private static void assertMessage(JsonNode body, int index, String role, String content) {
        assertThat(body.path("messages").path(index).path("role").textValue()).isEqualTo(role);
        assertThat(body.path("messages").path(index).path("content").textValue()).isEqualTo(content);
    }

    private static void assertFailure(LlmGateway gateway, LlmFailureType expectedType) {
        assertThatThrownBy(() -> gateway.chat(request()))
                .isInstanceOfSatisfying(LlmGatewayException.class, failure -> {
                    assertThat(failure.failureType()).isEqualTo(expectedType);
                    assertThat(failure).hasNoCause();
                });
    }

    private static LlmGateway gateway(String baseUrl, String apiKey, Duration timeout) {
        OpenAiChatProperties properties = new OpenAiChatProperties();
        properties.setBaseUrl(baseUrl);
        properties.setApiKey(apiKey);
        properties.setTimeout(timeout);
        SpringAiConfig configuration = new SpringAiConfig();
        ChatModel chatModel = configuration.openAiCompatibleChatModel(properties);
        return configuration.llmGateway(chatModel);
    }

    private static LlmChatRequest request() {
        return request("request-model-a", "0.2", "0.8", 1024);
    }

    private static LlmChatRequest request(String model, String temperature, String topP, int maxOutputTokens) {
        return new LlmChatRequest(
                "openai-compatible",
                model,
                List.of(
                        new LlmMessage(LlmMessageRole.SYSTEM, "system line 1\nsystem line 2"),
                        new LlmMessage(LlmMessageRole.USER, "  exact user body  "),
                        new LlmMessage(LlmMessageRole.ASSISTANT, "prior assistant answer")
                ),
                new BigDecimal(temperature),
                new BigDecimal(topP),
                maxOutputTokens
        );
    }

    private static String successfulResponse(boolean includeUsage) {
        String usage = includeUsage
                ? ",\"usage\":{\"prompt_tokens\":17,\"completion_tokens\":5,\"total_tokens\":22}"
                : "";
        return """
                {
                  "id":"chatcmpl-provider-42",
                  "object":"chat.completion",
                  "created":1788220800,
                  "model":"resolved-model-b",
                  "choices":[
                    {
                      "index":0,
                      "message":{"role":"assistant","content":"provider answer"},
                      "finish_reason":"stop"
                    }
                  ]%s
                }
                """.formatted(usage);
    }

    private record StubResponse(int status, String body, Duration delay) {
    }

    private record CapturedRequest(String method, String path, Map<String, List<String>> headers, String body) {
        private String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElse(null);
        }
    }

    private static final class LocalChatStub implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final AtomicInteger requestCount = new AtomicInteger();
        private final AtomicReference<CapturedRequest> capturedRequest = new AtomicReference<>();
        private final AtomicReference<StubResponse> response = new AtomicReference<>();

        private LocalChatStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "v35-local-chat-stub");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext("/v1/chat/completions", this::handle);
            server.start();
        }

        private void respond(int status, String body, Duration delay) {
            response.set(new StubResponse(status, body, delay));
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        private int requestCount() {
            return requestCount.get();
        }

        private CapturedRequest capturedRequest() {
            return capturedRequest.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            requestCount.incrementAndGet();
            Map<String, List<String>> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((key, value) -> headers.put(key, List.copyOf(value)));
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedRequest.set(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    Map.copyOf(headers),
                    requestBody
            ));

            StubResponse configured = response.get();
            if (configured == null) {
                configured = new StubResponse(500, "stub response is not configured", Duration.ZERO);
            }
            if (!configured.delay().isZero()) {
                try {
                    Thread.sleep(configured.delay().toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    exchange.close();
                    return;
                }
            }

            byte[] body = configured.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(configured.status(), body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException clientDisconnected) {
                // Expected when the timeout test closes the client side first.
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
