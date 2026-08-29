package com.agentflow.knowledge.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/**
 * Local V19 allowlist. The application-wide Jackson configuration intentionally stays
 * untouched, while this write endpoint accepts only its two documented metadata fields.
 */
public final class UpdateKnowledgeBaseMetadataRequestDeserializer
        extends StdDeserializer<UpdateKnowledgeBaseMetadataRequest> {
    private static final Set<String> ALLOWED_FIELDS = Set.of("name", "description");

    public UpdateKnowledgeBaseMetadataRequestDeserializer() {
        super(UpdateKnowledgeBaseMetadataRequest.class);
    }

    @Override
    public UpdateKnowledgeBaseMetadataRequest deserialize(
            JsonParser parser,
            DeserializationContext context
    ) throws IOException {
        JsonNode body = parser.getCodec().readTree(parser);
        if (body == null || !body.isObject()) {
            throw JsonMappingException.from(parser, "knowledge-base update body must be a JSON object");
        }

        Iterator<String> fieldNames = body.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!ALLOWED_FIELDS.contains(fieldName)) {
                throw JsonMappingException.from(
                        parser,
                        "knowledge-base update does not allow field: " + fieldName
                );
            }
        }

        return new UpdateKnowledgeBaseMetadataRequest(
                body.has("name"),
                nullableText(body, "name", parser),
                body.has("description"),
                nullableText(body, "description", parser)
        );
    }

    private static String nullableText(JsonNode body, String fieldName, JsonParser parser)
            throws JsonMappingException {
        JsonNode value = body.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw JsonMappingException.from(parser, fieldName + " must be a string");
        }
        return value.textValue();
    }
}
