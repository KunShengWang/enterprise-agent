"""Exactly one Phase-4 ROB trial. Reuses the frozen world, task, driver and business grader."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).parent
RUN = ROOT / 'target/harness-reliability/rob01-20261005-r1'
spec = importlib.util.spec_from_file_location('phase4_harness', ROOT / 'evals/harness-core/run.py')
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)
legacy = harness.legacy
TASK = ROOT / 'evals/procurement-v2/tasks/robustness/ROB-01.json'


def freeze():
    value = harness.freeze()
    value['reliability_runner'] = {p.name: legacy.digest(p) for p in HERE.iterdir() if p.is_file()}
    return value


def gate():
    audit = ROOT / 'target/harness-reliability-audit'
    results = legacy.read(audit / 'legacy-results.json')
    assert {'procurement', 'replay', 'full'} <= {r['phase'] for r in results}
    assert all(r['exitCode'] == 0 and r['failures'] == 0 and r['errors'] == 0 for r in results)
    assert 'BUILD SUCCESS' in (audit / 'focused.log').read_text(encoding='utf-8', errors='replace')
    assert subprocess.check_output(['git', 'branch', '--show-current'], text=True).strip() == 'feat/harness-reliability'
    assert not list((ROOT / 'target/classes/com/agent/platform/procurement').glob('workflow/*.class'))


def main():
    args = argparse.ArgumentParser()
    args.add_argument('action', choices=['readiness', 'run'])
    action = args.parse_args().action
    gate()
    RUN.mkdir(parents=True, exist_ok=True)
    with (ROOT / 'target/procurement-v2/world.lock').open('a+b') as lock:
        import msvcrt
        lock.seek(0); lock.write(b'0'); lock.flush(); lock.seek(0)
        msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        if action == 'readiness':
            assert not (RUN / 'trial-dispatched.json').exists()
            frozen = freeze()
            legacy.write(RUN / 'freeze.json', frozen)
            cp = str(ROOT/'target/test-classes') + os.pathsep + str(ROOT/'target/classes') + os.pathsep + (ROOT/'target/procurement-v2-classpath.txt').read_text().strip()
            quote = lambda p: '"' + str(p).replace('\\', '/') + '"'
            argfile = RUN / 'preflight.args'
            argfile.write_text('-cp\n' + quote(cp) + '\n' + quote(HERE/'ReliabilityPreflight.java') + '\n' + quote(RUN/'controls.json') + '\n', encoding='utf-8')
            env = legacy.model_env()
            temp = ROOT/'target/procurement-v2/jvm-temp'; temp.mkdir(parents=True, exist_ok=True)
            env['TEMP'] = env['TMP'] = str(temp)
            legacy.command(['java', '@'+str(argfile)], RUN/'preflight.log', timeout=120, env=env)
            folder = RUN / 'readiness'; folder.mkdir(exist_ok=False)
            state = harness.world(TASK, folder, 'reset')
            assert not state['rfqs']
            assert freeze() == frozen
            legacy.write(RUN/'readiness.json', dict(status='PASS', provider_calls=0, counts=state['counts'],
                seed_hash=state['baseCanonicalBusinessHash']))
            print('Readiness PASS; provider calls=0', flush=True)
        else:
            assert legacy.read(RUN/'readiness.json')['status'] == 'PASS'
            assert freeze() == legacy.read(RUN/'freeze.json'), 'source/config changed after readiness'
            marker = RUN/'trial-dispatched.json'
            with marker.open('x', encoding='utf-8') as out:
                json.dump(dict(task='ROB-01', max_valid_trials=1, automatic_rerun=False), out)
            row = harness.trial(TASK, RUN/'ROB-01-1-a1')
            legacy.write(RUN/'result.json', row)
            legacy.write(RUN/'post-run-freeze.json', freeze())
            assert freeze() == legacy.read(RUN/'freeze.json'), 'source changed during model trial'
            print(json.dumps(row, ensure_ascii=False), flush=True)
            if not row.get('passed'): raise SystemExit(1)


if __name__ == '__main__':
    main()
