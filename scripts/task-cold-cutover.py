#!/usr/bin/env python3
"""Stop one explicitly identified legacy JVM and record its exit before execing the new JVM.
Operators must account for every executor in the database domain; this is not discovery/fencing.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import signal
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--old-pid', type=int, required=True)
    parser.add_argument('--lock-path', type=Path, required=True)
    parser.add_argument('--record', type=Path, required=True)
    parser.add_argument('--timeout-seconds', type=int, default=60)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if args.old_pid <= 1 or args.old_pid == os.getpid() or not args.lock_path.is_absolute():
        parser.error('Use an explicit legacy JVM PID and an absolute stable lock path')
    if not command or Path(command[0]).name != 'java':
        parser.error('The replacement command must directly execute java (not Maven or a shell)')
    old = subprocess.run(['ps', '-p', str(args.old_pid), '-o', 'comm='], text=True, capture_output=True)
    if old.returncode != 0 or Path(old.stdout.strip()).name != 'java':
        parser.error('The specified legacy PID must currently identify a Java process')
    args.record.parent.mkdir(parents=True, exist_ok=True)
    now = lambda: datetime.datetime.now(datetime.timezone.utc).isoformat()
    record = {'schemaVersion': 'task-cold-cutover-v1', 'oldPid': str(args.old_pid),
              'scope': 'operator-identified single-host database execution domain',
              'lockPath': str(args.lock_path), 'stopRequestedAt': now(), 'oldJvmExited': False}
    # Exclusive creation prevents overwriting an earlier cold-switch record.
    with args.record.open('x') as output:
        json.dump(record, output, indent=2)
    os.kill(args.old_pid, signal.SIGTERM)
    deadline = time.monotonic() + args.timeout_seconds
    while True:
        state = subprocess.run(['ps', '-p', str(args.old_pid), '-o', 'stat='], text=True, capture_output=True)
        # A zombie has exited and cannot execute any work; its parent may reap it later.
        if state.returncode == 1 and not state.stdout.strip() and not state.stderr.strip():
            break  # ps definitively found no matching PID
        if state.returncode != 0 or not state.stdout.strip():
            raise SystemExit('Cannot confirm legacy JVM exit: process observation failed; replacement refused.')
        if state.stdout.strip().startswith('Z'):
            break
        if time.monotonic() >= deadline:
            raise SystemExit('Legacy JVM has not exited; replacement refused. No lock is sufficient to bypass this.')
        time.sleep(0.1)
    record.update(oldJvmExited=True, oldJvmExitedAt=now(), replacementExecAt=now())
    args.record.write_text(json.dumps(record, indent=2) + '\n')
    os.environ['AGENTFLOW_TASK_RECOVERY_LOCK_PATH'] = str(args.lock_path)
    os.environ['AGENTFLOW_TASK_RECOVERY_MODE'] = 'CONTROLLED_SINGLE_HOST'
    os.execvp(command[0], command)


if __name__ == '__main__':
    main()
