package com.agentflow.agent.trace;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TracePayloadProperties.class)
public class TraceConfiguration {
}
