"""One authorized ANOM-01 attempt after deterministic gate, then unconditional stop."""
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from contract_fix import runner, require_unused
from acceptance_v11 import evaluate

ROOT = runner.ROOT
REPORT = ROOT / 'evals/multi-agent/reports/blocker-candidates'
CAMPAIGN = ROOT / 'target/multi-agent/multi-candidates-20261004-r1'


def verify():
    current = runner.freeze()
    prior = runner.legacy.read(REPORT / 'pre-freeze.json')
    for key in ('code_sha', 'tasks', 'seed', 'world_contract', 'model_reference', 'graders', 'runner', 'phase6_driver', 'v22'):
        if current[key] != prior[key]:
            raise RuntimeError('Frozen business evaluation changed: ' + key)
    if current['harness_runner']['metrics.py'] != prior['harness_runner']['metrics.py']:
        raise RuntimeError('Routing rubric changed')
    for path, digest in runner.legacy.read(REPORT / 'historical-preserved.json').items():
        if runner.legacy.digest(ROOT / path) != digest:
            raise RuntimeError('Historical artifact changed: ' + path)
    if subprocess.check_output(['git', 'branch', '--show-current'], text=True).strip() != 'feat/multi-agent':
        raise RuntimeError('Wrong branch')
    if subprocess.check_output(['git', 'diff', '--cached', '--name-only'], text=True).strip():
        raise RuntimeError('Index is not empty')
    if runner.legacy.digest(ROOT / 'src/main/java/com/agent/platform/procurement/tool/ProcurementToolHandler.java') != '8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d':
        raise RuntimeError('Protected Handler changed')
    return current


def main():
    if len(sys.argv) != 1:
        raise RuntimeError('No overrides, resumes or retries')
    require_unused(CAMPAIGN)
    frozen = verify()
    if runner.legacy.read(REPORT / 'model-gate.json')['freeze'] != frozen:
        raise RuntimeError('Source changed after deterministic gate')
    suites = [ET.parse(p).getroot() for p in (ROOT / 'target/surefire-reports').glob('TEST-*-phase6-candidates.xml')]
    if len(suites) != 6 or sum(int(s.get('tests')) for s in suites) != 88 or any(int(s.get(k, 0)) for s in suites for k in ('failures', 'errors', 'skipped')):
        raise RuntimeError('Focused gate failed')
    subprocess.run(['git', 'diff', '--check'], check=True)
    tasks = [p for p in (ROOT / 'evals/procurement-v2/tasks').rglob('*.json') if runner.legacy.read(p)['task_id'] == 'ANOM-01']
    if len(tasks) != 1:
        raise RuntimeError('Task identity ambiguous')
    with (ROOT / 'target/procurement-v2/world.lock').open('a+b') as stream:
        if sys.platform == 'win32':
            import msvcrt
            stream.seek(0); stream.write(b'0'); stream.flush(); stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        CAMPAIGN.mkdir(exist_ok=False)
        runner.legacy.write(CAMPAIGN / 'freeze.json', frozen)
        runner.legacy.write(CAMPAIGN / 'authorization.json', dict(tasks=['ANOM-01'], max_new_attempts=1, prior_valid_trials=9, no_retry=True))
        attempt = CAMPAIGN / 'ANOM-01-1-a1'
        print('START ANOM-01 sole authorized attempt', flush=True)
        row = runner.trial(tasks[0], attempt)
        if (attempt / 'raw.json').exists():
            raw = runner.legacy.read(attempt / 'raw.json')
            row, consistency, evidence = evaluate(runner.legacy.read(tasks[0]), row, raw)
            audits = [dict(toolCallId=e['toolCallId'], childRunId=e['result']['metadata'].get('childRunId'), audit=e['result']['metadata']['_domainValidationAudit']) for e in raw['executions'] if (e.get('result') or {}).get('metadata', {}).get('_domainValidationAudit')]
            runner.legacy.write(attempt / 'candidate-validation-audit.json', audits)
            runner.legacy.write(attempt / 'final-state-consistency.json', consistency)
            runner.legacy.write(attempt / 'acceptance-evidence.json', evidence)
        else:
            row.update(acceptance_status='FAIL', acceptance_failures=['missing raw evidence'], SupervisorFinalStateConsistency='UNVERIFIED')
        row['cohort'] = 'post-blocker-candidate-contract-fix'
        runner.legacy.write(attempt / 'result.json', row)
        runner.legacy.write(CAMPAIGN / 'accounting.json', dict(prior_valid_trials=9, new_attempts=1, new_valid=int(row['status'] != 'EVAL_INFRA_ERROR'), new_infra=int(row['status'] == 'EVAL_INFRA_ERROR'), stopped=True))
        runner.legacy.write(CAMPAIGN / 'source-unchanged.json', dict(unchanged=verify() == frozen))
        print(json.dumps({k: row.get(k) for k in ('task_id', 'status', 'failure_reason', 'acceptance_status', 'acceptance_failures', 'SupervisorFinalStateConsistency', 'tool_calls', 'tool_errors', 'total_tokens')}, ensure_ascii=False), flush=True)
        print('STOP. PASS or FAIL does not authorize further trials.', flush=True)


if __name__ == '__main__':
    main()
