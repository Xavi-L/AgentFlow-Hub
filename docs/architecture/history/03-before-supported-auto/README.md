# 图 03：采用 supported automatic route 前的历史快照

此目录按原字节保存本轮修改前的图 03 文件（基线提交 `c10ee75e39e66f07be5d294e6fdc64f0d9ec9762`）。它们是历史记录，不是当前候选的成功证据。

- [候选 source](03-task-e2e.candidate.workflow.json)、[失败 validation](03-task-e2e.candidate.validation.json)、[完整 predicate probe](03-task-e2e.candidate.predicates.json)：对应 return-left + left/left + labelSegment 1，失败 predicate 为 routeClearsPlacedLabels。
- [首版 source](03-task-e2e.workflow.json)、[HTML](03-task-e2e.html)、[delivery](03-task-e2e.delivery.json)：首版成功 render/deliver，但 [browser evidence](03-task-e2e.visual-check.json) 与 [visual review](03-task-e2e.visual-review.json) 失败。
- [未完成状态](03-task-e2e.incomplete.json)：保留本轮修改前的候选与首版产物身份。

回执内原绝对路径保留为运行时事实；请按本目录的候选/产物及回执中的身份区分历史与当前证据。
