"""Deterministic fixture authoring utility, never imported by the runtime adapter."""
import json
from pathlib import Path
from datetime import date, timedelta

ROOT = Path(__file__).resolve().parents[2] / 'data/procurement/v2/seeds'

def generate():
    suppliers = [dict(id=f'V2-SUP-{i+1:03}', name=name, approved=i != 5, capacity=cap)
                 for i, (name, cap) in enumerate([
                     ('Nebula Forge Fictional', 'HIGH'), ('Cobalt Finch Fictional', 'MEDIUM'),
                     ('Amber Circuit Fictional', 'HIGH'), ('Silver Moss Fictional', 'LOW'),
                     ('Violet Harbor Fictional', 'MEDIUM'), ('Copper Kite Fictional', 'LOW')])]
    items = [dict(id=f'V2-WS-{i+1:03}', name=f'AI Workstation {i+1:02}', gpu_memory_gb=[24,32,48,16,24,32,48,64,24,32][i],
                  warranty='36 months', uom='Nos') for i in range(10)]
    departments = [dict(id='V2-ALG', name='Algorithms'),dict(id='V2-SIM', name='Simulation'),dict(id='V2-ENG', name='Engineering')]
    budgets = [dict(id=f'V2-BUD-{i+1:03}', department=d['id'], allocated=a, committed=c, currency='CNY', fiscal_year='2026')
               for i,(d,a,c) in enumerate(zip(departments,[600000,450000,320000],[180000,210000,80000]))]
    relations=[]
    for i,s in enumerate(suppliers):
        for j in [0, (i+1)%9+1, (i+4)%9+1]:
            relations.append(dict(id=f"V2-REL-{i+1:02}-{j+1:02}",supplier=s['id'],item=items[j]['id'],
                                  reference_price=[12800,10900,13700,11900,12500,9800][i]+j*500,
                                  currency='CNY',lead_time_days=[12,20,10,25,16,8][i],capacity_units=[80,35,100,12,45,10][i]))
    history=[]
    counts=[6,5,4,3,4,2]
    for i,count in enumerate(counts):
        for j in range(count):
            ordered=date(2026,1,5)+timedelta(days=21*j+i)
            history.append(dict(id=f'V2-HIST-{i+1:02}-{j+1:02}',supplier=suppliers[i]['id'],item=items[0]['id'],
                                quantity=4+j,unit_price=[13000,10800,13900,12100,12700,9900][i]-j*25,currency='CNY',
                                ordered_on=str(ordered),delivered_on=str(ordered+timedelta(days=[12,27,11,29,18,22][i]))))
    performance=[dict(id=s['id'],supplier=s['id'],transaction_count=counts[i],on_time_rate=[.98,.65,.96,.72,.88,.55][i],
                      quality_score=[96,97,95,82,90,76][i],observation_date='2026-09-01') for i,s in enumerate(suppliers)]
    requisitions=[dict(id=f'V2-REQ-{i+1:03}',department=departments[i%3]['id'],item=items[i%3]['id'],
                       quantity=20 if i==0 else 5+i,required_by='2026-12-15') for i in range(6)]
    return dict(suppliers=suppliers,items=items,item_suppliers=relations,departments=departments,budgets=budgets,
                purchase_history=history,supplier_performance=performance,sample_requisitions=requisitions)

if __name__ == '__main__':
    ROOT.mkdir(parents=True, exist_ok=True)
    for name, records in generate().items():
        (ROOT / (name+'.json')).write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
        print(f'{name}: {len(records)}')
