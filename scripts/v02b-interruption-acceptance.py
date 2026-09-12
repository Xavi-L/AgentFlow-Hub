#!/usr/bin/env python3
"""V0.2-B controlled noncooperative HTTP + real PostgreSQL + B09 JVM SIGKILL/restart.

B03 acquire-to-entry scheduler races and nested ownership are additionally covered by
TaskExternalCallDeadlineTest; this process harness does not claim to schedule those races.
"""
from __future__ import annotations
import argparse
import datetime as dt
import http.server
import importlib.util
import json
import os
from pathlib import Path
import threading
import time
import traceback
import urllib.request

SPEC = importlib.util.spec_from_file_location('v02a_acceptance', Path(__file__).with_name('v02a-restart-acceptance.py'))
A = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(A)
now, save, wait_for = A.now, A.save, A.wait_for


class External(A.External):
    """Independent durable receipts survive tested JVM death; no automatic request retry."""
    def __init__(self, directory, port):
        self.directory, self.events, self.lock = directory, [], threading.Lock()
        external = self
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                case = request['case']
                boundary = 'tool' if self.path == '/tool' else request['type']
                with external.lock:
                    ordinal = 1 + sum(e['case'] == case and e['boundary'] == boundary for e in external.events)
                    event = {**request, 'boundary': boundary, 'ordinal': ordinal, 'at': now()}
                    external.events.append(event)
                    with (directory / 'external-receipts.jsonl').open('a') as out:
                        out.write(json.dumps(event) + '\n'); out.flush(); os.fsync(out.fileno())
                gate = directory / 'external-gates' / f'{case}-{boundary}'
                if Path(str(gate) + '.block').exists():
                    save(Path(str(gate) + '.reached'), event)
                    wait_for(lambda: Path(str(gate) + '.allow').exists(), 'external response gate', 3600)
                if self.path == '/tool':
                    answer = {'orderNo': case, 'status': 'SUCCESS', 'controlled': True}
                else:
                    if boundary == 'FINAL_GENERATION':
                        content = 'Controlled B answer from one observed execution.'
                    elif 'TOOL' in case and boundary == 'DECISION' and ordinal == 1:
                        content = json.dumps({'type': 'CALL_TOOL', 'toolCode': 'order_query', 'arguments': {'orderNo': case}, 'reason': 'Read controlled order facts'})
                    else:
                        content = json.dumps({'type': 'FINISH', 'answerPlan': 'Explain local recorded facts'})
                    answer = {'content': content, 'requestId': f'{case}:{boundary}:{ordinal}'}
                encoded = json.dumps(answer).encode()
                try:
                    self.send_response(200); self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(encoded))); self.end_headers(); self.wfile.write(encoded)
                except (BrokenPipeError, ConnectionResetError):
                    pass
            def log_message(self, *_args):
                pass
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()


class Acceptance(A.Acceptance):
    fixture_main = 'com.agentflow.acceptance.V02BInterruptionFixture'
    external_factory = External

    def __init__(self, args):
        super().__init__(args)
        save(self.run / 'run-started.json', {'at': now(), 'scope': 'V0.2-B B01-B10 process/PG acceptance'})
        (self.run / 'executed-harness.py').write_text(Path(__file__).read_text())

    def boot(self, *args, **kwargs):
        kwargs['extra'] = {'agentflow.task.execution.max-concurrent-external-calls': '1', **kwargs.get('extra', {})}
        return super().boot(*args, **kwargs)

    def settings(self, total=30, model=30):
        self.sql(f'UPDATE agent_app SET timeout_seconds={int(total)},model_call_timeout_seconds={int(model)} WHERE id={A.AGENT}')

    def b_command(self, action, **data):
        name = f'{time.monotonic_ns()}.json'
        save(self.run / 'b-commands' / name, {'action': action, **data})
        result = self.run / 'b-commands' / (name + '.result')
        wait_for(result.exists, 'B local test command')
        return json.loads(result.read_text())

    def attempts(self, task):
        return sorted((json.loads(path.read_text()) for path in (self.run / 'b-attempts').glob(f'{task}-*.entered')), key=lambda r: r['attempt'])

    def settled(self, task, method='settle', expected=True):
        path = self.run / 'b-settled' / f'{task}-{method}.returned'
        wait_for(path.exists, 'bounded settlement method returned')
        result = json.loads(path.read_text())
        assert result['result'] is expected, result
        return result

    def terminal(self, task, status, error=None):
        row = wait_for(lambda: (value if (value := self.row(task))['status'] in A.TERMINALS else None), 'task terminal ' + task)
        assert row['status'] == status, row
        assert row['error_code'] == error, row
        snapshot = self.snapshot(task)
        terminal_events = [event for event in snapshot['agent_task_event'] if event['event_type'] in ('TASK_COMPLETED', 'TASK_FAILED', 'TASK_CANCELLED', 'TASK_TIMED_OUT')]
        assert len(terminal_events) == 1, terminal_events
        assert [e['sequence_no'] for e in snapshot['agent_task_event']] == list(range(1, row['last_event_sequence'] + 1))
        if status != 'COMPLETED':
            assert row['final_answer'] is None and row['citations'] == []
            assert not any(e['event_type'] in ('ANSWER_CHUNK', 'TASK_COMPLETED') for e in snapshot['agent_task_event'])
        return row

    def observe(self, task):
        status, detail = self.api('GET', f'/tasks/{task}')
        assert status == 200
        status, trace = self.api('GET', f'/tasks/{task}/trace')
        assert status == 200
        trace_task = trace['data'].get('task', trace['data'])
        assert trace_task['status'] == detail['data']['status']
        request = urllib.request.Request(self.base + f'/api/v1/tasks/{task}/events', headers={'Authorization': 'Bearer ' + self.token})
        with urllib.request.urlopen(request, timeout=8) as response:
            sse = response.read().decode()
        expected = 'TASK_' + detail['data']['status']
        assert expected in sse, (expected, sse)
        return {'detail': detail['data'], 'traceTask': trace_task, 'sse': sse}

    def release(self, case, boundary):
        self.control(f'external-gates/{case}-{boundary}.allow')
        wait_for(lambda: (self.run / 'b-boundaries' / f'{case}-{boundary}.exited').exists(), 'actual gateway body exit')
        wait_for(lambda: self.b_command('state')['activeWorkCount'] == 0, 'permit returned after actual body exit')

    def cancel(self, task):
        status, body = self.api('POST', f'/tasks/{task}/cancel')
        assert status == 200, body
        return self.terminal(task, 'CANCELLED')

    def install_failure(self, case, count=1, sqlstate='40001', event=False):
        self.sql('CREATE SEQUENCE v02b_fault_attempts')
        table = 'agent_task_event' if event else 'agent_task'
        match = f"NEW.task_id=(SELECT id FROM agent_task WHERE user_input='{case}') AND NEW.event_type='TASK_COMPLETED'" if event else f"NEW.user_input='{case}' AND OLD.status='RUNNING' AND NEW.status IN ('COMPLETED','FAILED','CANCELLED','TIMED_OUT') AND NEW.recovery_metadata IS NULL"
        self.sql(f"""CREATE FUNCTION v02b_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
          IF {match} THEN
            IF nextval('v02b_fault_attempts') <= {int(count)} THEN RAISE EXCEPTION USING ERRCODE='{sqlstate}', MESSAGE='V02B controlled terminal persistence fault'; END IF;
          END IF; RETURN NEW; END $$;
          CREATE TRIGGER v02b_failure BEFORE {'INSERT' if event else 'UPDATE'} ON {table} FOR EACH ROW EXECUTE FUNCTION v02b_fail()""")
        return table

    def remove_failure(self, table):
        self.sql(f'DROP TRIGGER v02b_failure ON {table}; DROP FUNCTION v02b_fail(); DROP SEQUENCE v02b_fault_attempts')

    def gates(self, case, mode='RETRY_GATE'):
        self.control('b-settlement/' + case, mode)

    def await_retry(self, task):
        wait_for(lambda: (self.run / 'gates' / f'{task}-retry.reached').exists(), 'second settlement after first transient DB failure')

    def normal_counts(self, case):
        counts = self.external.counts(case)
        assert counts == {'DECISION': 1, 'FINAL_GENERATION': 1, 'tool': 0, 'EMBEDDING': 1, 'VECTOR': 1}, counts
        return counts

    def run_matrix(self):
        self.settings()
        def b01():
            evidence = []
            for case, boundary in [('B01_LLM', 'DECISION'), ('B01_TOOL', 'tool'), ('B01_EMBEDDING', 'EMBEDDING'), ('B01_VECTOR', 'VECTOR')]:
                task = self.create(case, '', boundary)
                self.cancel(task)
                before = self.snapshot(task)
                counts = self.external.counts(case)
                assert self.b_command('state')['activeWorkCount'] == 1
                self.release(case, boundary)
                assert self.snapshot(task) == before
                assert self.external.counts(case) == counts
                evidence.append({'case': case, 'taskId': task, 'counts': counts, 'lateResultSnapshotUnchanged': True})
            save(self.run / 'cases/B01.json', evidence)
        self.case('B01', b01)

        def b02():
            held = self.create('B02_HELD', '', 'DECISION')
            self.cancel(held)
            waiting = self.create('B02_WAIT_CANCEL', '')
            wait_for(lambda: self.row(waiting)['status'] == 'RUNNING', 'permit waiter RUNNING')
            self.cancel(waiting)
            self.settings(total=1, model=10)
            timed = self.create('B02_WAIT_TIMEOUT', '')
            self.terminal(timed, 'TIMED_OUT')
            state = self.b_command('state')
            assert state['capacity'] == 1 and state['activeWorkCount'] == 1 and state['availablePermits'] == 0, state
            assert sum(self.external.counts('B02_WAIT_CANCEL').values()) == 0
            assert sum(self.external.counts('B02_WAIT_TIMEOUT').values()) == 0
            self.release('B02_HELD', 'DECISION')
            self.settings()
            success = self.create('B02_RECOVERED', '')
            self.terminal(success, 'COMPLETED')
            save(self.run / 'cases/B02.json', {'held': held, 'cancelledWaiter': waiting, 'timedOutWaiter': timed, 'stateWhileHeld': state, 'reusedPermitTask': success})
        self.case('B02', b02)

        def b03():
            case = 'B03_RETURN_CANCEL'
            self.gates(case, 'FIRST_GATE')
            task = self.create(case, '')
            wait_for(lambda: (self.run / 'gates' / f'{task}-first-settlement.reached').exists(), 'normal execution result observed')
            counts = self.normal_counts(case)
            status, _ = self.api('POST', f'/tasks/{task}/cancel')
            assert status == 200
            self.control(f'gates/{task}-first-settlement.allow')
            row = self.terminal(task, 'CANCELLED')
            assert row['total_tokens'] == 30 and row['token_usage_quality'] == 'EXACT', row
            assert self.external.counts(case) == counts
            save(self.run / 'cases/B03.json', {'taskId': task, 'usagePreserved': 30, 'processCoverage': ['waiting cancellation B02', 'return boundary persistent cancellation'],
                 'requiredCompanion': 'TaskExternalCallDeadlineTest acquire-before-entry races, exact once release, nested ownership; this harness alone is not that scheduler evidence'})
        self.case('B03', b03)

        def b04():
            evidence = []
            for case, total, model, expected, error in [('B04_MODEL', 15, 1, 'FAILED', 'AGENT_LLM_TIMEOUT'), ('B04_TASK', 1, 15, 'TIMED_OUT', None)]:
                self.settings(total, model)
                task = self.create(case, '', 'DECISION')
                row = self.terminal(task, expected, error)
                before = self.snapshot(task)
                self.release(case, 'DECISION')
                assert self.snapshot(task) == before
                evidence.append({'case': case, 'taskId': task, 'status': row['status'], 'errorCode': row['error_code']})
            self.settings()
            save(self.run / 'cases/B04.json', evidence)
        self.case('B04', b04)

        def b05():
            case = 'B05_TRANSIENT'
            table = self.install_failure(case)
            task = self.create(case, '')
            row = self.terminal(task, 'COMPLETED')
            attempts = self.attempts(task)
            assert len(attempts) == 2 and len({a['observedAt'] for a in attempts}) == 1
            assert row['total_tokens'] == 30
            self.normal_counts(case)
            save(self.run / 'cases/B05.json', {'taskId': task, 'attempts': attempts, 'sqlAttemptCount': self.sql('SELECT last_value FROM v02b_fault_attempts')})
            self.remove_failure(table)
        self.case('B05', b05)

        def b06():
            case = 'B06_COMMIT_LOST'
            self.gates(case, 'LOST_COMMIT_RESPONSE')
            task = self.create(case, '')
            self.terminal(task, 'COMPLETED')
            wait_for(lambda: (self.run / 'b-attempts' / f'{task}-1.committed').exists(), 'committed call before receipt loss')
            settled = self.settled(task)
            self.normal_counts(case)
            rollback_case = 'B06_EVENT_ROLLBACK'
            self.gates(rollback_case)
            table = self.install_failure(rollback_case, event=True)
            rolled = self.create(rollback_case, '')
            self.await_retry(rolled)
            before = self.snapshot(rolled)
            assert before['agent_task'][0]['status'] == 'RUNNING' and before['agent_task'][0]['final_answer'] is None
            assert not any(e['event_type'] in ('ANSWER_CHUNK', 'TASK_COMPLETED') for e in before['agent_task_event'])
            self.control(f'gates/{rolled}-retry.allow')
            self.terminal(rolled, 'COMPLETED')
            assert len(self.attempts(task)) == 1 and len(self.attempts(rolled)) == 2
            self.normal_counts(rollback_case)
            save(self.run / 'cases/B06.json', {'committedTask': task, 'commitAttempts': self.attempts(task), 'settlementReturned': settled, 'rollbackTask': rolled, 'rolledBackSnapshot': before, 'retryAttempts': self.attempts(rolled)})
            self.remove_failure(table)
        self.case('B06', b06)

        def b07():
            evidence = []
            for variant in ('DEADLINE', 'CANCEL', 'OTHER_TERMINAL'):
                case = 'B07_' + variant
                self.settings(total=2 if variant == 'DEADLINE' else 30)
                self.gates(case)
                table = self.install_failure(case)
                task = self.create(case, '')
                self.await_retry(task)
                if variant == 'DEADLINE':
                    deadline = dt.datetime.fromisoformat(self.row(task)['started_at']) + dt.timedelta(seconds=2)
                    wait_for(lambda: dt.datetime.now(dt.timezone.utc) > deadline, 'deadline after observed outcome', 5)
                elif variant == 'CANCEL':
                    status, _ = self.api('POST', f'/tasks/{task}/cancel')
                    assert status == 200
                else:
                    result = self.fixture_command('terminal', task, status='COMPLETED')
                    assert result['accepted'], result
                self.control(f'gates/{task}-retry.allow')
                row = self.terminal(task, 'CANCELLED' if variant == 'CANCEL' else 'COMPLETED')
                attempts = self.attempts(task)
                assert len(attempts) == 2 and len({a['observedAt'] for a in attempts}) == 1
                if variant != 'OTHER_TERMINAL':
                    assert row['total_tokens'] == 30
                    # PostgreSQL stores microseconds while the Java clock can observe nanoseconds.
                    assert abs((dt.datetime.fromisoformat(row['completed_at']) - dt.datetime.fromisoformat(attempts[0]['observedAt'])).total_seconds()) <= .000001
                else:
                    assert row['final_answer'] == 'Controlled fixture terminal preservation'
                self.normal_counts(case)
                evidence.append({'variant': variant, 'taskId': task, 'attempts': attempts, 'row': row})
                self.remove_failure(table)
            self.settings()
            save(self.run / 'cases/B07.json', evidence)
        self.case('B07', b07)

        def b08():
            evidence = []
            for variant, sqlstate, expected_attempts in [('PERSISTENT', '40001', 3), ('INVALID', '23514', 1)]:
                case = 'B08_' + variant
                queued_case = case + '_QUEUED'
                queued = self.create(queued_case, 'QUEUED')
                assert self.row(queued)['status'] == 'QUEUED'
                table = self.install_failure(case, count=100, sqlstate=sqlstate)
                task = self.create(case, '')
                state = wait_for(lambda: (s if not (s := self.b_command('state'))['admissionReady'] else None), 'settlement degradation')
                assert 'TASK_SETTLEMENT_PERSIST_FAILED' in state['diagnostic'], state
                assert state['taskExecutionHealthStatus'] == 'DOWN', state
                assert self.row(task)['status'] == 'RUNNING'
                attempts = self.attempts(task)
                assert len(attempts) == expected_attempts, attempts
                returned = self.settled(task, expected=False)
                self.control(f'gates/{queued}-queued.allow')
                queued_row = self.terminal(queued, 'FAILED', 'TASK_DISPATCH_REJECTED')
                rejection = self.settled(queued, method='rejectDispatch')
                assert queued_row['started_at'] is None and sum(self.external.counts(queued_case).values()) == 0
                self.assert_closed(task, case)
                self.normal_counts(case)
                log = (self.run / f'{self.proc.label}.log').read_text()
                assert 'TASK_SETTLEMENT_PERSIST_FAILED' in log and task in log
                evidence.append({'taskId': task, 'variant': variant, 'attempts': attempts, 'state': state,
                     'settlementReturned': returned, 'alreadyCommittedQueuedTask': queued,
                     'queuedRejectionReturned': rejection, 'queuedRejection': queued_row})
                self.remove_failure(table)
                self.kill(); self.boot(); self.recovered(task, 'RUNNING')
            save(self.run / 'cases/B08.json', evidence)
        self.case('B08', b08)

        def b09():
            case = 'B09_KILL_RETRY'
            self.gates(case)
            table = self.install_failure(case)
            task = self.create(case, '')
            self.await_retry(task)
            attempts = self.attempts(task)
            assert len(attempts) == 2
            before = self.snapshot(task)
            assert before['agent_task'][0]['status'] == 'RUNNING' and before['agent_task'][0]['final_answer'] is None
            assert len(before['llm_call_log']) == 2 and all(r['status'] == 'SUCCESS' for r in before['llm_call_log'])
            creator = self.proc.pid
            row = self.restart_check(task, case)
            assert row['status'] == 'FAILED' and row['error_code'] == 'TASK_RESTART_INTERRUPTED'
            assert row['recovery_metadata']['executionOutcome'] == 'UNKNOWN' and row['total_tokens'] == 30
            assert row['token_usage_quality'] == 'UNKNOWN'
            observed = self.observe(task)
            save(self.run / 'cases/B09-process.json', {'taskId': task, 'creatorPid': creator, 'replacementPid': self.proc.pid,
                 'retryEntrySignals': attempts, 'confirmedExitRecords': [json.loads(p.read_text()) for p in (self.run / 'processes').glob('*.exited')],
                 'memoryOutcomeNotRestoredAsSuccess': True, 'observations': observed})
            self.remove_failure(table)
        self.case('B09', b09)

        def b10():
            case = 'B10_TOOL_LATE'
            task = self.create(case, '', 'tool')
            self.cancel(task)
            before = self.snapshot(task)
            assert before['tool_call_log'][0]['status'] == 'FAILED'
            observations, observer_errors = [], []
            first_read, releasing, handler_done = threading.Event(), threading.Event(), threading.Event()
            def observe_across_release():
                try:
                    # Exactly three bounded reads: held handler, release in progress, actual handler exited.
                    for phase in (None, releasing, handler_done):
                        if phase is not None and not phase.wait(15):
                            raise AssertionError('B10 observer phase did not arrive')
                        observations.append(self.observe(task))
                        first_read.set()
                except Exception as failure:
                    observer_errors.append(str(failure))
                    first_read.set()
            observer = threading.Thread(target=observe_across_release, daemon=True)
            observer.start()
            assert first_read.wait(15) and not observer_errors, observer_errors
            late = self.b_command('late-record-writes', taskId=task)
            assert late == {'toolRowsUpdated': 0, 'stepRowsUpdated': 0}, late
            releasing.set()
            self.release(case, 'tool')
            handler_path = self.run / 'b-handler-late' / (case + '.json')
            wait_for(handler_path.exists, 'actual late handler mapper write attempts')
            handler_late = json.loads(handler_path.read_text())
            assert handler_late == {'toolRowsUpdated': 0, 'stepRowsUpdated': 0}, handler_late
            handler_done.set()
            observer.join(timeout=15)
            assert not observer.is_alive() and not observer_errors and len(observations) == 3, observer_errors
            assert self.snapshot(task) == before
            assert all(value == observations[0] for value in observations), observations
            save(self.run / 'cases/B10.json', {'taskId': task, 'lateWriteAttempts': late, 'actualHandlerLateWriteAttempts': handler_late, 'observations': observations,
                 'originalUsageQualityPreserved': self.row(task)['token_usage_quality']})
        self.case('B10', b10)

    def close(self, error=None):
        for proc in self.processes:
            if proc.poll() is None: self.kill(proc)
        if self.external: self.external.server.shutdown()
        if self.started_pg:
            self.command([self.pg / 'pg_ctl', '-D', self.run / 'pgdata', '-m', 'fast', '-w', 'stop'], 'postgres-stop.log')
        for i in range(1, 11):
            self.cases.setdefault(f'B{i:02d}', {'status': 'NOT_RUN', 'reason': 'Stopped at first unresolved failure'})
        save(self.run / 'matrix.json', self.cases)
        passed = error is None and all(c['status'] == 'PASSED' for c in self.cases.values())
        save(self.run / 'run-result.json', {'status': 'PASSED' if passed else 'FAILED', 'stage': self.stage, 'finishedAt': now(),
             'matrix': self.cases, 'error': str(error) if error else None, 'automaticExecutionRetries': 0 if passed else None,
             'boundary': 'Controlled independent HTTP model/embedding/vector/tool fixture, actual PostgreSQL transactions and B09 JVM SIGKILL/waitpid/restart; no real provider evidence',
             'requiredCompanionTests': ['TaskExternalCallDeadlineTest scheduler races and nested single permit ownership', 'TaskSettlementServiceTest bounded delays and classifier boundaries'],
             'skippedExternal': ['V47 paid provider/Qdrant regression is separately reported']})
        print('V02B evidence retained: ' + str(self.run), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir')
    parser.add_argument('--compiled-classpath')
    parser.add_argument('--pg-port', type=int, default=55463)
    parser.add_argument('--backend-port', type=int, default=18063)
    parser.add_argument('--external-port', type=int, default=19063)
    parser.add_argument('--frontend-port', type=int, default=5183)
    args = parser.parse_args()
    args.legacy_ref = A.LEGACY_BASELINE
    acceptance = Acceptance(args)
    error = None
    try:
        acceptance.preflight()
        acceptance.stage = 'matrix'
        acceptance.run_matrix()
        acceptance.kill()
        acceptance.audit_receipts()
        acceptance.stage = 'complete'
    except Exception as ex:
        error = ex; traceback.print_exc()
    finally:
        acceptance.close(error)
    return 1 if error else 0


if __name__ == '__main__':
    raise SystemExit(main())
