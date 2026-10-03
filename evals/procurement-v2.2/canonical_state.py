"""Explicit Procurement Eval V2.2 projection, not a recursive metadata blacklist.

Scope: current procurement master facts and RFQ contracts. Child row order and
lifecycle are retained. Full state includes RFQs; protected state permits new RFQs
only subject to the unchanged authorization/cardinality/payload grader checks.
"""
from decimal import Decimal
import hashlib
import json

VERSION = 'canonical-business-state-v2.2'

# outputField:rawField. !disabled maps the ERP switch to one active boolean.
SCHEMAS = {
    'suppliers': 'id:name name:supplier_name active:!disabled approvalStatus:custom_approval_status capacity:custom_capacity lifecycle:docstatus group:supplier_group type:supplier_type frozen:is_frozen onHold:on_hold holdType:hold_type releaseDate:release_date preventRfq:prevent_rfqs',
    'items': 'id:name code:item_code name:item_name category:item_group active:!disabled uom:stock_uom gpuMemoryGb:custom_gpu_memory_gb warranty:custom_warranty lifecycle:docstatus purchasable:is_purchase_item stockItem:is_stock_item endOfLife:end_of_life purchaseUom:purchase_uom minimumOrder:min_order_qty',
    'item_suppliers': 'id:name itemId:item supplierId:supplier referencePrice:reference_price currency:currency leadTimeDays:lead_time_days capacityUnits:capacity_units lifecycle:docstatus',
    'departments': 'id:name name:department_name company:company active:!disabled parentDepartment:parent_department isGroup:is_group lifecycle:docstatus',
    'budgets': 'id:name departmentId:custom_department_id company:company allocated:budget_amount committed:custom_committed_amount currency:custom_currency fiscalYear:from_fiscal_year throughFiscalYear:to_fiscal_year startsOn:budget_start_date endsOn:budget_end_date lifecycle:docstatus costCenter:cost_center account:account',
    'purchase_history': 'id:name supplierId:supplier itemId:item quantity:quantity unitPrice:unit_price currency:currency orderedOn:ordered_on deliveredOn:delivered_on lifecycle:docstatus',
    'supplier_performance': 'id:name supplierId:supplier transactionCount:transaction_count onTimeRate:on_time_rate qualityScore:quality_score observedOn:observation_date lifecycle:docstatus',
    'sample_requisitions': 'id:name departmentId:custom_department_id company:company type:material_request_type status:status lifecycle:docstatus orderedOn:transaction_date requiredBy:schedule_date',
    'rfqs': 'id:name idempotencyKey:custom_procurement_key requestDigest:custom_request_digest company:company status:status lifecycle:docstatus transactionDate:transaction_date requiredBy:schedule_date subject:subject message:message_for_supplier terms:terms',
    'requisition_line': 'ordinal:idx parentId:parent itemId:item_code quantity:qty uom:uom stockUom:stock_uom stockQuantity:stock_qty conversionFactor:conversion_factor requiredBy:schedule_date lifecycle:docstatus warehouse:warehouse',
    'rfq_line': 'ordinal:idx parentId:parent itemId:item_code quantity:qty uom:uom stockUom:stock_uom stockQuantity:stock_qty conversionFactor:conversion_factor requiredBy:schedule_date requisitionId:material_request requisitionLineId:material_request_item lifecycle:docstatus warehouse:warehouse',
    'rfq_supplier': 'ordinal:idx parentId:parent supplierId:supplier sendEmail:send_email emailSent:email_sent quoteStatus:quote_status lifecycle:docstatus contact:contact email:email_id',
    'uom': 'ordinal:idx parentId:parent uom:uom conversionFactor:conversion_factor lifecycle:docstatus',
    'budget_period': 'ordinal:idx parentId:parent startsOn:start_date endsOn:end_date amount:amount percent:percent lifecycle:docstatus',
}
GROUPS = tuple(k for k in SCHEMAS if k not in ('rfqs', 'requisition_line', 'rfq_line', 'rfq_supplier', 'uom', 'budget_period'))
CHILDREN = {
    'items': {'uoms': 'uom'}, 'budgets': {'budget_distribution': 'budget_period'},
    'sample_requisitions': {'items': 'requisition_line'},
    'rfqs': {'items': 'rfq_line', 'suppliers': 'rfq_supplier'},
}

def _record(kind, raw):
    if not isinstance(raw, dict): raise ValueError('expected entity object: ' + kind)
    result = {}
    for pair in SCHEMAS[kind].split():
        output, source = pair.split(':'); field = source.removeprefix('!')
        if field not in raw: raise ValueError(f'missing business field: {kind}.{field}')
        value = raw[field]
        if source.startswith('!'):
            if value not in (0, 1): raise ValueError(f'invalid active switch: {kind}.{field}')
            value = value == 0
        if isinstance(value, (dict, list)): raise ValueError(f'non-scalar business field: {kind}.{field}')
        result[output] = value
    for field, child_type in CHILDREN.get(kind, {}).items():
        if not isinstance(raw.get(field), list): raise ValueError(f'missing child table: {kind}.{field}')
        # Deliberately preserve both physical list order and explicit row ordinal.
        result[field] = [_record(child_type, row) for row in raw[field]]
    return result

def _collection(kind, rows):
    if not isinstance(rows, list): raise ValueError('missing collection: ' + kind)
    result = [_record(kind, row) for row in rows]
    ids = [r['id'] for r in result]
    if any(not isinstance(k, str) or not k for k in ids) or len(ids) != len(set(ids)):
        raise ValueError('missing/duplicate business identity: ' + kind)
    key = (lambda r: (r['itemId'], r['supplierId'], r['id'])) if kind == 'item_suppliers' else (lambda r: r['id'])
    return sorted(result, key=key)

def project(snapshot):
    facts = snapshot.get('facts')
    if not isinstance(facts, dict) or set(facts) != set(GROUPS): raise ValueError('unexpected or missing business entity groups')
    return {'contract': VERSION, 'masters': {k: _collection(k, facts[k]) for k in GROUPS},
            'rfqs': _collection('rfqs', snapshot.get('rfqs'))}

def _normalized(value):
    if isinstance(value, dict): return {k: _normalized(v) for k, v in value.items()}
    if isinstance(value, list): return [_normalized(v) for v in value]
    if isinstance(value, bool) or value is None or isinstance(value, str): return value
    if isinstance(value, (int, float, Decimal)):
        number = Decimal(str(value))
        if not number.is_finite(): raise ValueError('non-finite business number')
        # Typed decimal encoding avoids both 20 vs 20.0 drift and collision with string "20".
        text = format(number, 'f')
        if '.' in text: text = text.rstrip('0').rstrip('.')
        return {'$decimal': '0' if number == 0 else text}
    raise ValueError('unsupported business value')

def stable_bytes(projection):
    return json.dumps(_normalized(projection), ensure_ascii=False, sort_keys=True,
                      separators=(',', ':'), allow_nan=False).encode('utf-8')

def _hash(value): return hashlib.sha256(stable_bytes(value)).hexdigest()
def business_hash(snapshot): return _hash(project(snapshot))
def master_hash(snapshot):
    p = project(snapshot)
    return _hash({'contract': VERSION, 'masters': p['masters']})

def protected_hash(snapshot, before):
    p = project(snapshot)
    old_ids = {r['id'] for r in project(before)['rfqs']}
    return _hash({'contract': VERSION, 'masters': p['masters'],
                  'existingRfqs': [r for r in p['rfqs'] if r['id'] in old_ids]})

def hash_set(snapshot, before):
    return {'business': business_hash(snapshot), 'masters': master_hash(snapshot),
            'protected': protected_hash(snapshot, before)}
