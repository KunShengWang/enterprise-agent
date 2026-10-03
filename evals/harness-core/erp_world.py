"""Container-side wrapper: frozen seed/setup with the frozen V2.2 canonical preflight."""
import json
import os
from pathlib import Path
import runpy
import sys
from canonical_state import hash_set

legacy = runpy.run_path('/workspace/output/harness_legacy_world.py')
seed, frappe = legacy['seed'], legacy['frappe']
os.chdir('/home/frappe/frappe-bench/sites')
frappe.init(site='frontend', sites_path='/home/frappe/frappe-bench/sites')
frappe.connect()
frappe.set_user('Administrator')
try:
    command, taskfile, dest = sys.argv[1:4]
    if command == 'reset':
        seed.reset(); seed.seed()
        baseline = legacy['snapshot']()
        if baseline['rfqs']: raise RuntimeError('reset left RFQs')
        legacy['setup'](json.loads(Path(taskfile).read_text(encoding='utf-8')))
    state = legacy['snapshot']()
    if command == 'reset':
        # Match the frozen artifact boundary (dates/Decimal serialized with default=str).
        serialized = json.loads(json.dumps(baseline, default=str))
        state['baseCanonicalBusinessHash'] = hash_set(serialized, serialized)['business']
    Path(dest).write_text(json.dumps(state, sort_keys=True, default=str, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
finally:
    frappe.destroy()
