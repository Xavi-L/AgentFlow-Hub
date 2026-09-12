#!/usr/bin/env python3
"""V0.2-A real JVM SIGKILL/restart + disposable PostgreSQL controlled acceptance.

The independent HTTP fixture keeps durable receipt/handler counters after a tested JVM dies.
No request, task, model or tool execution is automatically retried. Restart only settles rows.
"""
from __future__ import annotations
import argparse
import datetime as dt
import http.server
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import threading
import time
import traceback
import urllib.error
import urllib.request

REPO = Path(__file__).resolve().parents[1]
OWNER = '620000000000000001'
AGENT = '620000000000000100'
PASSWORD = 'V02a-fixture-test!'
TERMINALS = {'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'}
# Verified pre-lock production source used by the initial A17 run; remains stable after A is merged.
LEGACY_BASELINE = 'fb6a325a9d1906632ca81eee1d2eb10f86359214'


def resolve_legacy_baseline(ref):
    revision = subprocess.run(['git', 'rev-parse', '--verify', '--end-of-options', ref + '^{commit}'],
                              cwd=REPO, capture_output=True, text=True, check=True).stdout.strip()
    lock_source = subprocess.run(['git', 'ls-tree', '-r', '--name-only', revision, '--',
                                  'backend/src/main/java/com/agentflow/agent/task/recovery/TaskExecutionProcessLock.java'],
                                 cwd=REPO, capture_output=True, text=True, check=True).stdout.strip()
    if lock_source:
        raise ValueError('A17 requires a real pre-lock legacy revision; selected source already contains the process lock')
    return revision


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def wait_for(predicate, description, timeout=75):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(.025)
    raise AssertionError('Timed out waiting for ' + description)


class External:
    """Runs outside the tested JVM and fsyncs receipt evidence before blocking/replying."""
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
                        out.write(json.dumps(event) + '\n')
                        out.flush()
                        os.fsync(out.fileno())
                gate = directory / 'external-gates' / f'{case}-{boundary}'
                if Path(str(gate) + '.block').exists():
                    save(Path(str(gate) + '.reached'), event)
                    wait_for(lambda: Path(str(gate) + '.allow').exists(), 'external response gate', 3600)
                if self.path == '/tool':
                    answer = {'orderNo': case, 'status': 'SUCCESS', 'controlled': True}
                else:
                    wants_tool = case.startswith(('A04', 'A05', 'A10'))
                    if request['type'] == 'FINAL_GENERATION':
                        content = 'Controlled final answer retained as trace only when interrupted.'
                    elif wants_tool and ordinal == 1:
                        content = json.dumps({'type': 'CALL_TOOL', 'toolCode': 'order_query', 'arguments': {'orderNo': case}, 'reason': 'Read controlled order facts'})
                    else:
                        content = json.dumps({'type': 'FINISH', 'answerPlan': 'Explain local recorded facts'})
                    answer = {'content': content, 'requestId': f'{case}:{boundary}:{ordinal}',
                              'unknownUsage': case.startswith('A16_MIXED') and request['type'] == 'FINAL_GENERATION'}
                encoded = json.dumps(answer).encode()
                try:
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(encoded)))
                    self.end_headers()
                    self.wfile.write(encoded)
                except (BrokenPipeError, ConnectionResetError):
                    pass
            def log_message(self, *_args):
                pass
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def counts(self, case=None):
        with self.lock:
            events = [e for e in self.events if case is None or e['case'] == case]
            return {kind: sum(e['boundary'] == kind for e in events) for kind in ('DECISION', 'FINAL_GENERATION', 'tool', 'EMBEDDING', 'VECTOR')}


class Acceptance:
    def __init__(self, args):
        self.args = args
        self.run = Path(args.run_dir or tempfile.mkdtemp(prefix='agentflow-v02a-')).resolve()
        self.run.mkdir(parents=True, exist_ok=True)
        if (self.run / 'run-started.json').exists() or (self.run / 'pgdata').exists():
            raise ValueError('Use a fresh run directory; existing databases/runs are never reused')
        save(self.run / 'run-started.json', {'at': now(), 'scope': 'V0.2-A A01-A17'})
        (self.run / 'executed-harness.py').write_text(Path(__file__).read_text())
        self.pg = Path(os.environ.get('PG_BIN', '/Library/PostgreSQL/18/bin'))
        self.java = Path(os.environ['JAVA_HOME']) / 'bin/java'
        self.pgport, self.port, self.extport, self.frontport = args.pg_port, args.backend_port, args.external_port, args.frontend_port
        self.base = f'http://127.0.0.1:{self.port}'
        self.processes, self.sequence, self.cases = [], 0, {}
        self.proc = None
        self.started_pg = False
        self.token = None
        self.tasks = {}
        self.external = None
        self.failures = []
        self.stage = 'preflight'

    def command(self, argv, log=None, cwd=REPO, **kwargs):
        if log:
            with (self.run / log).open('w') as out:
                return subprocess.run([str(a) for a in argv], cwd=cwd, stdout=out, stderr=subprocess.STDOUT, check=True, **kwargs)
        return subprocess.run([str(a) for a in argv], cwd=cwd, capture_output=True, text=True, check=True, **kwargs).stdout.strip()

    def sql(self, sql):
        value = self.command([self.pg / 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-p', self.pgport,
                              '-U', 'v02a_fixture', '-d', 'agentflow_v02a', '-c', sql])
        return value

    def query(self, sql):
        return json.loads(self.sql("SELECT COALESCE(json_agg(q),'[]'::json) FROM (" + sql + ') q'))

    def snapshot(self, task):
        task = int(task)
        return {table: self.query(f'SELECT * FROM {table} WHERE ' + ('id' if table == 'agent_task' else 'task_id')
                                 + f'={task} ORDER BY ' + ('sequence_no' if table == 'agent_task_event' else 'id'))
                for table in ('agent_task', 'agent_step', 'llm_call_log', 'tool_call_log', 'rag_retrieval_log', 'agent_task_event')}

    def row(self, task):
        return self.query(f'SELECT * FROM agent_task WHERE id={int(task)}')[0]

    def api(self, method, path, data=None, token=None, key=None, timeout=20):
        headers = {'Content-Type': 'application/json'}
        if token or self.token:
            headers['Authorization'] = 'Bearer ' + (token or self.token)
        if key:
            headers['Idempotency-Key'] = key
        request = urllib.request.Request(self.base + '/api/v1' + path, method=method, headers=headers,
                                         data=json.dumps(data).encode() if data is not None else None)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def login(self, username='v02a-fixture'):
        status, body = self.api('POST', '/auth/login', {'username': username, 'password': PASSWORD})
        assert status == 200, body
        return body['data']['accessToken']

    def boot(self, mode='CONTROLLED_SINGLE_HOST', expect='ready', extra=None, classpath=None, cold_old_pid=None):
        self.sequence += 1
        label = f'jvm-{self.sequence:03d}'
        options = {
            'spring.devtools.restart.enabled': 'false', 'spring.datasource.url': f'jdbc:postgresql://127.0.0.1:{self.pgport}/agentflow_v02a',
            'spring.datasource.username': 'v02a_fixture', 'spring.datasource.password': '',
            'spring.datasource.hikari.maximum-pool-size': '12',
            'spring.datasource.hikari.connection-timeout': '3000',
            'agentflow.security.jwt.secret-base64': 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
            'agentflow.task.execution.mode': 'engine', 'agentflow.task.recovery.mode': mode,
            'agentflow.task.recovery.lock-path': str(self.run / 'domain.lock'), 'agentflow.task.recovery.batch-size': '2',
            'agentflow.task.dispatcher.core-pool-size': '8', 'agentflow.task.dispatcher.max-pool-size': '8',
            'agentflow.task.sse.poll-interval-ms': '50', 'agentflow.task.sse.heartbeat-interval-ms': '100',
            'agentflow.task.sse.connection-timeout-ms': '2000', 'agentflow.task.execution.final-max-output-tokens': '',
            'mybatis-plus.configuration.log-impl': 'org.apache.ibatis.logging.nologging.NoLoggingImpl',
            'logging.level.com.agentflow': 'WARN', 'server.port': str(self.port), 'server.address': '127.0.0.1',
            'agentflow.document.storage.root': str(self.run / 'documents'), 'v02a.control-dir': str(self.run),
            'v02a.external-url': f'http://127.0.0.1:{self.extport}', 'v02a.run-label': label}
        if extra:
            options.update(extra)
        argv = [str(self.java)] + [f'-D{k}={v}' for k, v in options.items()] + [
            '-cp', classpath or self.classpath, 'com.agentflow.acceptance.V02ATaskRecoveryFixture']
        if cold_old_pid is not None:
            argv = ['python3', str(REPO / 'scripts/task-cold-cutover.py'), '--old-pid', str(cold_old_pid),
                    '--lock-path', str(self.run / 'domain.lock'), '--record', str(self.run / 'cold-cutover.json'), '--'] + argv
        out = (self.run / f'{label}.log').open('w')
        proc = subprocess.Popen(argv, cwd=REPO / 'backend', stdout=out, stderr=subprocess.STDOUT)
        proc.label = label
        self.processes.append(proc)
        save(self.run / 'processes' / f'{label}.started', {'pid': proc.pid, 'at': now(), 'mode': mode, 'lockPath': str(self.run / 'domain.lock')})
        if expect == 'exit':
            wait_for(lambda: proc.poll() is not None, 'JVM refused startup', 60)
            assert proc.returncode != 0
        elif expect == 'ready':
            def ready():
                if proc.poll() is not None:
                    raise AssertionError(f'{label} exited {proc.returncode}; see {label}.log')
                return (self.run / 'processes' / f'{label}.ready').exists()
            wait_for(ready, label + ' application runner', 80)
            self.proc = proc
            self.token = self.login()
        else:
            self.proc = proc
        return proc

    def kill(self, proc=None):
        proc = proc or self.proc
        if proc is None:
            return
        if proc.poll() is None:
            os.kill(proc.pid, signal.SIGKILL)
        status = proc.wait(timeout=15)
        save(self.run / 'processes' / f'{proc.label}.exited', {'pid': proc.pid, 'at': now(), 'returnCode': status,
                                                            'confirmedWaitpid': True, 'signal': 'SIGKILL' if status == -9 else None})
        if proc is self.proc:
            self.proc = None

    def control(self, name, value=''):
        path = self.run / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    def create(self, case, gate='CLAIMED', external=None):
        self.control('case-controls/' + case, gate)
        if external:
            self.control(f'external-gates/{case}-{external}.block')
        status, body = self.api('POST', f'/agents/{AGENT}/tasks', {'userInput': case}, key=case)
        assert status == 201, (case, status, body)
        task = str(body['data']['taskId'])
        self.tasks[case] = task
        if gate in ('QUEUED', 'CLAIMED', 'FINAL_LOG', 'TOOL_LOG'):
            suffix = {'QUEUED': 'queued', 'CLAIMED': 'claimed', 'FINAL_LOG': 'final-log', 'TOOL_LOG': 'tool-log'}[gate]
            wait_for(lambda: (self.run / 'gates' / f'{task}-{suffix}.reached').exists(), case + ' ' + gate)
        elif external:
            wait_for(lambda: (self.run / 'external-gates' / f'{case}-{external}.reached').exists(), case + ' external receipt')
        return task

    def recovered(self, task, previous, cancelled=False):
        row = self.row(task)
        expected = 'CANCELLED' if cancelled else 'FAILED'
        assert row['status'] == expected, row
        assert row['phase'] is None and row['final_answer'] is None and row['citations'] == []
        assert row['termination_reason'] == ('USER_CANCELLED' if cancelled else 'SYSTEM_ERROR')
        rec = row['recovery_metadata']
        assert rec['schemaVersion'] == 'task-recovery-v1' and rec['previousStatus'] == previous
        assert rec['reasonCode'] == ('TASK_RESTART_DISPATCH_LOST' if previous == 'QUEUED' else 'TASK_RESTART_INTERRUPTED')
        assert row['error_code'] == (None if cancelled else rec['reasonCode'])
        assert rec['executionOutcome'] == ('NOT_STARTED' if previous == 'QUEUED' else 'UNKNOWN')
        completeness = 'COMPLETE' if previous == 'QUEUED' else 'UNCONFIRMED'
        assert rec['recordCompleteness'] == completeness and rec['counterCompleteness'] == completeness
        if previous == 'RUNNING':
            assert row['token_usage_quality'] == 'UNKNOWN'
        events = self.snapshot(task)['agent_task_event']
        assert len([e for e in events if e['event_type'].startswith('TASK_') and e['event_type'][5:] in TERMINALS]) == 1
        assert not any(e['event_type'] == 'ANSWER_CHUNK' for e in events)
        assert [e['sequence_no'] for e in events] == list(range(1, row['last_event_sequence'] + 1))
        if previous == 'QUEUED':
            assert row['started_at'] is None and not any(e['event_type'] == 'TASK_STARTED' for e in events)
        for step in self.snapshot(task)['agent_step']:
            assert step['status'] != 'RUNNING'
        return row

    def runner_count(self, task):
        path = self.run / 'runner-receipts.jsonl'
        return sum(json.loads(line)['taskId'] == str(task) for line in path.read_text().splitlines()) if path.exists() else 0

    def restart_check(self, task, case, previous='RUNNING', cancelled=False):
        before, counts = self.snapshot(task), self.external.counts(case)
        runner_before = self.runner_count(task)
        self.kill()
        self.boot()
        row = self.recovered(task, previous, cancelled)
        assert self.external.counts(case) == counts, 'Recovery resent external work'
        assert self.runner_count(task) == runner_before, 'Recovery redispatched the old task'
        after = self.snapshot(task)
        assert before['agent_task'][0]['execution_snapshot'] == row['execution_snapshot']
        assert before['agent_task'][0]['started_at'] == row['started_at']
        for table in ('llm_call_log', 'rag_retrieval_log'):
            assert before[table] == after[table], 'Recovery rewrote committed call facts'
        for tool in before['tool_call_log']:
            if tool['status'] != 'RUNNING':
                assert tool in after['tool_call_log']
        save(self.run / 'cases' / f'{case}.json', {'taskId': task, 'countsBefore': counts, 'countsAfter': self.external.counts(case),
                                                'before': before, 'after': after})
        return row

    def fixture_command(self, action, task, **data):
        if action == 'internal-run':
            # Probe the production admission guard, without re-entering the earlier execution gate.
            self.control('task-controls/' + str(task), 'INTERNAL_GUARD_PROBE')
        name = f'{self.sequence}-{action}-{task}-{time.monotonic_ns()}.json'
        save(self.run / 'commands' / name, {'action': action, 'taskId': task, **data})
        path = self.run / 'commands' / (name + '.result')
        wait_for(path.exists, 'test-only internal command')
        return json.loads(path.read_text())

    def case(self, name, action):
        print(name + ' running', flush=True)
        started = now()
        try:
            action()
            self.cases[name] = {'status': 'PASSED', 'startedAt': started, 'finishedAt': now()}
            print(name + ' PASSED', flush=True)
        except Exception as error:
            self.cases[name] = {'status': 'FAILED', 'startedAt': started, 'finishedAt': now(), 'error': str(error)}
            self.failures.append(name)
            save(self.run / 'cases' / f'{name}-failure.json', {'error': str(error), 'traceback': traceback.format_exc()})
            raise
        finally:
            save(self.run / 'matrix.json', self.cases)
            related = {case: task for case, task in self.tasks.items() if case == name or case.startswith(name + '_')}
            save(self.run / 'cases' / f'{name}-summary.json', {'tasks': related, 'externalCounts': {case: self.external.counts(case) for case in related},
                 'runnerEntries': {task: self.runner_count(task) for task in related.values()},
                 'snapshots': {task: self.snapshot(task) for task in related.values()}})

    def preflight(self):
        self.legacy_revision = resolve_legacy_baseline(self.args.legacy_ref)
        for executable in (self.java, self.pg / 'initdb', self.pg / 'pg_ctl', self.pg / 'psql'):
            assert executable.is_file(), executable
        ports = [self.port, self.pgport, self.extport, self.frontport]
        assert len(set(ports)) == len(ports)
        for port in ports:
            with socket.socket() as sock:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                sock.bind(('127.0.0.1', port))
        save(self.run / 'revision.json', {'head': self.command(['git', 'rev-parse', 'HEAD']),
                                        'dirtyPaths': self.command(['git', 'diff', '--name-only']).splitlines(),
                                        'legacyBaselineRevision': self.legacy_revision})
        self.stage = 'build'
        if self.args.compiled_classpath:
            (self.run / 'classpath.txt').write_text(Path(self.args.compiled_classpath).read_text())
        else:
            self.command(['mvn', '-q', '-DskipTests', 'test-compile', 'dependency:build-classpath', '-Dmdep.includeScope=test',
                          '-Dmdep.outputFile=' + str(self.run / 'classpath.txt')], 'build.log', REPO / 'backend')
        self.classpath = str(REPO / 'backend/target/test-classes') + ':' + str(REPO / 'backend/target/classes') + ':' + (self.run / 'classpath.txt').read_text().strip()
        self.stage = 'postgres'
        self.command([self.pg / 'initdb', '-D', self.run / 'pgdata', '-U', 'v02a_fixture', '--auth=trust', '--encoding=UTF8', '--no-locale'], 'initdb.log')
        self.command([self.pg / 'pg_ctl', '-D', self.run / 'pgdata', '-l', self.run / 'postgres.log', '-o',
                      f"-h 127.0.0.1 -p {self.pgport} -c unix_socket_directories=''", '-w', 'start'])
        self.started_pg = True
        self.command([self.pg / 'createdb', '-h', '127.0.0.1', '-p', self.pgport, '-U', 'v02a_fixture', 'agentflow_v02a'])
        self.external = External(self.run, self.extport)
        self.boot()
        save(self.run / 'schema.json', self.query('SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank'))

    def basic(self):
        self.case('A01', lambda: self.restart_check(self.create('A01', 'QUEUED'), 'A01', 'QUEUED'))
        self.case('A02', lambda: self.restart_check(self.create('A02'), 'A02'))
        def a03():
            task = self.create('A03', '', 'DECISION')
            assert self.external.counts('A03')['DECISION'] == 1
            assert self.snapshot(task)['llm_call_log'] == []
            self.restart_check(task, 'A03')
        self.case('A03', a03)
        def a04():
            task = self.create('A04', '', 'tool')
            assert self.snapshot(task)['tool_call_log'][0]['status'] == 'RUNNING'
            self.restart_check(task, 'A04')
            log = self.snapshot(task)['tool_call_log'][0]
            assert log['status'] == 'FAILED' and log['error_code'] == 'TASK_RESTART_INTERRUPTED'
        self.case('A04', a04)
        self.case('A05', lambda: self.restart_check(self.create('A05', 'TOOL_LOG'), 'A05'))
        def a06():
            task = self.create('A06', 'FINAL_LOG')
            assert any(r['call_type'] == 'FINAL_GENERATION' and r['status'] == 'SUCCESS' for r in self.snapshot(task)['llm_call_log'])
            row = self.restart_check(task, 'A06')
            assert row['total_tokens'] == 30 and row['token_usage_quality'] == 'UNKNOWN'
        self.case('A06', a06)
        def a07():
            task = self.create('A07')
            status, _ = self.api('POST', f'/tasks/{task}/cancel')
            assert status == 200 and self.row(task)['cancel_requested_at'] is not None
            self.restart_check(task, 'A07', cancelled=True)
            task = self.create('A07_RACE')
            self.kill()
            # Transaction fixture represents cancellation already admitted by the old execution domain.
            args = [str(self.pg / 'psql'), '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-p', str(self.pgport), '-U', 'v02a_fixture', '-d', 'agentflow_v02a']
            cancel = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
            cancel.stdin.write(f"BEGIN; UPDATE agent_task SET cancel_requested_at=clock_timestamp() WHERE id={task}; SELECT 'CANCEL_LOCK_HELD';\n")
            cancel.stdin.flush()
            assert cancel.stdout.readline().strip() == 'CANCEL_LOCK_HELD'
            restarted = self.boot(expect='pending')
            wait_for(lambda: self.query("SELECT pid FROM pg_stat_activity WHERE wait_event='transactionid' AND query LIKE '%agent_task%'"), 'recovery waits for cancellation transaction', 75)
            cancel.stdin.write('COMMIT;\n\\q\n')
            cancel.stdin.flush()
            cancel.communicate(timeout=10)
            wait_for(lambda: (self.run / 'processes' / f'{restarted.label}.ready').exists(), 'race recovery commit')
            self.token = self.login()
            self.recovered(task, 'RUNNING', True)
            save(self.run / 'cases/A07-race.json', {'taskId': task, 'row': self.row(task), 'lockWaitObserved': True})
        self.case('A07', a07)
        def a08():
            for suffix, gate, previous in [('RUNNING', 'CLAIMED', 'RUNNING'), ('QUEUED', 'QUEUED', 'QUEUED')]:
                case = 'A08_' + suffix
                task = self.create(case, gate)
                self.kill()
                # Historical timestamp fixture; never writes the target terminal outcome.
                self.sql(f"UPDATE agent_task SET created_at=created_at-interval '2 hours', updated_at=updated_at-interval '2 hours', started_at=started_at-interval '2 hours' WHERE id={task}")
                self.boot()
                assert self.recovered(task, previous)['status'] == 'FAILED'
        self.case('A08', a08)
        def a09():
            saved = {}
            for terminal in sorted(TERMINALS):
                task = self.create('A09_' + terminal)
                result = self.fixture_command('terminal', task, status=terminal)
                assert result['accepted'], result
                assert self.row(task)['status'] == terminal
                saved[task] = self.snapshot(task)
            for _ in range(2):
                self.kill()
                self.boot()
                for task, snapshot in saved.items():
                    assert self.snapshot(task) == snapshot
            save(self.run / 'cases/A09.json', saved)
        self.case('A09', a09)

    def advanced(self):
        def a10():
            task = self.create('A10', '', 'tool')
            self.kill()
            before = self.snapshot(task)
            counts = self.external.counts()
            checks = []
            points = [('step', 'agent_step', 'UPDATE', f'NEW.task_id={task}'),
                      ('tool', 'tool_call_log', 'UPDATE', f'NEW.task_id={task}'),
                      ('task', 'agent_task', 'UPDATE', f'NEW.id={task} AND NEW.recovery_metadata IS NOT NULL'),
                      ('sequence', 'agent_task', 'UPDATE', f'NEW.id={task} AND NEW.last_event_sequence<>OLD.last_event_sequence'),
                      ('event', 'agent_task_event', 'INSERT', f'NEW.task_id={task}')]
            for point, table, operation, condition in points:
                self.sql(f"CREATE FUNCTION v02a_injected_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF {condition} THEN RAISE EXCEPTION 'V02A_DATABASE_FAILURE_{point}'; END IF; RETURN NEW; END $$; CREATE TRIGGER v02a_failure BEFORE {operation} ON {table} FOR EACH ROW EXECUTE FUNCTION v02a_injected_failure()")
                self.boot()
                assert self.snapshot(task) == before, 'Atomic rollback failed at ' + point
                self.assert_closed(task, 'A10_' + point)
                checks.append({'point': point, 'entireSnapshotRolledBack': True})
                self.kill()
                self.sql(f'DROP TRIGGER v02a_failure ON {table}; DROP FUNCTION v02a_injected_failure()')
            self.boot()
            self.recovered(task, 'RUNNING')
            assert self.external.counts() == counts
            save(self.run / 'cases/A10.json', {'taskId': task, 'writes': checks, 'counts': counts})
        self.case('A10', a10)
        def a11():
            tasks = [self.create(f'A11_BATCH_{i}', 'QUEUED') for i in range(3)]
            self.kill()
            first = min(tasks, key=int)
            self.control('recovery-after-commit', first)
            proc = self.boot(expect='pending')
            wait_for(lambda: (self.run / 'gates' / f'{proc.label}-after-commit.reached').exists(), 'first recovery COMMIT')
            saved = self.snapshot(first)
            assert self.row(first)['status'] == 'FAILED'
            assert sum(self.row(t)['status'] == 'QUEUED' for t in tasks) == 2
            self.kill()
            (self.run / 'recovery-after-commit').unlink()
            self.boot()
            assert self.snapshot(first) == saved
            for task in tasks:
                self.recovered(task, 'QUEUED')
            task = self.create('A11_LOST', 'QUEUED')
            self.kill()
            self.control('recovery-lost-response', task)
            self.boot()
            saved = self.snapshot(task)
            self.recovered(task, 'QUEUED')
            self.assert_closed(task, 'A11_LOST')
            self.kill()
            (self.run / 'recovery-lost-response').unlink()
            self.boot()
            assert self.snapshot(task) == saved
            save(self.run / 'cases/A11.json', {'batchTasks': tasks, 'lostCommitResponseTask': task, 'reentrantSnapshotsUnchanged': True})
        self.case('A11', a11)
        def a12():
            task = self.create('A12', '', 'DECISION')
            self.sql(f"UPDATE agent_task SET created_at=created_at-interval '2 hours', started_at=started_at-interval '2 hours', updated_at=created_at-interval '2 hours' WHERE id={task}")
            before = self.snapshot(task)
            old = self.proc
            for mode in ('CONTROLLED_SINGLE_HOST', 'DISABLED'):
                candidate = self.boot(mode=mode, expect='exit', extra={'server.port': str(self.port + 1)})
                assert old.poll() is None
                assert self.snapshot(task) == before
                assert 'lock' in (self.run / f'{candidate.label}.log').read_text().lower()
            self.proc = old
            self.restart_check(task, 'A12')
        self.case('A12', a12)
        def a13():
            task = self.create('A13', 'QUEUED')
            self.kill()
            self.boot('DISABLED')
            self.assert_closed(task, 'A13', internal=True)
            self.kill()
            self.boot()
            self.recovered(task, 'QUEUED')
        self.case('A13', a13)
        def a14():
            task = self.create('A14_ANOMALY', 'QUEUED')
            self.kill()
            # Deliberate impossible evidence fixture, not a fabricated recovered outcome.
            self.sql(f"INSERT INTO agent_step(id,task_id,step_index,step_type,status,title,started_at,created_at) VALUES (620000000000009999,{task},0,'LLM_DECISION','RUNNING','Invariant violation fixture',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
            before = self.snapshot(task)
            self.boot()
            self.assert_closed(task, 'A14_ANOMALY')
            assert self.snapshot(task) == before
            self.kill()
            self.sql('DELETE FROM agent_step WHERE id=620000000000009999')
            self.boot()
            self.recovered(task, 'QUEUED')
            self.kill()
            self.sql('ALTER TABLE agent_task RENAME COLUMN id TO v02a_missing_id')
            self.boot()
            status, body = self.api('POST', f'/agents/{AGENT}/tasks', {'userInput': 'A14_READ_FAILURE'}, key='A14_READ_FAILURE')
            assert status == 503 and body['code'] == 'TASK_EXECUTION_NOT_READY'
            log = (self.run / f'{self.proc.label}.log').read_text()
            assert 'recover' in log.lower() and ('read' in log.lower() or 'candidate' in log.lower())
            self.kill()
            self.sql('ALTER TABLE agent_task RENAME COLUMN v02a_missing_id TO id')
            self.boot()
            save(self.run / 'cases/A14.json', {'anomalyTask': task, 'anomalyRollback': True, 'recoveryReadFailureClosed': True})
        self.case('A14', a14)
        self.case('A15', self.observation)
        def a16():
            evidence = []
            for case, gate in [('A16_V1_MISSING', 'CLAIMED'), ('A16_MIXED', 'FINAL_LOG')]:
                task = self.create(case, gate)
                self.kill()
                if 'V1' in case:
                    self.sql(f"UPDATE agent_task SET execution_snapshot=(execution_snapshot-'executionSettings') || '{{\"snapshotVersion\":\"agent-task-snapshot-v1\"}}'::jsonb WHERE id={task}")
                before = self.snapshot(task)
                counts = self.external.counts(case)
                self.boot()
                row = self.recovered(task, 'RUNNING')
                assert row['execution_snapshot'] == before['agent_task'][0]['execution_snapshot']
                assert row['decision_turns_used'] == before['agent_task'][0]['decision_turns_used']
                assert row['tool_calls_used'] == before['agent_task'][0]['tool_calls_used']
                assert self.snapshot(task)['llm_call_log'] == before['llm_call_log']
                if 'MIXED' in case:
                    assert {r['usage_quality'] for r in before['llm_call_log']} == {'EXACT', 'ESTIMATED'}
                    total = sum(r['total_tokens'] or 0 for r in before['llm_call_log'])
                    assert row['total_tokens'] == total
                    assert row['recovery_metadata']['recordedUsage']['tokenUsageQuality'] == 'MIXED'
                else:
                    assert row['total_tokens'] == 0
                assert self.external.counts(case) == counts
                evidence.append({'case': case, 'taskId': task, 'before': before, 'after': self.snapshot(task)})
            save(self.run / 'cases/A16.json', evidence)
        self.case('A16', a16)
        def a17():
            task = self.create('A17', 'QUEUED')
            self.kill()
            before = self.snapshot(task)
            self.boot('DISABLED')
            self.assert_closed(task, 'A17')
            assert self.snapshot(task) == before
            self.kill()
            # Baseline JVM actually runs the selected pre-lock application classes. Cold entrypoint confirms its exit.
            self.cold_switch()
            self.recovered(task, 'QUEUED')
        self.case('A17', a17)

    def assert_closed(self, task, key, internal=False):
        key = key + '_blocked_probe'
        before = self.sql('SELECT count(*) FROM agent_task')
        status, body = self.api('POST', f'/agents/{AGENT}/tasks', {'userInput': key}, key=key)
        assert status == 503 and body['code'] == 'TASK_EXECUTION_NOT_READY', (status, body)
        status, body = self.api('POST', f'/tasks/{task}/cancel')
        assert status == 503 and body['code'] == 'TASK_EXECUTION_NOT_READY', (status, body)
        status, _ = self.api('GET', f'/tasks/{task}')
        assert status == 200
        if internal:
            for action in ('internal-create', 'internal-dispatch', 'internal-claim', 'internal-run', 'internal-cancel'):
                result = self.fixture_command(action, task, key=key + '_' + action)
                assert not result['accepted'], (action, result)
            save(self.run / 'cases/A13-internal.json', {'allFiveEntriesRejected': True})
        assert self.sql('SELECT count(*) FROM agent_task') == before
        assert self.sql(f"SELECT count(*) FROM agent_task WHERE client_request_id='{key}'") == '0'

    def observation(self):
        evidence = []
        other = self.login('v02a-other')
        before = self.external.counts()
        for case in ('A01', 'A06', 'A07'):
            task = self.tasks[case]
            status, detail = self.api('GET', f'/tasks/{task}')
            assert status == 200
            status, trace = self.api('GET', f'/tasks/{task}/trace')
            assert status == 200
            trace_task = trace['data'].get('task', trace['data'])
            assert trace_task['status'] == detail['data']['status']
            assert trace_task['recovery'] == detail['data']['recovery']
            for path in (f'/tasks/{task}', f'/tasks/{task}/trace', f'/tasks/{task}/events'):
                status, _ = self.api('GET', path, token=other)
                assert status in (403, 404), (path, status)
            request = urllib.request.Request(self.base + f'/api/v1/tasks/{task}/events', headers={'Authorization': 'Bearer ' + self.token})
            with urllib.request.urlopen(request, timeout=8) as response:
                sse = response.read().decode()
            assert 'TASK_FAILED' in sse or 'TASK_CANCELLED' in sse
            assert 'task-recovery-v1' in sse
            status, repeated = self.api('POST', f'/agents/{AGENT}/tasks', {'userInput': case}, key=case)
            assert status == 200 and repeated['data']['taskId'] == task
            evidence.append({'taskId': task, 'detail': detail['data'], 'traceTask': trace_task, 'sse': sse})
        assert self.external.counts() == before
        save(self.run / 'cases/A15.json', evidence)

    def cold_switch(self):
        # Compile the explicit immutable pre-lock Git revision into an isolated temporary checkout.
        baseline = self.run / 'legacy-baseline'
        baseline.mkdir()
        archive = subprocess.Popen(['git', 'archive', self.legacy_revision, 'backend'], cwd=REPO, stdout=subprocess.PIPE)
        self.command(['tar', '-x', '-C', baseline], stdin=archive.stdout)
        archive.stdout.close()
        assert archive.wait() == 0
        fixture = baseline / 'backend/src/test/java/com/agentflow/acceptance/V02ATaskRecoveryFixture.java'
        fixture.parent.mkdir(parents=True, exist_ok=True)
        fixture.write_text((REPO / 'backend/src/test/java/com/agentflow/acceptance/V02ATaskRecoveryFixture.java').read_text())
        self.command(['mvn', '-q', '-DskipTests', 'test-compile'], 'legacy-build.log', baseline / 'backend')
        cp = str(baseline / 'backend/target/test-classes') + ':' + str(baseline / 'backend/target/classes') + ':' + (self.run / 'classpath.txt').read_text().strip()
        legacy = self.boot(classpath=cp)
        assert legacy.poll() is None
        save(self.run / 'legacy-cold-switch-before.json', {'oldPid': legacy.pid, 'legacySourceRevision': self.legacy_revision, 'at': now()})
        # The production cutover entrypoint stops and observes the legacy JVM, then execs the actual replacement JVM.
        record = self.run / 'cold-cutover.json'
        threading.Thread(target=legacy.wait, daemon=True).start()
        self.proc = None
        self.boot(cold_old_pid=legacy.pid)
        assert legacy.poll() is not None and record.exists()
        save(self.run / 'cases/A17-cold-switch.json', {'legacySourceRevision': self.legacy_revision,
                                                    'legacyExit': legacy.returncode, 'record': json.loads(record.read_text()),
                                                    'newPid': self.proc.pid, 'sameDatabaseExecutionDomain': True})

    def browser(self):
        self.stage = 'browser'
        manifest = {'backendUrl': self.base, 'frontendUrl': f'http://127.0.0.1:{self.frontport}', 'username': 'v02a-fixture', 'password': PASSWORD,
                    'tasks': {'queued': self.tasks['A01'], 'running': self.tasks['A06'], 'cancelled': self.tasks['A07']},
                    'evidenceBoundary': 'controlled external HTTP fixture; real JVM/PostgreSQL'}
        save(self.run / 'browser-manifest.json', manifest)
        env = dict(os.environ, V02A_CONTROL_DIR=str(self.run), V02A_FRONTEND_URL=manifest['frontendUrl'],
                   AGENTFLOW_API_TARGET=self.base)
        out = (self.run / 'vite.log').open('w')
        proc = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', str(self.frontport), '--strictPort'],
                                cwd=REPO / 'frontend', env=env, stdout=out, stderr=subprocess.STDOUT)
        try:
            def ready():
                try:
                    with urllib.request.urlopen(manifest['frontendUrl'], timeout=1) as response:
                        return response.status == 200
                except OSError:
                    return False
            wait_for(ready, 'Vite', 30)
            before = self.external.counts()
            self.command(['node', 'node_modules/@playwright/test/cli.js', 'test', '--config', 'e2e/task-restart-recovery.config.ts'],
                          'browser.log', REPO / 'frontend', env=env)
            assert self.external.counts() == before
        finally:
            proc.terminate()
            proc.wait(timeout=15)

    def audit_receipts(self):
        # Independently check every receipt against the creator JVM lifetime, including advanced cases.
        receipts = [json.loads(line) for line in (self.run / 'runner-receipts.jsonl').read_text().splitlines()]
        started = {json.loads(path.read_text())['pid']: path.stem for path in (self.run / 'processes').glob('*.started')}
        exited = {}
        for path in (self.run / 'processes').glob('*.exited'):
            record = json.loads(path.read_text())
            exited[record['pid']] = dt.datetime.fromisoformat(record['at'])
        allowed = []
        for path in (self.run / 'commands').glob('*.json'):
            command = json.loads(path.read_text())
            if command['action'] != 'internal-run':
                continue
            result = json.loads(Path(str(path) + '.result').read_text())
            assert result['accepted'] is False
            label = f"jvm-{int(path.name.split('-')[0]):03d}"
            process = json.loads((self.run / 'processes' / (label + '.started')).read_text())
            allowed.append((str(command['taskId']), process['pid']))
        first = {}
        for receipt in receipts:
            task, pid = receipt['taskId'], receipt['pid']
            if task not in first:
                first[task] = pid
            else:
                assert (task, pid) in allowed, ('Unexpected repeated Runner entry', receipt)
                allowed.remove((task, pid))
        assert not allowed, 'Missing explicit internal Runner guard probe'
        assert set(first) == set(self.tasks.values())
        events = [json.loads(line) for line in (self.run / 'external-receipts.jsonl').read_text().splitlines()]
        for event in events:
            task = self.tasks[event['case']]
            creator = first[task]
            assert creator in exited, ('Creator JVM exit not recorded', creator)
            assert dt.datetime.fromisoformat(event['at']) <= exited[creator], ('External request after creator JVM exit', event)
        result = {'status': 'PASSED', 'taskCount': len(first), 'runnerReceiptCount': len(receipts),
                  'externalReceiptCount': len(events), 'recoveryExternalResends': 0, 'unexpectedRunnerReentries': 0,
                  'explicitRejectedInternalRunnerProbes': len(receipts) - len(first),
                  'method': 'Every external receipt precedes its original creator JVM confirmed exit; repeated Runner entries require a matching rejected internal guard fixture command'}
        save(self.run / 'receipt-audit.json', result)
        return result

    def close(self, error=None):
        for proc in self.processes:
            if proc.poll() is None:
                self.kill(proc)
        if self.external:
            self.external.server.shutdown()
        if self.started_pg:
            self.command([self.pg / 'pg_ctl', '-D', self.run / 'pgdata', '-m', 'fast', '-w', 'stop'], 'postgres-stop.log')
        for i in range(1, 18):
            self.cases.setdefault(f'A{i:02d}', {'status': 'NOT_RUN', 'reason': 'Stopped at the first unresolved failure'})
        save(self.run / 'matrix.json', self.cases)
        passed = error is None and all(c['status'] == 'PASSED' for c in self.cases.values())
        save(self.run / 'run-result.json', {'status': 'PASSED' if passed else 'FAILED', 'stage': self.stage, 'finishedAt': now(),
                                          'matrix': self.cases, 'error': str(error) if error else None,
                                          'automaticExecutionRetries': 0, 'recoveryExternalResends': 0 if passed else None,
                                          'boundary': 'Controlled independent HTTP model and durable tool fixture; real JVM SIGKILL/exit/restart and PostgreSQL; no paid provider evidence',
                                          'skippedExternal': ['V47 paid provider/Qdrant evidence is separately reported']})
        print('V02A evidence retained: ' + str(self.run), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir')
    parser.add_argument('--legacy-ref', default=LEGACY_BASELINE, help='Pre-lock Git revision for A17 cold cutover; defaults to the immutable initial acceptance baseline')
    parser.add_argument('--compiled-classpath', help='Reuse an explicitly prepared current test-compile and dependency classpath file')
    parser.add_argument('--pg-port', type=int, default=55462)
    parser.add_argument('--backend-port', type=int, default=18062)
    parser.add_argument('--external-port', type=int, default=19062)
    parser.add_argument('--frontend-port', type=int, default=5182)
    args = parser.parse_args()
    acceptance = Acceptance(args)
    error = None
    try:
        acceptance.preflight()
        acceptance.stage = 'matrix'
        acceptance.basic()
        acceptance.advanced()
        acceptance.browser()
        acceptance.audit_receipts()
        acceptance.stage = 'complete'
    except Exception as ex:
        error = ex
        traceback.print_exc()
    finally:
        acceptance.close(error)
    return 1 if error else 0


if __name__ == '__main__':
    raise SystemExit(main())
