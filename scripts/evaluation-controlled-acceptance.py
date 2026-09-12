#!/usr/bin/env python3
"""Retain a V0.3-A association report from the existing controlled HTTP test fixture.

This is an acceptance harness, not an Evaluation CLI subcommand. No Java task,
PostgreSQL row, actual process interruption or external provider is produced here.
"""
import argparse
from collections import Counter
import hashlib
from pathlib import Path
import sys

from test_evaluation import E, Service, TOKEN


def accept(output):
    fixture_source = Path(__file__).with_name("test_evaluation.py")
    source_hash = hashlib.sha256(fixture_source.read_bytes()).hexdigest()
    suite = {
        "agentId": "1", "configVersionId": "20", "suiteMode": "CONTROLLED_CONTRACT",
        "dataset": {"datasetId": "cli-controlled-association-fixture", "version": "v1"},
        "cases": [
            {"caseId": "fixture-success", "userInput": "success", "expected": {"fixtureTaskStatus": "COMPLETED"}},
            {"caseId": "fixture-admission-rejected", "userInput": "reject", "expected": {"fixtureAdmissionError": "AGENT_DISABLED"}},
            {"caseId": "fixture-restart-interrupted", "userInput": "interrupt", "expected": {
                "fixtureTaskStatus": "FAILED", "fixtureErrorCode": "TASK_RESTART_INTERRUPTED"}},
        ],
        "budget": {"maxPlannedTasks": 3, "maxTotalTokens": 1000,
                   "observationTimeoutSeconds": 2, "requestTimeoutSeconds": 1,
                   "pollIntervalSeconds": E.Decimal("0.02"), "maxNetworkChecks": 3},
        "materials": {"businessFixture": {"version": "evaluation-http-fixture-v1",
                         "source": "scripts/test_evaluation.py:Service", "sourceSha256": source_hash,
                         "initializationMethod": "Ephemeral in-memory HTTP fixture; no database or external provider"},
                      "corpusManifest": None},
        "environment": {"applicationRevision": "development"},
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    fixture = Service()
    try:
        manifest = E.create_run(suite, output, fixture.client)
        assert len(manifest["cases"]) == 3 and len(fixture.posts) == 0
        first_report = E.execute(output, fixture.client)
        first_posts = len(fixture.posts)
        assert first_posts == 3
        assert first_report["executionComplete"] and first_report["N_planned"] == 3
        assert first_report["N_linked"] == first_report["N_terminal"] == 2
        assert first_report["N_admissionRejected"] == 1 and first_report["N_incomplete"] == 0
        assert first_report["N_scored"] == 0 and first_report["qualityEvaluation"] == "NOT_EVALUATED"
        assert first_report["taskStatusDistribution"] == {"COMPLETED": 1, "FAILED": 1}
        assert first_report["cases"][1]["taskId"] is None
        assert first_report["cases"][2]["errorCode"] == "TASK_RESTART_INTERRUPTED"
        assert first_report["cases"][2]["usage"]["tokenUsageQuality"] == "UNKNOWN"
        for _ in range(2):
            assert E.execute(output, fixture.client) == first_report
            assert len(fixture.posts) == first_posts
        assert E.report(output) == first_report
        cases_by_key = {case["clientRequestId"]: case for case in manifest["cases"]}
        receipts = []
        for ordinal, (key, body) in enumerate(fixture.posts, 1):
            case = cases_by_key[key]
            assert body == case["request"]["body"]
            receipts.append({"ordinal": ordinal, "caseExecutionId": case["caseExecutionId"],
                             "clientRequestId": key, "originalRequestHash": E.content_hash(case["request"]),
                             "fixtureTaskId": fixture.tasks.get(key, {}).get("taskId")})
        assert list(Counter(key for key, _ in fixture.posts).values()) == [1, 1, 1]
        evidence = {
            "schemaVersion": "agent-eval-controlled-acceptance-v1", "status": "PASSED",
            "suiteMode": "CONTROLLED_CONTRACT", "evalRunId": manifest["evalRunId"],
            "evidenceClass": "CONTROLLED_HTTP_FIXTURE_WITH_REAL_CLI_FILES",
            "scope": "Three-case association and repeated resume; no quality evaluation",
            "fixtureSource": "scripts/test_evaluation.py:Service", "fixtureSourceSha256": source_hash,
            "fixtureTaskIds": sorted(task["taskId"] for task in fixture.tasks.values()),
            "fixtureIdentityNotice": "Agent, owner, version, task, model and effective hash values are fixture identities",
            "realJavaExecution": False, "realPostgreSQL": False, "realProvider": False,
            "actualProcessInterruption": False, "qualityScored": False,
            "interruptionNotice": "TASK_RESTART_INTERRUPTED is a synthetic HTTP response in this report",
            "plannedCasesBeforeFirstTaskPost": 3, "originalTaskPostCount": first_posts,
            "repeatedResumeCount": 2, "repeatedResumeAdditionalTaskPosts": len(fixture.posts) - first_posts,
            "totalTaskPostCount": len(fixture.posts), "distinctFixtureTasks": len(fixture.tasks),
            "checks": ["full_plan_before_post", "original_request_and_key_preserved", "one_post_per_case",
                       "admission_without_task_kept", "failed_interrupted_fixture_kept", "fixed_three_case_denominator",
                       "no_scoring", "two_resumes_create_no_additional_posts", "offline_report_reproducible",
                       "manifest_journal_artifact_integrity", "no_fixture_credential_or_connection_address_in_files"],
            "requestReceipts": receipts,
        }
        E.atomic_json(output / "acceptance.json", evidence)
        E.atomic_bytes(output / ".gitignore", b".lock\n.*.tmp\n")
        E.atomic_bytes(output / "README.md", (
            "# V0.3-A CLI 受控关联报告\n\n"
            "这是 CONTROLLED_CONTRACT HTTP fixture + 真实 CLI 本地文件证据，无质量评分。"
            "Agent/owner/config/task ID、模型名及 effective hash 均为 fixture 身份。"
            "其中 TASK_RESTART_INTERRUPTED 是受控 HTTP 返回值，不是本次实际中断 Java 进程产生的记录。"
            "本目录不证明 PostgreSQL 持久化、真实 provider、真实恢复或回答质量。\n\n"
            "完整计划为 3 例：成功、明确准入拒绝（无 task ID）、中断失败。"
            "两例关联 fixture task，三例各发送一次原始 POST；随后两次 resume 新增 POST 均为 0。"
            "N_planned=3，N_linked=2，N_terminal=2，N_admissionRejected=1，N_incomplete=0，N_scored=0。\n\n"
            "- [机器报告](report.json) 与 [可读报告](report.md)\n"
            "- [验收检查与原请求计数](acceptance.json)\n"
            "- [完整不可变计划](manifest.json) 与 [追加 journal](journal.jsonl)\n"
            "- artifacts/ 保存两份脱敏 Task/Trace fixture 响应，hash 由 journal 核对。\n\n"
            "从仓库根目录重跑（必须使用全新目录）：\n\n"
            "```sh\npython3 scripts/evaluation-controlled-acceptance.py --output out/cli-controlled-association-new\n```\n\n"
            "该脚本只复用 scripts/test_evaluation.py 的 HTTP fixture，不增加 Evaluation CLI 子命令。"
            "临时监听地址和鉴权值仅在内存使用，不写入本目录。"
            "Git 不保存目录的 0700 权限；检出后的文件可直接阅读，若使用 CLI 离线 report，"
            "先恢复运行目录及 artifacts/ 的 0700 权限、文件的 0600 权限。\n"
        ).encode("utf-8"))
        forbidden = (TOKEN, fixture.base, "127.0.0.1", "http://", "https://", "/private/tmp", "/tmp/",
                     "fixture-secret", "must-not-land", "exception-fixture-value", "Bearer " + TOKEN)
        for path in output.rglob("*"):
            if path.is_file():
                contents = path.read_text()
                assert not any(value in contents for value in forbidden), "UNSAFE_RETAINED_EVIDENCE"
        assert E.report(output) == first_report
        return {"status": "PASSED", "suiteMode": "CONTROLLED_CONTRACT", "N_planned": 3,
                "N_linked": 2, "N_admissionRejected": 1, "N_scored": 0,
                "repeatedResumeAdditionalTaskPosts": 0}
    finally:
        fixture.close()


def main():
    parser = E.SafeParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    try:
        args = parser.parse_args()
        print(E.canonical_json(accept(args.output)))
        return 0
    except Exception:
        print("Controlled acceptance failed; existing evidence was not overwritten.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
