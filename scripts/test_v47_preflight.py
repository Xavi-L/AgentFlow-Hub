"""Offline policy regression: no subprocesses, provider requests, or credentials required."""
import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location('v47_preflight', Path(__file__).with_name('v47-preflight.py'))
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)

MODES = (
    ('AGENTFLOW_TASK_DECISION_JSON_SCHEMA_ENABLED', 'AGENTFLOW_AGENT_JSON_SCHEMA_MODELS'),
    ('AGENTFLOW_TASK_DECISION_JSON_OBJECT_ENABLED', 'AGENTFLOW_AGENT_JSON_OBJECT_MODELS'),
    ('AGENTFLOW_TASK_PROVIDER_THINKING_DISABLED', 'AGENTFLOW_AGENT_THINKING_DISABLED_MODELS'),
)


class ModelCapabilityPreflightTest(unittest.TestCase):
    def test_legacy_disabled_modes_need_no_model_capability_grant(self):
        checks = preflight.model_capability_checks({'OPENAI_CHAT_MODEL': 'test-model'})
        self.assertEqual(len(checks), 3)
        self.assertTrue(all(check['passed'] for check in checks))

    def test_each_enabled_mode_rejects_missing_or_non_exact_model_membership(self):
        for flag, allowlist in MODES:
            for declared in ('', 'different-model', 'test-model-extra', 'TEST-MODEL', '*'):
                with self.subTest(flag=flag, declared=declared):
                    checks = preflight.model_capability_checks({
                        'OPENAI_CHAT_MODEL': 'test-model', flag: 'true', allowlist: declared,
                    })
                    self.assertEqual(sum(not check['passed'] for check in checks), 1)
                    self.assertIn(allowlist, next(check['detail'] for check in checks if not check['passed']))

    def test_comma_separated_model_entries_are_trimmed_without_cross_granting_modes(self):
        for flag, allowlist in MODES:
            with self.subTest(flag=flag):
                env = {'OPENAI_CHAT_MODEL': 'test-model', flag: ' TRUE ',
                       allowlist: 'other-model, test-model , , third-model'}
                self.assertTrue(all(check['passed'] for check in preflight.model_capability_checks(env)))
                other_flag, _ = next(mode for mode in MODES if mode[0] != flag)
                env[other_flag] = 'true'
                self.assertEqual(sum(not check['passed'] for check in preflight.model_capability_checks(env)), 1)


if __name__ == '__main__':
    unittest.main()
