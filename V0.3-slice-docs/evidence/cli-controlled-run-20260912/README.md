# V0.3-A CLI 受控关联报告

这是 CONTROLLED_CONTRACT HTTP fixture + 真实 CLI 本地文件证据，无质量评分。Agent/owner/config/task ID、模型名及 effective hash 均为 fixture 身份。其中 TASK_RESTART_INTERRUPTED 是受控 HTTP 返回值，不是本次实际中断 Java 进程产生的记录。本目录不证明 PostgreSQL 持久化、真实 provider、真实恢复或回答质量。

完整计划为 3 例：成功、明确准入拒绝（无 task ID）、中断失败。两例关联 fixture task，三例各发送一次原始 POST；随后两次 resume 新增 POST 均为 0。N_planned=3，N_linked=2，N_terminal=2，N_admissionRejected=1，N_incomplete=0，N_scored=0。

- [机器报告](report.json) 与 [可读报告](report.md)
- [验收检查与原请求计数](acceptance.json)
- [完整不可变计划](manifest.json) 与 [追加 journal](journal.jsonl)
- artifacts/ 保存两份脱敏 Task/Trace fixture 响应，hash 由 journal 核对。

从仓库根目录重跑（必须使用全新目录）：

```sh
python3 scripts/evaluation-controlled-acceptance.py --output out/cli-controlled-association-new
```

该脚本只复用 scripts/test_evaluation.py 的 HTTP fixture，不增加 Evaluation CLI 子命令。临时监听地址和鉴权值仅在内存使用，不写入本目录。Git 不保存目录的 0700 权限；检出后的文件可直接阅读，若使用 CLI 离线 report，先恢复运行目录及 artifacts/ 的 0700 权限、文件的 0600 权限。
