"""V2.2 changes only the business-state hash inputs to the frozen V2.1 grader."""
from pathlib import Path
import sys
from canonical_state import VERSION, hash_set

LEGACY = Path(__file__).resolve().parents[1] / 'procurement-v2'
sys.path.insert(0, str(LEGACY / 'graders'))
from grade import grade as grade_v21

def grade(task, raw, before, after):
    b = hash_set(before, before); a = hash_set(after, before)
    # Shallow copies replace only hash inputs; raw facts, provenance versions,
    # RFQs, executions, approval evidence and all grader checks stay untouched.
    result = grade_v21(task, raw, dict(before, businessHash=b['protected']),
                       dict(after, businessHash=a['protected']))
    result['eval_version'] = 'procurement-eval-v2.2'
    result['canonical_state'] = {'contract': VERSION, 'before': b, 'after': a}
    return result
