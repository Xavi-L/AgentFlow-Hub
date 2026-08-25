package com.agentflow.knowledge.chat;

import java.time.Duration;
import java.util.Objects;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Builds the narrow V9 provider client without coupling V9 to the V6 vector package. */
final class ChatRestClientFactory {
    private ChatRestClientFactory() {
    }

    static RestClient create(String baseUrl, Duration timeout) {
        String safeBaseUrl = requireNonBlank(baseUrl, "baseUrl").replaceAll("/+$", "");
        Duration safeTimeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (safeTimeout.isNegative() || safeTimeout.isZero() || safeTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("timeout must be between 1ms and Integer.MAX_VALUE milliseconds");
        }

        int timeoutMillis = Math.toIntExact(safeTimeout.toMillis());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        return RestClient.builder()
                .baseUrl(safeBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
