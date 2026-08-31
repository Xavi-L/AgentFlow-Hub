package com.agentflow.tool;

import java.util.Objects;

/** Safe report_generate data wrapper containing only the deterministic Markdown report. */
public record ReportGenerateToolData(String markdown) {
    public ReportGenerateToolData {
        Objects.requireNonNull(markdown, "markdown must not be null");
    }
}
