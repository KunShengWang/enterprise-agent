# Unified Agent Workbench Public Presentation P2 Gap Matrix

> Audit date: 2026-07-20
> Baseline: P0-P1 checkpoint `c96290d`
> Scope: read-only audit before P2 implementation

## 1. Decision Summary

P2 should add a deterministic, versioned presentation projection over existing authoritative facts. It should not add a new business fact table.

Selected API shape:

```text
GET /api/agent/work-items/{workItemId}/presentations?afterSequence=-1&limit=500
GET /api/agent/work-items/{workItemId}/presentations/stream?afterSequence=-1
```

Presentation sequence will be derived from WorkEvent sequence with a fixed ordinal (`workSequence * 10 + ordinal`). This supports more than one presentation for one WorkEvent while preserving replay order. Presentation IDs will be deterministic from WorkItem, source event ID, kind, and ordinal. Runtime `MODEL_DELTA` remains on the existing unified event stream and is not copied into Public Presentation.

## 2. Gap Matrix

| Product requirement | Current code fact | Recommended authority | New field/projection | Expected files | Test evidence | Risk |
|---|---|---|---|---|---|---|
| Publish task understanding without internal reasoning | `RoutingDecisionRecord.decision` stores `targetId`, `modelConfidence`, internal `reason`, extracted input, missing input, and `userFacingSummary` together | Effective routing decision after RoutePolicyValidator | Project only `userFacingSummary`; Java fallback from allow-listed target label and disposition | Public presentation projector/service | Internal reason/confidence absent from API tests | Existing frontend falls back to `decision.reason` |
| Stable public route summary | `ROUTING_DECIDED` payload contains target/disposition and can contain internal validation reasons | Effective routing decision + validated WorkItem target | `ROUTE_SUMMARY` with safe target label | Projector | Missing summary fallback tests | Raw validation payload contains identifiers and reasons |
| Standard process is not an Agent plan | Frontend `planFor(target)` hardcodes steps and labels them `执行计划` | `ExecutionTargetDefinition` or Java product catalog | `STANDARD_PROCESS` kind with configured steps | Target public catalog + projector | No-public-plan test | Misrepresentation of template as model plan |
| Real execution plan only when explicitly persisted | No generic persisted `publicPlan` exists; Incident delegation plan and recovery plan are domain records | Explicit allow-listed `publicPlan` payload only | `EXECUTION_PLAN` only when source payload has validated public steps | Projector | Explicit plan and rejection tests | Guessing plan from ordinary model text would expose reasoning |
| Tool display metadata is authoritative | `ToolDefinition` has name, description, schema, risk, metadata; no display name or public argument whitelist | Existing `ToolDefinition.metadata` | Add metadata keys `publicDisplayName`, `publicArgumentKeys`, `publicActionSummary` | Tool catalogs + presentation service | Catalog display-name tests | MCP/unknown tools need safe fallback |
| Tool arguments are minimized | Runtime `TOOL_REQUESTED` payload contains complete `arguments`; raw `/events` exposes it | Tool-specific allow-list in `ToolDefinition` | `PublicToolPresentation.publicArguments` | Projector/sanitizer | Secrets, URL, SQL, headers removed | Unknown nested secrets and unrestricted MCP schemas |
| Tool result summary is safe | Runtime `TOOL_COMPLETED` payload contains projected result content/error/metadata; frontend inspects raw metadata | Tool result projected metadata + deterministic fallback | Public result summary/count/duration/attempt | Projector/sanitizer | Raw content/payload absent | Result metadata may still contain domain payload |
| Backend duration and attempt | Frontend subtracts timestamps; runtime events have source timestamps and tool call IDs but no stable attempt label | Paired WorkEvents and source timestamps; call order by WorkEvent sequence | Deterministic duration and ordinal attempt in projector | Projector | Repeated tool tests | Missing request event after replay requires fallback |
| Retry is user readable | Incident events include `TASK_RETRY_SCHEDULED`; routing and dispatch have retry/failure events | WorkEvent phase/event type | `RETRY` presentation with fixed safe summary | Projector | Retry mapping tests | Failure reason/stack must stay inspector-only |
| UNKNOWN and reconciliation are user readable | Dispatch marks `UNKNOWN`; `DISPATCH_RECONCILED` and recovery UNKNOWN states exist | WorkItem state + WorkEvent phase | `RECOVERY` / `ACTION_STARTED` safe summaries | Projector | UNKNOWN/reconciliation tests | Internal idempotency details in payload |
| Lease takeover is public without owner/token | Incident `TASK_LEASE_RECOVERED` is projected; lease owner and fencing data may be payload fields | Event type only for public summary | `RECOVERY`, detail stripped; raw event remains inspector | Projector | owner/token exclusion tests | Accidentally copying payload leaks topology |
| Budget exhausted is readable | Incident `BUDGET_EXHAUSTED`, budget store exceptions and events exist | Projected event type/phase | `ERROR` or `WAITING_FOR_USER` safe summary | Projector | Budget summary test | Raw budget account internals should not be copied |
| Approval and preview are public but bounded | Route preview is returned in WorkItem detail; runtime approval reason can be model/policy text | Preview safe fields and approval record | `APPROVAL_REQUIRED` with safe title/summary and reference IDs | Projector/query service | Approval visibility tests | Approval reason may include internal policy detail |
| Agent delegation is safe | Agent Tree `objective` is currently rendered directly by frontend | Task event with explicit public summary; otherwise role/status only | `AGENT_DELEGATION` with allow-listed role label | Projector | Objective exclusion test | Objective can contain internal identifiers or prompt-derived text |
| Evidence remains a business fact | Agent Tree returns complete evidence/conflicts/assessment to Inspector | Existing evidence tables and Inspector API | Public presentation references counts/status, not raw evidence | Projector | Evidence payload exclusion | Duplicating evidence creates a second fact source |
| Final answer has one authority | Existing stream sends `MODEL_DELTA`; frontend later loads Primary ASSISTANT message | Primary persisted message/run ID | `FINAL_RESULT` references run/message, no answer body | Projector | Final reference and child isolation tests | Copying final body creates divergence after guardrail rewrite |
| Malformed ToolCall fails closed | `JsonAgentModelGateway` catches structured parse errors and returns `plain_text_fallback`; stream router suppresses JSON but completion can emit it as final text | Gateway protocol parser | Throw explicit protocol exception; no final answer or delta | Gateway + tests | Malformed envelope tests | Current path can persist raw JSON as final answer |
| Public/Inspector/Internal are distinct | `/events` returns every WorkEvent and payload; frontend derives public text itself | Java visibility classifier | Versioned `PublicPresentation` DTO and endpoint filtering | New presentation package/controller integration | INTERNAL absent from public API | Treating raw event API as public remains unsafe |
| Ownership is enforced | Existing Workbench reads call `findWorkItem(principal, id)` and tenant-scoped stores | WorkbenchStore ownership check | Presentation service starts with owned WorkItem lookup | Service/controller tests | Cross-tenant denial | Bypassing service with source ID lookup |
| Replay and ordering are stable | WorkEvent has per-WorkItem sequence; frontend sorts mixed items by timestamps | WorkEvent sequence | Derived sequence and deterministic ID | Service/SSE | replay, afterSequence, duplicate tests | Timestamp sorting is unstable across stores |
| History and live use one DTO | Existing event history and SSE differ (`WorkEvent` vs `UnifiedWorkStreamItem`) | PublicPresentation projection | Same DTO for GET and SSE | Service/controller | DTO equality test | Separate mapping logic would drift |
| Hidden reasoning never leaves API | Internal route reason, validation reasons, raw payload and Agent objective are reachable today | Explicit Java allow-list | Do not copy arbitrary strings/payloads; deny sensitive keys | Sanitizer + tests | prompt/reasoning/stack tests | Blacklist-only filtering is insufficient |

## 3. Current Frontend Hardcoding Inventory

`frontend/src/utils/conversationItems.ts` currently contains:

- `toolNames`: eight tool-name to Chinese-label mappings;
- `targetName`: execution target and role display mappings;
- `planFor(target)`: four fixed process templates labeled as Agent execution plans;
- `eventStatus`: regex over phase and summary;
- `resultSummary`: payload-shape guessing for document/source/record count;
- tool duration from frontend timestamp subtraction;
- `decision.userFacingSummary ?? decision.reason` unsafe fallback;
- status cards inferred from latest phase;
- direct rendering of Agent Tree `objective`;
- direct rendering of approval `reason`;
- final answer selection from persisted message or live delta.

P2 minimal frontend adaptation should consume Public Presentation for route summary, standard process, action, tool, delegation, approval/recovery, and errors. User input and authoritative final message/live delta remain separate sources.

## 4. Raw Envelope Leak Path

Current malformed protocol path:

```text
model emits JSON-like ToolCall
-> StreamingResponseRouter suppresses deltas
-> parseTurn throws
-> JsonAgentModelGateway returns plain_text_fallback(raw)
-> complete() emits assistantText
-> Runtime can persist raw JSON as final answer
```

P2 must replace this fallback with an explicit protocol failure. Normal non-JSON text remains a valid final answer. Tool-free Commander/Reviewer/Planner structured domain JSON remains final content by design and is not interpreted as a ToolCall envelope.

## 5. Storage Decision

No new presentation table is justified for P2:

- WorkEvent already supplies a stable per-WorkItem sequence and source event identity;
- effective routing decision, active target and tool definitions can be reloaded deterministically;
- projection uses fixed Java mappings and schema version;
- history and SSE can call the same projector;
- presentation is not a second business fact source.

The tradeoff is that changing projection rules changes historical rendering. `schemaVersion=1` and deterministic IDs make this explicit. A future immutable presentation table should only be considered if legal/audit requirements demand preservation of the exact historical wording across schema upgrades.

## 6. Planned Modification Boundary

Expected production areas:

- new `workbench.presentation` model/projector/service;
- presentation GET/SSE controller endpoints;
- ToolDefinition metadata in existing catalogs;
- `JsonAgentModelGateway` fail-closed protocol handling;
- minimal TypeScript DTO/API/conversation projection adaptation.

Explicitly excluded:

- new business fact tables;
- frontend layout or visual redesign;
- Inspector redesign;
- hidden Chain of Thought;
- `DefaultAgentRuntime.run()` changes;
- P3 through P6.
