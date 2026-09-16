# 图 10B：协调布线修复候选

## 状态与范围

本包只针对 `docs/architecture/10-settlement.candidate.workflow.json`。
这是**新的布局候选**，不是正式图，也不是原版 Archify 已验收的修复。

已执行：基线字节核对、语义字段不变检查、有限的独立几何检查。
未执行：原版 Archify validate / deliver、真实浏览器检查和 Archify 截图视觉复核。
没有向远程仓库写入内容；没有修改业务代码、Skill、10A 或历史成功/失败记录。

被检查的远程 HEAD 为 `7e1e8bb7c2ae55b44726b410fb2546cb64d4a401`。
候选原始 Git blob 为 `5981848bf77f3902d67adb5879230b98e231c74a`。
文档中的施工基线 `0320892...` 是原作者本地历史基线，不能与当前远程 HEAD 混淆。

## 为什么不能只改 sp-retry

最初的 `history/10b-before-retry-straight/10-settlement.candidate.validation.json`
已经列出四组关系冲突：

- sp-confirm 与 sp-retry 的交叉。
- sp-again 与 sp-unconfirmed 的共享通道。
- sp-existing 与 sp-uncertain 的共享通道。
- sp-retry 与 sp-uncertain 的共享通道。

后来尝试 straight、再尝试 left/left，会更早在 route feasibility 失败。
诊断从四个变成一个不表示前三个问题已修复；回执处在不同检查阶段。
`sp-retry` 是 edge ID（classify -> backoff），不是有界退避节点 ID。

本包不进一步推断旧候选的具体失败 predicate，也不把 supportedFixes=[] 解释为全局无解。
改用一个完整、明确且相互协调的路由方案：保留相同节点位置，给不同业务分支指定分开的通道。

## 不变量

完整保留：8 个节点、12 条关系、3 个泳道、3 张 cards、所有文案、mainPath、semanticChecks。
节点 lane/col/type/width/height 完全不变。没有缩字体、删除标签、弱化检查或裁切 viewBox。
只修改 edges 的 renderer 支持字段：route、fromSide、toSide、via、labelSegment、labelDy。
这是一组整体布局变更，**不要拆开只应用其中几条边**。

## 路由安排

- 主路径 sp-begin / sp-live / sp-commit：right -> left straight。
- sp-unconfirmed / sp-fail：left -> right straight；仅改变几何，不改变方向或错误分类。
- sp-read-fail / sp-uncertain / sp-retry：bottom -> top straight，labelDy=39，将标签放到两节点间隙。
- sp-existing：top -> top，走上方 y=32 的已终态分支。
- sp-confirm：从 readback 右侧到 done 下侧，用一个直角，避开重试区。
- sp-again：从 backoff 底部出发，经 y=416 和左外侧 x=20 返回 read 顶部；这是数据库重试控制边，**不是业务执行重试**。
- sp-interrupt：从 backoff 左侧出发，经 x=34、y=310 进入 degrade 底部；与正常退避返回路径分别使用端口和外侧通道。

主要派生坐标（独立计算，不是原版 --layout-json）：

```
columns = [112, 310.8, 495.2, 679.6, 799.6, 919.6]
laneHeight = 106
laneGap = 20
nodes top/cy/bottom:
  save:    86 / 120 / 154
  failure: 212 / 246 / 280
  retry:   338 / 372 / 406
viewBox = [994, 534]
```

这些绝对 via 与当前节点、文案、编译器规则绑定。若以后改变节点宽度、lane、col、label 或编译器，
必须重新生成布局并完整校验，不能无条件复用这些坐标。

sp-existing 与 sp-again 在 read 顶部保留一个 24px 共同端点段。
独立检查明确报告它，没有通过更改节点 ID 或 role 规避检测。
原版 showcase 的非共享端点 crossing/corridor 检查与“图中完全没有任何共线”不同。
浏览器视觉复核应检查这个共同端点是否能凭箭头、虚实线和标签分辨；若不清楚，应如实标未通过。

## 应用

在仓库根目录执行，路径替换为本包实际解压位置：

```bash
python3 /path/to/archify10b_repair/apply_repair.py .
python3 /path/to/archify10b_repair/apply_repair.py . --apply
```

脚本默认 dry-run。写入前核对原候选 SHA-256、修复候选 SHA-256 与语义字段不变；
若已安装的 workflow compiler 文件存在，还必须匹配所建模的 Git blob
`81ba79a14895072643ea680b6eb304c45b0b8e29`。不匹配时拒绝覆盖，不提供强制选项。
脚本使用 Python 3.9+ 标准库；不需要 Shapely。独立分析器是可选附件，需要 Shapely，
不是应用或原版验收的先决条件。

应用后脚本只替换 candidate。旧 candidate 和相邻10B JSON回执会先复制到唯一 history 子目录。
不会提交、推送，不会操作10A/正式HTML/Atlas。
也可以审阅 `.patch` 后人工应用，但**不要与脚本重复应用**。

## 原版验收

```bash
node .agents/skills/archify/bin/archify.mjs validate workflow \
  docs/architecture/10-settlement.candidate.workflow.json \
  --quality showcase --json

node .agents/skills/archify/bin/archify.mjs validate workflow \
  docs/architecture/10-settlement.candidate.workflow.json \
  --quality showcase --layout-json
```

记录两个命令各自真实退出码。诊断时可执行 --layout-json；其成功不替代整体验收。
不要用 `tee` 丢失原命令退出码；若使用管道，设置 pipefail 或明确读取原命令状态。

先比较原版测量与上面的预期坐标。若不一致，查清是本地版本、几何规则、图例反馈还是输入变更，
不要手工改回执或只按独立报告宣称通过。

通过要求：9/9 artifact checks，composition 0 errors / 0 warnings，所有语义检查保留。
之后才将候选原样复制到正式 `10-settlement.workflow.json`，执行当前 Skill 规定的 deliver，
成功后对本轮新 artifact 执行 visual-check 与截图视觉复核。
四个桌面视口：1440x900、1600x1000、1920x1080、2048x1320；主题范围遵循已安装 Skill。

原版 validate 若失败，保留完整诊断及退出码，不修改业务代码或 Skill。
原版 deliver 若失败，不读取旧 HTML 当成本轮新图；绑定输入/输出 hash 与本轮命令结果。
最终分开记录 artifact validation、browser evidence、visual review 状态。
10A已通过的原产物/回执保持不变；整体10只有在10B各层也通过后才能complete。
不开始图11。不提交/推送，除非用户另行授权。

## 独立检查覆盖与限制

独立模型仅支持本候选的固定节点、显式 straight/via、端口及标签控制；不是通用自动布线器。
检查：矩形/线段相交、端口垂直进入、端点/内部最小段长、节点/标签与路径间距、
非共享端点交叉与共享通道、泳道标题避让、坐标范围、保守的图例区域、预计文字缩放。

结果：0项独立问题；标签与其他边最小间距24px；最短端点段10px、内部段24px；
预计最小节点字号7.4849px（930/994 * 8）。这些是模型计算，不是浏览器实测。
完整证据见 `10-settlement.independent-check.json`。

未覆盖原版 schema 验证、完整 renderer 实现、原版最终SVG/HTML检查、真实图例精确排布、
HTML响应式布局、浏览器交互或视觉质量。本包不替代以上任何验收。

## 源码依据

- AgentFlow-Hub: docs/architecture/10-settlement.candidate.workflow.json
- AgentFlow-Hub: docs/architecture/10-settlement.acceptance.json 及其 history 目录
- Archify: archify/renderers/workflow/workflow-compiler.mjs（Git blob 81ba79a14895072643ea680b6eb304c45b0b8e29）
- Archify: archify/renderers/shared/geometry.mjs、legend.mjs、text-fit.mjs、desktop-readability.mjs

只读取源码并独立计算；没有执行、安装或改写用户已安装的 Skill。
