package com.agentflow.agent.engine;

import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.ToolSnapshot;
import com.agentflow.infra.llm.LlmResponseSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Constrains the existing two-action protocol using only the task's frozen tool definitions. */
public final class AgentDecisionResponseSchema {
    private AgentDecisionResponseSchema() { }

    public static LlmResponseSchema fromTools(ObjectMapper mapper, List<ToolSnapshot> tools) {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        var branches = schema.putArray("oneOf");
        for (ToolSnapshot tool : tools) {
            ObjectNode branch = branches.addObject().put("type", "object");
            ObjectNode fields = branch.putObject("properties");
            fields.putObject("type").put("const", "CALL_TOOL");
            fields.putObject("toolCode").put("const", tool.toolCode());
            fields.set("arguments", tool.inputSchema());
            fields.putObject("reason").put("type", "string").put("minLength", 1).put("maxLength", 256);
            branch.putArray("required").add("type").add("toolCode").add("arguments").add("reason");
            branch.put("additionalProperties", false);
        }
        ObjectNode finish = branches.addObject().put("type", "object");
        ObjectNode fields = finish.putObject("properties");
        fields.putObject("type").put("const", "FINISH");
        fields.putObject("answerPlan").put("type", "string").put("minLength", 1).put("maxLength", 2048);
        finish.putArray("required").add("type").add("answerPlan");
        finish.put("additionalProperties", false);
        return new LlmResponseSchema("agent_decision_v1", schema);
    }
}
