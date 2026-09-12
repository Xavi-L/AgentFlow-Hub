package com.agentflow.agent.task.recovery;

/** Safe startup diagnostic; never embeds persisted prompts, provider responses or credentials. */
public final class TaskRecoveryInvariantException extends IllegalStateException {
    private final String reasonCode;

    public TaskRecoveryInvariantException(long taskId, String reasonCode) {
        super("TASK_RECOVERY_INVARIANT_VIOLATION taskId=" + taskId + " reason=" + reasonCode);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() { return reasonCode; }
}
