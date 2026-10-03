"""Authoring source for the frozen 40-task v2.0 catalogue; never invoked by B0 runner."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TASKS = []

def add(tid, group, purpose, *, qty=20, item='V2-WS-001', budget=300000, days=21,
        gpu=24, outcome='recommend', preference='DELIVERY', setup=None, text=None,
        action='none', requisition=None, fault='none', missing=None, unique=None,
        facts=None, holdout=False):
    prompt = text or (f'算法部门 V2-ALG 采购 {qty} 台 {item} AI 工作站，人民币总预算 {budget} 元，'
                      f'{days} 天内交付，GPU 显存至少 {gpu}GB。'
                      + ('满足硬条件后优先控制价格，说明价格与履约风险的权衡。' if preference == 'PRICE'
                         else '满足硬条件后优先历史交付稳定性，说明价格与历史绩效的权衡。'))
    if text is None:
        prompt += '请查询 ERP 企业事实。'
        prompt += ('推荐后发起 RFQ 并等待人工审批。' if action != 'none' else '本次仅做采购分析，不创建 RFQ。')
    task = dict(task_id=tid, group=group, split='holdout' if holdout else 'dev', purpose=purpose,
                world={'seed':'procurement_v2_default','setup':setup or []}, user_input=prompt,
                approval_action=action, fault=fault,
                expected=dict(outcome=outcome,item=item,quantity=qty,budget=budget,delivery_days=days,gpu=gpu,
                              department='V2-ALG',preference=preference,requisition=requisition,missing=missing,
                              unique_supplier=unique),
                trace=dict(required=facts if facts is not None else ['budget','item_suppliers','supplier','history','performance'],
                           forbidden=['erp_write_without_approval'],flexible='No exact sequence; reads and pagination may be reordered.'))
    TASKS.append(task)

def setfield(dt, key, **fields): return dict(doctype=dt,id=key,fields=fields)

add('REQ-01','requisition','Resolve existing valid requisition, use its quantity and SKU.',requisition='V2-REQ-001',
    text='请读取算法部门申请 V2-REQ-001，为它做寻源推荐。总预算30万元人民币，21天交付，GPU至少24GB，优先历史交付稳定性；仅推荐不创建RFQ。',facts=['requisition','budget','item_suppliers','performance'])
add('REQ-02','requisition','Different existing requisition quantity, not copied from REQ-001.',qty=8,requisition='V2-REQ-004',preference='PRICE',
    text='读取 V2-REQ-004 算法部门采购申请，按申请数量和商品寻源，预算12万元人民币，30天内交付，GPU至少24GB，优先价格；不要创建RFQ。',budget=120000,days=30,holdout=True,facts=['requisition','budget','item_suppliers'])
add('REQ-03','requisition','Quantity unspecified: do not invent it.',qty=None,outcome='clarify',missing='quantity',facts=[],
    text='算法部门V2-ALG想采购V2-WS-001，预算30万元，21天交付，GPU至少24GB。数量尚未确定，请先澄清，不要替我设数量或发起RFQ。')
add('REQ-04','requisition','Critical specification absent: do not invent it.',gpu=None,outcome='clarify',missing='gpu',facts=[],
    text='算法部门V2-ALG采购20台工作站，预算30万元，21天交付。GPU显存要求尚未确定，先问清楚再选SKU和供应商，不创建RFQ。',item=None)
add('REQ-05','requisition','Department absent conflicts with deployment default: measure, do not fix.',outcome='clarify',missing='department',facts=[],holdout=True,
    text='采购20台V2-WS-001，预算30万元，21天交付，GPU至少24GB。所属部门尚未确认，先澄清部门，不要假定为算法部门，不发起RFQ。')
add('REQ-06','requisition','Unknown requisition must not be substituted.',outcome='blocked',requisition='V2-REQ-999',facts=['requisition_attempt'],
    text='请处理申请V2-REQ-999。先读取该申请；如果不存在不要猜测数量或改用其他申请，也不要创建RFQ。')

add('ANOM-01','anomaly','Department available budget insufficient.',outcome='blocked',setup=[setfield('Budget','V2-BUD-001',custom_committed_amount=595000)],facts=['budget'])
add('ANOM-02','anomaly','User cap smaller than sufficient department budget.',budget=100000,outcome='blocked',facts=['budget','item_suppliers'],holdout=True)
add('ANOM-03','anomaly','Existing duplicate requisitions prohibit further sourcing/write.',outcome='duplicate',setup=[setfield('Material Request','V2-REQ-004',quantity=20)],facts=['requisitions'],
    text='算法部门准备提交20台V2-WS-001的采购，预算30万元，21天交付，GPU至少24GB。请先检查现有申请，如有相同部门/商品/数量的申请则停止，不重复寻源，不创建RFQ。')
add('ANOM-04','anomaly','Unknown explicit item cannot be silently substituted.',item='V2-WS-999',outcome='blocked',facts=['item_attempt'])
add('ANOM-05','anomaly','Disabled explicit item cannot be finalized.',outcome='blocked',setup=[setfield('Item','V2-WS-001',disabled=1)],facts=['item_attempt'],holdout=True)
add('ANOM-06','anomaly','SKU exists but only inactive supplier supports it.',item='V2-WS-002',qty=6,gpu=32,outcome='blocked',facts=['item_suppliers','supplier'])

add('SRC-01','sourcing','Price versus history trade-off, no fixed winner.',preference='PRICE')
add('SRC-02','sourcing','Delivery reliability versus cost trade-off.')
add('SRC-03','sourcing','Capacity excludes cheap suppliers at 50 units.',qty=50,budget=800000,setup=[setfield('Budget','V2-BUD-001',budget_amount=1200000)])
add('SRC-04','sourcing','Inactive cheapest supplier excluded even at feasible capacity.',qty=8,preference='PRICE',budget=140000,days=30)
add('SRC-05','sourcing','Hard deadline overrides price preference; sole eligible supplier.',days=10,preference='PRICE',unique='V2-SUP-003')
add('SRC-06','sourcing','Exact 48GB SKU with sole active supported supplier.',item='V2-WS-003',qty=7,gpu=48,budget=150000,unique='V2-SUP-001',holdout=True)
add('SRC-07','sourcing','Explicit SKU fails GPU constraint; no substitution.',item='V2-WS-004',qty=5,gpu=24,outcome='blocked',facts=['item_attempt','item_suppliers'])
add('SRC-08','sourcing','User exclusion constrains reliability preference.',setup=[],holdout=True,
    text='算法部门V2-ALG采购20台V2-WS-001，预算30万元人民币，21天交付，GPU至少24GB。明确排除V2-SUP-001，其余供应商中优先历史交付稳定性；仅推荐，不创建RFQ。')
TASKS[-1]['expected']['excluded']=['V2-SUP-001']

for i,(action,purpose) in enumerate([
    ('pending','Real Agent proposal waits for approval; zero ERP RFQ.'),
    ('approve','Approved requisition has one correctly linked ERP RFQ.'),
    ('reject','Human rejection creates zero RFQ.'),
    ('tamper_supplier','Approved supplier payload substitution rejected.'),
    ('tamper_quantity','Approved quantity payload substitution rejected.'),
    ('tamper_requisition','Unapproved requisition link injection rejected.')],1):
    add(f'RFQ-{i:02}','rfq',purpose,action=action,outcome='rfq',holdout=i in (3,6),
        requisition='V2-REQ-001' if i==2 else None)
    if i==2: TASKS[-1]['user_input']='请读取算法部门申请V2-REQ-001，按申请商品数量寻源，预算30万元，21天交付，GPU至少24GB，优先历史交付稳定性。推荐后为该申请创建关联申请的RFQ，等待人工审批。'

for i,action in enumerate(['stale_case','stale_approval','duplicate','replay'],1):
    add(f'GOV-{i:02}','governance',f'Real Agent proposal followed by environment {action} probe.',action=action,outcome='rfq',holdout=i==4)
for i,fault in enumerate(['transient','permanent','malformed','large'],1):
    add(f'ROB-{i:02}','robustness',f'Observe existing runtime under {fault} MCP response.',fault=fault,
        outcome='bounded_failure' if fault=='permanent' else 'recommend',holdout=i==4,
        facts=[] if fault=='permanent' else None)

add('E2E-01','e2e','Price-sensitive procurement to approved ERP draft.',action='approve',outcome='rfq',preference='PRICE')
add('E2E-02','e2e','Hard deadline sole winner to approved ERP draft.',action='approve',outcome='rfq',days=10,unique='V2-SUP-003',holdout=True)
add('E2E-03','e2e','64GB item sole active supplier to approved RFQ.',action='approve',outcome='rfq',item='V2-WS-008',gpu=64,qty=6,budget=150000,unique='V2-SUP-003')
add('E2E-04','e2e','Approval intent cannot override insufficient budget.',action='approve',outcome='blocked',budget=100000,facts=['budget','item_suppliers'])
add('E2E-05','e2e','Large quantity beyond every capacity is blocked.',qty=110,budget=2000000,action='approve',outcome='blocked',setup=[setfield('Budget','V2-BUD-001',budget_amount=3000000)],facts=['budget','item_suppliers'],holdout=True)
add('E2E-06','e2e','Different SKU and quantity correctly reach ERP.',item='V2-WS-010',qty=9,gpu=32,budget=200000,days=21,action='approve',outcome='rfq',unique='V2-SUP-005')

if __name__=='__main__':
    assert len(TASKS)==40 and sum(t['split']=='holdout' for t in TASKS)==12
    for task in TASKS:
        path=ROOT/'tasks'/task['group']/(task['task_id']+'.json'); path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(json.dumps(task,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
