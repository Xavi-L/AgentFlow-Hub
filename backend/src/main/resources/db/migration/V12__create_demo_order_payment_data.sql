-- V26: deterministic, read-only demo business data for the later built-in tools.
-- ToolRuntime, tool definitions, call logs, tickets, and mutable demo APIs remain
-- outside this migration and this slice. Keep this file immutable after Flyway applies it.

CREATE TABLE mock_order (
    id BIGINT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payment_status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_mock_order_no UNIQUE (order_no),
    CONSTRAINT ck_mock_order_no_not_blank
        CHECK (char_length(btrim(order_no)) > 0),
    CONSTRAINT ck_mock_order_user_no_not_blank
        CHECK (char_length(btrim(user_no)) > 0),
    CONSTRAINT ck_mock_order_amount_nonnegative
        CHECK (amount >= 0)
);

CREATE TABLE mock_payment_log (
    id BIGINT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    log_level VARCHAR(16) NOT NULL,
    error_code VARCHAR(64),
    message TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_mock_payment_log_order_no_not_blank
        CHECK (char_length(btrim(order_no)) > 0),
    CONSTRAINT ck_mock_payment_log_trace_id_not_blank
        CHECK (char_length(btrim(trace_id)) > 0),
    CONSTRAINT ck_mock_payment_log_level
        CHECK (log_level IN ('INFO', 'WARN', 'ERROR')),
    CONSTRAINT ck_mock_payment_log_message_not_blank
        CHECK (char_length(btrim(message)) > 0)
);

CREATE INDEX idx_payment_log_order
    ON mock_payment_log (order_no, occurred_at DESC);

CREATE INDEX idx_payment_log_error
    ON mock_payment_log (error_code);

-- Fixed IDs and timestamps make the fixture reproducible across local databases.
INSERT INTO mock_order (
    id,
    order_no,
    user_no,
    amount,
    currency,
    status,
    payment_status,
    error_code,
    created_at,
    updated_at
) VALUES (
    260000000000000001,
    'order_1024',
    'demo_user_1024',
    199.00,
    'CNY',
    'CREATED',
    'PAY_FAILED',
    'E_PAY_TIMEOUT',
    TIMESTAMPTZ '2026-05-01 12:00:00+08:00',
    TIMESTAMPTZ '2026-05-01 12:00:00+08:00'
);

INSERT INTO mock_payment_log (
    id,
    order_no,
    trace_id,
    log_level,
    error_code,
    message,
    occurred_at,
    created_at
) VALUES (
    260000000000000002,
    'order_1024',
    'pay-trace-1024',
    'ERROR',
    'E_PAY_TIMEOUT',
    'Payment gateway response timeout after 3000ms',
    TIMESTAMPTZ '2026-05-01 12:00:00+08:00',
    TIMESTAMPTZ '2026-05-01 12:00:00+08:00'
);
