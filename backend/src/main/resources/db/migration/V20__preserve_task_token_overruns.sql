-- Provider usage is an observed fact even when it exceeds the admitted budget.
-- Keep nonnegative/additive accounting and the budget fence for successful tasks.
ALTER TABLE agent_task DROP CONSTRAINT ck_agent_task_token_totals;
ALTER TABLE agent_task ADD CONSTRAINT ck_agent_task_token_totals CHECK (
    input_tokens >= 0
    AND output_tokens >= 0
    AND total_tokens >= 0
    AND total_tokens::BIGINT = input_tokens::BIGINT + output_tokens::BIGINT
    AND (total_tokens <= max_total_tokens OR status IN ('FAILED', 'TIMED_OUT', 'CANCELLED'))
);
