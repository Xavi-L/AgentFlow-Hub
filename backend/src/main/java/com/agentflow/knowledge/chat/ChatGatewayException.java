package com.agentflow.knowledge.chat;

/** Provider/configuration/response failure at the ChatGateway boundary; never expose it directly to HTTP clients. */
public final class ChatGatewayException extends RuntimeException {
    public ChatGatewayException(String message) {
        super(message);
    }

    public ChatGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
