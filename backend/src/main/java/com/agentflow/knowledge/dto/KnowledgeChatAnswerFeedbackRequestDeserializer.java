package com.agentflow.knowledge.dto;

import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackVerdict;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/** Local allowlist that keeps V12's request body to exactly one client-controlled field. */
public final class KnowledgeChatAnswerFeedbackRequestDeserializer
        extends StdDeserializer<KnowledgeChatAnswerFeedbackRequest> {
    private static final Set<String> ALLOWED_FIELDS = Set.of("verdict");

    public KnowledgeChatAnswerFeedbackRequestDeserializer() {
        super(KnowledgeChatAnswerFeedbackRequest.class);
    }

    @Override
    public KnowledgeChatAnswerFeedbackRequest deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode body = parser.getCodec().readTree(parser);
        if (body == null || !body.isObject()) {
            throw JsonMappingException.from(parser, "feedback request body must be a JSON object");
        }

        Iterator<String> fieldNames = body.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!ALLOWED_FIELDS.contains(fieldName)) {
                throw JsonMappingException.from(
                        parser,
                        "feedback request does not allow field: " + fieldName
                );
            }
        }

        return new KnowledgeChatAnswerFeedbackRequest(readVerdict(body, parser));
    }

    private static KnowledgeChatAnswerFeedbackVerdict readVerdict(JsonNode body, JsonParser parser)
            throws JsonMappingException {
        JsonNode value = body.get("verdict");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw JsonMappingException.from(parser, "verdict must be a string");
        }
        try {
            return KnowledgeChatAnswerFeedbackVerdict.valueOf(value.textValue());
        } catch (IllegalArgumentException invalidVerdict) {
            throw JsonMappingException.from(
                    parser,
                    "verdict must be HELPFUL or NOT_HELPFUL"
            );
        }
    }
}
