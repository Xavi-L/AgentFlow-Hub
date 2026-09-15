# 图 03：节点宽度调整前的历史失败基线

基线提交为 `fbf54421f031a93d8b810423199651cf933b4437`。候选、validation、acceptance、incomplete 文件均保留原字节。

- [候选](03-task-e2e.candidate.workflow.json)：11 个节点均为 150×68，自动反馈路由，left/left + labelSegment 1。
- [完整失败回执](03-task-e2e.candidate.validation.json)：artifact checks 9/9；showcase desktop-readability 失败。
- [验收记录](03-task-e2e.acceptance.json)、[未完成状态](03-task-e2e.incomplete.json)：对应本次缩宽之前的失败基线。
- [诊断 layout](03-task-e2e.candidate.layout.json)、[命令退出码](layout-command.json)：本轮修改前额外测得 viewBox = requiredViewBox = 1326×534。layout 退出 0 不代表完整 showcase 验证成功。

首版六泳道 HTML 与失败浏览器回执另见 [此前历史快照](../03-before-supported-auto/README.md)。当前交付状态请查看主目录的 acceptance 与 Atlas。
