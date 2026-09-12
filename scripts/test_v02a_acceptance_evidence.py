#!/usr/bin/env python3
"""Focused regression for evidence bookkeeping; it does not replace any A01-A17 case."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

MODULE = importlib.util.spec_from_file_location('v02a_acceptance', Path(__file__).with_name('v02a-restart-acceptance.py'))
ACCEPTANCE = importlib.util.module_from_spec(MODULE)
MODULE.loader.exec_module(ACCEPTANCE)


class CaseEvidenceTest(unittest.TestCase):
    def test_default_legacy_baseline_resolves_to_the_verified_immutable_pre_lock_commit(self):
        expected = 'fb6a325a9d1906632ca81eee1d2eb10f86359214'
        self.assertEqual(ACCEPTANCE.LEGACY_BASELINE, expected)
        self.assertEqual(ACCEPTANCE.resolve_legacy_baseline(ACCEPTANCE.LEGACY_BASELINE), expected)

    def test_case_names_remain_labels_and_big_task_ids_remain_snapshot_keys(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = ACCEPTANCE.Acceptance.__new__(ACCEPTANCE.Acceptance)
            run.run, run.cases, run.failures = Path(temporary), {}, []
            run.tasks = {'A01': '620000000000000001', 'A01_VARIANT': '620000000000000002', 'A02': '620000000000000003'}
            class External:
                def counts(self, case):
                    return {'DECISION': 0, 'case': case}
            run.external = External()
            seen = []
            def snapshot(task_id):
                self.assertTrue(task_id.isdigit())
                seen.append(task_id)
                return {'agent_task': [{'id': task_id}]}
            run.snapshot = snapshot
            run.runner_count = lambda task_id: int(task_id.isdigit())
            with contextlib.redirect_stdout(io.StringIO()):
                run.case('A01', lambda: None)
            result = json.loads((Path(temporary) / 'cases/A01-summary.json').read_text())
            self.assertEqual(set(result['tasks']), {'A01', 'A01_VARIANT'})
            self.assertEqual(set(result['snapshots']), {'620000000000000001', '620000000000000002'})
            self.assertEqual(set(seen), set(result['snapshots']))
            self.assertEqual(run.cases['A01']['status'], 'PASSED')


if __name__ == '__main__':
    unittest.main()
