package com.agentflow.agent.task.sse;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import java.util.List;

/** Raw strings avoid permissive numeric binding and silently ignored duplicate cursors. */
public final class TaskSseCursor {
    private TaskSseCursor() { }

    public static long resolve(List<String> query, List<String> header) {
        Long q = parse(query);
        Long h = parse(header);
        if (q != null && h != null && !q.equals(h)) throw invalid();
        return q != null ? q : h != null ? h : 0;
    }

    private static Long parse(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        if (values.size() != 1) throw invalid();
        String value = values.getFirst();
        if (value == null || value.isEmpty() || value.length() > 19) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') throw invalid();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.COMMON_PARAM_INVALID,
                "Cursors must be single, matching non-negative decimal long values");
    }
}
