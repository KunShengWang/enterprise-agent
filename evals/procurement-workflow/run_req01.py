"""Authorized structured-output gate: REQ-01 x 1 only; no resume or retries."""
import argparse
import importlib.util
import json
import os
from pathlib import Path

spec = importlib.util.spec_from_file_location('workflow_gate_runner', Path(__file__).with_name('run.py'))
workflow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workflow)


def structured_metrics(raw):
    stages = {}
    for run in raw.get('stageRuns', []):
        metadata = run['request']['metadata']
        stage = metadata['procurementStage']
        expected = {'REQUISITION': 'RequisitionStageResult', 'SOURCING': 'SourcingStageResult', 'RFQ': 'RfqStageResult'}[stage]
        events = [e for e in raw.get('events', []) if e['runId'] == run['runId'] and e['type'].startswith('STRUCTURED_OUTPUT_')]
        models = [m for m in raw.get('models', []) if m['request']['runId'] == run['runId']]
        repairs = [m for m in models if m['request']['metadata'].get('_structuredCorrection')]
        validated = [e for e in events if e['type'] == 'STRUCTURED_OUTPUT_VALIDATED']
        usage = [m.get('turn', {}).get('usage') for m in repairs]
        known = all(u and u.get('source') == 'provider' for u in usage)
        stages[stage] = dict(
            typed_result_valid=bool(validated) and metadata.get('_structuredOutputType', '').endswith('.' + expected)
                and isinstance(metadata.get('_structuredOutput'), dict),
            output_type=metadata.get('_structuredOutputType'),
            structured_output_initial_success=bool(validated) and validated[-1]['payload']['structured_output_initial_success'],
            structured_output_repair_count=len(repairs),
            structured_output_failure_count=sum(e['payload'].get('structured_output_failure_count', 0) for e in events),
            repair_tokens=sum(u['totalTokens'] for u in usage) if known else None,
            repair_latency_ms=sum(m['latencyMs'] for m in repairs),
            json_mode_requested=bool(models) and all(m['request']['metadata'].get('_structuredJsonMode') for m in models),
            events=events)
    return dict(stages=stages, all_typed_valid=bool(stages) and all(s['typed_result_valid'] for s in stages.values()),
                structured_output_initial_success=sum(s['structured_output_initial_success'] for s in stages.values()),
                structured_output_repair_count=sum(s['structured_output_repair_count'] for s in stages.values()),
                structured_output_failure_count=sum(s['structured_output_failure_count'] for s in stages.values()),
                repair_tokens=sum(s['repair_tokens'] for s in stages.values()) if all(s['repair_tokens'] is not None for s in stages.values()) else None,
                repair_latency_ms=sum(s['repair_latency_ms'] for s in stages.values()))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-id', required=True)
    args = parser.parse_args()
    if Path(args.run_id).name != args.run_id:
        raise ValueError('run-id must be a folder name')
    target = workflow.ROOT / 'target/procurement-workflow' / args.run_id
    target.mkdir(parents=True, exist_ok=False)
    frozen = workflow.freeze()
    workflow.legacy.write(target / 'freeze.json', frozen)
    workflow.legacy.write(target / 'selection.json', dict(tasks=['REQ-01'], trials_per_task=1, resume=False, holdout=False))
    lock = workflow.ROOT / 'target/procurement-v2/world.lock'
    lock.parent.mkdir(parents=True, exist_ok=True)
    with lock.open('a+b') as stream:
        if os.name == 'nt':
            import msvcrt
            stream.seek(0); stream.write(b'0'); stream.flush(); stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        folder = target / 'REQ-01-1-a1'
        print('START REQ-01 x 1; stop after this trial', flush=True)
        row = workflow.trial(workflow.ROOT / 'evals/procurement-v2/tasks/requisition/REQ-01.json', folder)
        raw = workflow.legacy.read(folder / 'raw.json') if (folder / 'raw.json').exists() else {}
        structured = structured_metrics(raw)
        row['structured_output'] = structured
        row['final_acceptance'] = bool(row.get('passed') and row.get('workflow_acceptance', {}).get('passed')
            and structured['all_typed_valid'] and not row.get('invariant_violations'))
        workflow.legacy.write(folder / 'result.json', row)
        workflow.legacy.write(target / 'attempts.json', [row])
        workflow.legacy.write(target / 'summary.json', workflow.summarize([row], 1, 1))
        if workflow.freeze() != frozen:
            raise RuntimeError('Source changed during trial')
        print(json.dumps({k: row.get(k) for k in ('status', 'passed', 'final_acceptance', 'failure_reason', 'workflow_acceptance', 'structured_output')}, ensure_ascii=False), flush=True)
        if not row['final_acceptance']:
            raise SystemExit(1)


if __name__ == '__main__':
    main()
