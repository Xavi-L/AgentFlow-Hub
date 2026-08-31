-- V28: add the second database-defined built-in tool without changing the V12/V13 contracts.

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
    280000000000000001,
    'payment_log_query',
    'Payment Log Query',
    'Query shared demo payment logs by order number or error code without modifying business data.',
    'BUILTIN',
    '{
      "type": "object",
      "properties": {
        "orderNo": {
          "type": "string",
          "minLength": 1,
          "maxLength": 64
        },
        "errorCode": {
          "type": "string",
          "minLength": 1,
          "maxLength": 64
        },
        "limit": {
          "type": "integer",
          "minimum": 1,
          "maximum": 20,
          "default": 10
        }
      },
      "anyOf": [
        {"required": ["orderNo"]},
        {"required": ["errorCode"]}
      ],
      "additionalProperties": false
    }'::jsonb,
    '{
      "type": "object",
      "properties": {
        "logs": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "orderNo": {"type": "string"},
              "traceId": {"type": "string"},
              "level": {"type": "string"},
              "errorCode": {"type": ["string", "null"]},
              "message": {"type": "string"},
              "occurredAt": {"type": "string"}
            },
            "required": ["orderNo", "traceId", "level", "errorCode", "message", "occurredAt"],
            "additionalProperties": false
          }
        }
      },
      "required": ["logs"],
      "additionalProperties": false
    }'::jsonb,
    '{"handler": "paymentLogQueryTool", "readonly": true}'::jsonb,
    5000,
    0,
    FALSE,
    'MEDIUM',
    'ACTIVE',
    TIMESTAMPTZ '2026-05-01 12:00:00+08:00',
    TIMESTAMPTZ '2026-05-01 12:00:00+08:00'
);
