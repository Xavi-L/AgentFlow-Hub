# 图 11：Archify 诊断链修复（仅临时副本）

## 这不是图 11 布局成品

本包只修复一个已经核对源码的诊断参数交接缺陷，目的是恢复具体的布局错误。
它不改图的 JSON、不产出正式 HTML、不修改 Atlas，也不修改已安装的 Skill。

已执行：最小 JavaScript 参数契约复现与补丁验证；脚本替换/拒绝保护测试；使用假 CLI 的临时副本隔离、回执捕获及清理测试，共 7 个测试。
未执行：完整原版 Archify CLI、带补丁副本的真实 Archify CLI、真实图布局验收、deliver、浏览器或视觉验收。
当前工作容器无法取得完整 Archify 包，因此没有完整编译器运行结果。
测试中使用的假 CLI 明确标注 TEST FIXTURE；它的输出不是图 11 的验证证据。

本包与之前图 06/10B 的布局修复包不同：**这里没有“修复后的图 JSON”**。

## 所核对的基线

- 用户仓库：Xavi-L/AgentFlow-Hub
- 候选：docs/architecture/11-restart-recovery.candidate.workflow.json
- 候选 Git blob：9ccc38be36f5aa672761f851b92e772258ce4f81
- 原版调用栈：docs/architecture/11-restart-recovery.compiler-diagnostic.json
- 公开上游读取 ref：64b1ba0c1ee40c3da4d1d11d03ed353cccffdf2e
- workflow-compiler.mjs Git blob：81ba79a14895072643ea680b6eb304c45b0b8e29

脚本在用户机器上重新核对这两个 Git blob 指纹。与所读源码或候选不一致时拒绝自动运行，不提供强制覆盖选项。
所查上游 ref 的同一个调用点仍未物化 points；这不等于所有分支或未来版本都没有修复。

## 缺陷与边界

readableAutomaticCandidateSet 内部的 rawCandidates 元素形如 `{ family, via }`。
它映射生成的另一组 candidates 才含有 `points`，并且随后会被可行性过滤。
readableAutomaticVia 的失败诊断分支却把 rawCandidates 直接交给
classifyFailedAutomaticCandidatePins。后者解构 points，调用 candidateLabelRect，
最终 workflowEdgeLabelPoint 读取 points.length，触发 TypeError。

**这是内部调用参数形状不一致，不是用户 JSON 缺少 points。**
不要给 workflow schema 添加私有 points 字段；不要用 `points || []`、
`if (!points) continue`、吞异常或禁用校验来掩盖错误。

本包在调用前，以 `[start, ...candidate.via, end]` 生成并规范化 points。
保留全部原始候选，不只传过滤后“可行的 candidates”——否则失败候选没有了，
诊断又会遗漏错误。主路由求解、候选筛选、约束和严重级别均不被修改。

这修复的是诊断参数交接，不保证所有内部缺陷都消失，也不保证图布局正确。
新的显式几何冲突必须按实际坐标处理，不能因为补丁恢复了结构化回执就标 complete。

## 安全运行

Python 3.9+ 与 Node.js；Python 无第三方依赖；脚本不联网、不升级或安装任何软件。
`--repo` 为本地项目根目录。输出目录必须是新目录，已有目录不会被覆盖。

```bash
python3 /实际解压目录/archify11_diagnostic_repair/diagnose.py \
  --repo /Users/xavier/AgentFlow-Hub \
  --out /Users/xavier/AgentFlow-Hub/docs/architecture/history/11-diagnostic-handoff-run1
```

脚本顺序：
1. 核对候选与 compiler 的指纹及唯一调用点。
2. 保存当前候选快照；使用未修改的已安装 CLI 复现当前结果。
3. 将整个 Skill 拷贝到临时目录（不保留指向原目录的符号链接），仅修改副本的一个调用点。
4. 用副本 CLI 对同一候选执行 showcase validate，原始 stdout/stderr、退出码和可解析 JSON 分开保存。
5. 渲染已成功时补充副本 --layout-json，便于后续布局排障。
6. 清理临时副本，核对原 compiler 和候选字节未变化。

新输出包括：
- original.stdout.txt / original.stderr.txt / original.receipt.json（可解析时）
- temporary-copy.stdout.txt / temporary-copy.stderr.txt / temporary-copy.receipt.json（可解析时）
- 可用时的 temporary-copy-layout.*
- 两组 command.json（保留退出码和超时事实）
- diagnostic-run.json（工具来源、字节指纹、真实状态、清理结果）

运行脚本自身 exit 0 **仅表示诊断操作完成**。可能同时存在原版 exit 1、副本 exit 1：
例如原版内部崩溃而副本恢复了明确的几何冲突，诊断操作就已经完成，图仍然失败。

原版/副本结果必须区分，不能把副本成功填写为“原版验收通过”。
不会生成正式 HTML；10/12 及其他图保持不动；不会提交推送。

## 之后交给 Codex 的任务

先读取新的 diagnostic-run.json 及完整原版、副本回执。若副本仍 internal/unclassified，
继续拿完整堆栈分析，不猜测路由；若恢复了具体布局诊断，才开始修图。

参考历史可见：
- 首轮 rr-ready-edge / rr-scan-fail 共用纵向通道。
- ready 改 straight 后，rr-disabled-clear / rr-scan-fail 共用 y312 的水平通道。
- 为 rr-disabled-clear 加 channelY=322 后触发当前内部诊断崩溃。

因此不能把“一个内部错误”当作“只剩一个几何错误”，也不能按同样的计数
解释修复是否改善。当前仍 incomplete，阻塞点应注明 compiler diagnostic crash。

在恢复具体诊断后，可协调修改相关连线，不必固守 channelY=322。
必须保留业务语义与证据；明确类型、同步/异步、恢复只是终态收尾，不续跑模型/工具。
不要为过图删掉边或 semanticChecks。若发现语义证据不一致，单独报告，不能用几何修复掩盖。

最终必须重新使用未修改的已安装原版 CLI 对最终候选 validate。
原版通过后再 deliver → visual-check → 真实视觉复核，分别记录实际状态。
如果只有副本能通过，图仍是工具版本阻塞；需要单独评估正式修复/升级，
不能无授权更改已安装 Skill，也不能交付后宣称原版通过。

不开始图 12，不提交推送。
