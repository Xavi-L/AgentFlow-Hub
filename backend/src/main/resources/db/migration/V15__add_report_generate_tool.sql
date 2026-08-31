-- V29: add the third database-defined built-in tool without changing V12-V14 contracts.

INSERT INTO tool_definition (
    id,
    tool_code,
    name,
    description,
    type,
    input_schema,
    output_schema,
    config,
    timeout_ms,
    retry_count,
    requires_confirmation,
    permission_level,
    status,
    created_at,
    updated_at
) VALUES (
    290000000000000001,
    'report_generate',
    'Report Generate',
    'Generate a deterministic Markdown handling report from supplied analysis fields.',
    'BUILTIN',
    '{
      "type": "object",
      "properties": {
        "title": {
          "type": "string",
          "minLength": 1,
          "maxLength": 255
        },
        "summary": {
          "type": "string",
          "minLength": 1,
          "maxLength": 4000
        },
        "rootCause": {
          "type": "string",
          "minLength": 1,
          "maxLength": 4000
        },
        "suggestions": {
          "type": "array",
          "minItems": 1,
          "maxItems": 20,
          "items": {
            "type": "string",
            "minLength": 1,
            "maxLength": 1000
          }
        }
      },
      "required": ["title", "summary"],
      "additionalProperties": false
    }'::jsonb,
    '{
      "type": "object",
      "properties": {
        "markdown": {"type": "string"}
      },
      "required": ["markdown"],
      "additionalProperties": false
    }'::jsonb,
    '{"handler": "reportGenerateTool", "readonly": true}'::jsonb,
    10000,
    0,
    FALSE,
    'LOW',
    'ACTIVE',
    TIMESTAMPTZ '2026-05-01 12:00:00+08:00',
    TIMESTAMPTZ '2026-05-01 12:00:00+08:00'
);
