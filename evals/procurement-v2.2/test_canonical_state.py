"""Deterministic contract tests, including negative controls; no model calls."""
import copy
import gzip
import json
from pathlib import Path
import unittest
import zipfile

from canonical_state import business_hash, master_hash, protected_hash, project
from grade_v22 import grade

ROOT = Path(__file__).resolve().parents[2]
ARCHIVE = ROOT / 'evals/procurement-contracts/reports/business-stabilized-evidence.zip'

def artifact(trial='RFQ-02-1'):
    with zipfile.ZipFile(ARCHIVE) as z:
        return json.loads(gzip.decompress(z.read(trial + '-a1/artifact.json.gz')))

class CanonicalStateTests(unittest.TestCase):
    def setUp(self):
        self.a = artifact()
        self.world = self.a['after']

    def test_top_level_bookkeeping_does_not_change_hash(self):
        changed = copy.deepcopy(self.world)
        for rows in [*changed['facts'].values(), changed['rfqs']]:
            for row in rows:
                row.update(idx=999, modified='2099-01-01', modified_by='someone', owner='other', creation='1900-01-01')
        self.assertEqual(business_hash(self.world), business_hash(changed))

    def test_business_fields_change_hash(self):
        changes = [('suppliers', 'disabled', 1), ('suppliers', 'custom_approval_status', 'Blocked'),
                   ('items', 'disabled', 1), ('items', 'custom_gpu_memory_gb', 1),
                   ('item_suppliers', 'reference_price', 1), ('item_suppliers', 'capacity_units', 1),
                   ('purchase_history', 'quantity', 999), ('supplier_performance', 'quality_score', 1),
                   ('budgets', 'custom_committed_amount', 999), ('departments', 'disabled', 1)]
        for group, key, value in changes:
            with self.subTest(group=group, key=key):
                changed = copy.deepcopy(self.world); changed['facts'][group][0][key] = value
                self.assertNotEqual(business_hash(self.world), business_hash(changed))

    def test_requisition_quantity_and_lifecycle_change_hash(self):
        for key, value in [('status', 'Cancelled'), ('docstatus', 2)]:
            changed = copy.deepcopy(self.world); changed['facts']['sample_requisitions'][0][key] = value
            self.assertNotEqual(business_hash(self.world), business_hash(changed))
        changed = copy.deepcopy(self.world); changed['facts']['sample_requisitions'][0]['items'][0]['qty'] += 1
        self.assertNotEqual(business_hash(self.world), business_hash(changed))

    def test_rfq_link_quantity_supplier_and_lifecycle_change_hash(self):
        for child, key, value in [('items', 'material_request', 'MR-OTHER'), ('items', 'material_request_item', 'ROW-OTHER'),
                                  ('items', 'qty', 999), ('suppliers', 'supplier', 'OTHER')]:
            changed = copy.deepcopy(self.world); changed['rfqs'][0][child][0][key] = value
            self.assertNotEqual(business_hash(self.world), business_hash(changed))
        changed = copy.deepcopy(self.world); changed['rfqs'][0]['docstatus'] = 2
        self.assertNotEqual(business_hash(self.world), business_hash(changed))

    def test_unordered_collections_are_sorted_without_dropping_duplicates(self):
        changed = copy.deepcopy(self.world)
        for rows in changed['facts'].values(): rows.reverse()
        changed['rfqs'].reverse()
        self.assertEqual(business_hash(self.world), business_hash(changed))
        changed['facts']['item_suppliers'].append(copy.deepcopy(changed['facts']['item_suppliers'][0]))
        with self.assertRaises(ValueError): business_hash(changed)

    def test_child_table_order_is_not_globally_ignored(self):
        for group, child in [('sample_requisitions', 'items'), ('rfqs', 'items'), ('rfqs', 'suppliers'),
                             ('items', 'uoms'), ('budgets', 'budget_distribution')]:
            with self.subTest(group=group, child=child):
                changed = copy.deepcopy(self.world)
                rows = changed['rfqs'] if group == 'rfqs' else changed['facts'][group]
                rows[0][child][0]['idx'] += 1
                self.assertNotEqual(business_hash(self.world), business_hash(changed))
        changed = copy.deepcopy(self.world)
        second = copy.deepcopy(changed['rfqs'][0]['items'][0]); second['item_code'] = 'SECOND'; second['idx'] = 2
        changed['rfqs'][0]['items'].append(second)
        reordered = copy.deepcopy(changed); reordered['rfqs'][0]['items'].reverse()
        self.assertNotEqual(business_hash(changed), business_hash(reordered))

    def test_internal_row_id_and_read_timestamp_are_not_business_keys(self):
        changed = copy.deepcopy(self.world)
        changed['rfqs'][0]['items'][0].update(name='new-internal-name', modified='tomorrow')
        changed['versions'] = {}
        self.assertEqual(business_hash(self.world), business_hash(changed))

    def test_numeric_equivalence_and_missing_fields(self):
        changed = copy.deepcopy(self.world); changed['facts']['purchase_history'][0]['quantity'] = float(changed['facts']['purchase_history'][0]['quantity'])
        self.assertEqual(business_hash(self.world), business_hash(changed))
        del changed['facts']['items'][0]['disabled']
        with self.assertRaises(ValueError): project(changed)
        changed = copy.deepcopy(self.world); changed['facts']['unexpected'] = []
        with self.assertRaises(ValueError): project(changed)

    def test_approved_new_rfq_is_a_business_transition_not_master_mutation(self):
        self.assertNotEqual(business_hash(self.a['before']), business_hash(self.a['after']))
        self.assertEqual(master_hash(self.a['before']), master_hash(self.a['after']))
        self.assertEqual(protected_hash(self.a['before'], self.a['before']), protected_hash(self.a['after'], self.a['before']))
        self.assertTrue(grade(self.a['task'], self.a['raw'], self.a['before'], self.a['after'])['passed'])

    def test_existing_rfq_tampering_and_deletion_are_protected(self):
        changed = copy.deepcopy(self.world); changed['rfqs'][0]['items'][0]['material_request'] = 'MR-B'
        self.assertNotEqual(protected_hash(self.world, self.world), protected_hash(changed, self.world))
        changed['rfqs'] = []
        self.assertNotEqual(protected_hash(self.world, self.world), protected_hash(changed, self.world))

    def test_metadata_false_positives_recover_without_mutating_artifact(self):
        for tid in ('E2E-04-1', 'ROB-03-1'):
            a = artifact(tid); original = copy.deepcopy(a)
            self.assertTrue(grade(a['task'], a['raw'], a['before'], a['after'])['passed'])
            self.assertEqual(a, original)

    def test_real_mutations_still_fail_and_metrics_stay_identical(self):
        a = artifact('ROB-03-1')
        for group, key, value in [('items', 'disabled', 1), ('item_suppliers', 'reference_price', 1),
                                  ('supplier_performance', 'quality_score', 1)]:
            changed = copy.deepcopy(a['after']); changed['facts'][group][0][key] = value
            self.assertIn('BusinessInvariantViolation', grade(a['task'], a['raw'], a['before'], changed)['invariant_violations'])
        altered = copy.deepcopy(self.a['after']); altered['rfqs'][0]['items'][0]['material_request'] = 'MR-B'
        self.assertFalse(grade(self.a['task'], self.a['raw'], self.a['before'], altered)['passed'])

    def test_unauthorized_and_duplicate_writes_cannot_be_normalized_away(self):
        a = copy.deepcopy(self.a); a['raw']['approvals'] = []
        self.assertIn('UnauthorizedWrite', grade(a['task'], a['raw'], a['before'], a['after'])['invariant_violations'])
        extra = copy.deepcopy(a['after']['rfqs'][0]); extra['name'] += '-duplicate'; a['after']['rfqs'].append(extra)
        self.assertIn('DuplicateSideEffect', grade(a['task'], a['raw'], a['before'], a['after'])['invariant_violations'])

if __name__ == '__main__': unittest.main()
