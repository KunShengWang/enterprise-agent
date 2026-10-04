"""Additive trace and routing accounting. Does not alter the frozen business grader."""
import collections

TOOLS={'analyze_requisition':'procurement-requisition-agent-v1','source_suppliers':'procurement-sourcing-agent-v1','prepare_rfq':'procurement-rfq-agent-v1'}

def routing(task):
    outcome=task['expected']['outcome']
    required={'source_suppliers'} if outcome in ('recommend','rfq') else set()
    if outcome=='rfq': required.add('prepare_rfq')
    forbidden={'prepare_rfq'} if outcome!='rfq' else set()
    if outcome in ('clarify','duplicate'): forbidden.add('source_suppliers')
    return dict(required=sorted(required),forbidden=sorted(forbidden),optional=sorted(set(TOOLS)-required-forbidden))

def multi_agent_metrics(task,raw):
    runs=raw.get('runs',[]);root=raw['result']['runId']
    children=[r for r in runs if r['request']['metadata'].get('_domainParentRunId')==root]
    child_ids={r['runId'] for r in children}
    root_models=[m for m in raw['models'] if m['request']['runId']==root]
    child_models=[m for m in raw['models'] if m['request']['runId'] in child_ids]
    def tokens(models):return sum(m.get('turn',{}).get('usage',{}).get('totalTokens',0) for m in models)
    def peak(models):return max((m.get('turn',{}).get('usage',{}).get('promptTokens',0) for m in models),default=0)
    counts=collections.Counter(r['executionProfile']['name'] for r in children)
    actual={tool for tool,agent in TOOLS.items() if counts[agent]}
    rubric=routing(task);errors=sorted(set(rubric['required'])-actual)+sorted(actual&set(rubric['forbidden']))
    tools=collections.defaultdict(set)
    for m in raw['models']:
        agent=next(r['executionProfile']['name'] for r in runs if r['runId']==m['request']['runId'])
        tools[agent].add(len(m['request']['tools']))
    outcomes=collections.Counter(e['result'].get('metadata',{}).get('errorType','NONE') for e in raw.get('executions',[]) if e['toolName'] in TOOLS and e.get('result'))
    child_turns=sum(r['budgetSnapshot']['turns'] for r in children)
    child_stops=[dict(run_id=r['runId'],agent=r['executionProfile']['name'],state=r['state'],
                     stop_reason=r['state'] if r['state'] in ('COMPLETED','WAITING_APPROVAL','WAITING_INPUT') else r.get('failureReason') or 'UNKNOWN') for r in children]
    return dict(subagent_calls=len(children),subagent_calls_by_agent=dict(counts),
        child_turns=child_turns,child_tool_calls=sum(r['budgetSnapshot']['toolCalls'] for r in children),
        root_turns=max(0,raw['result'].get('budget',{}).get('turns',len(root_models)+child_turns)-child_turns),
        root_model_calls=len(root_models),child_model_calls=len(child_models),child_stop_reasons=child_stops,
        child_tokens=tokens(child_models),supervisor_tokens=tokens(root_models),
        token_boundary='Agent model only by role; input guardrail provider tokens remain included in frozen total, not assigned by role',
        root_peak_context=peak(root_models),child_peak_context=peak(child_models),overall_max_context=peak(raw['models']),
        visible_tool_counts={k:sorted(v) for k,v in tools.items()},routing_rubric=rubric,routing_errors=errors,
        unnecessary_subagent_calls=None,unnecessary_limitation='No reliable universal rubric; forbidden routing reported separately',
        child_failures=sum(r['state'] in ('FAILED','BLOCKED','MANUAL_REVIEW') for r in children),agent_tool_outcomes=dict(outcomes))
