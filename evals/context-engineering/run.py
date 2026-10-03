"""Phase 4: reuse frozen Phase 3 selection/world/driver/grader; never retune the benchmark."""
import importlib.util
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('frozen_harness_runner', ROOT/'evals/harness-core/run.py')
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)
original_freeze = harness.freeze

def freeze():
    value = original_freeze()
    value['context_runner'] = {p.name: harness.legacy.digest(p) for p in Path(__file__).parent.glob('*.py')}
    return value

if __name__ == '__main__':
    # The frozen runner restricts mode to Smoke 9x1 or Dev 28x1, requires passed same-source
    # Smoke before Dev, preserves attempts and stops on infra or the three write invariants.
    harness.freeze = freeze
    harness.FATAL = harness.FATAL | {'BusinessInvariantViolation', 'FabricatedCriticalFact'}
    harness.main()
