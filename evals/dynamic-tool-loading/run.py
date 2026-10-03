"""Phase 5 fixed Smoke 9x1 / Dev 28x1. Reuses frozen world, driver and grading contracts."""
import importlib.util
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('frozen_harness',ROOT/'evals/harness-core/run.py')
harness=importlib.util.module_from_spec(spec);spec.loader.exec_module(harness)
original_freeze=harness.freeze

def freeze():
    value=original_freeze()
    value['dynamic_runner']={p.name:harness.legacy.digest(p) for p in Path(__file__).parent.glob('*.py')}
    return value

if __name__=='__main__':
    harness.freeze=freeze
    harness.FATAL |= {'BusinessInvariantViolation','FabricatedCriticalFact'}
    harness.main()
