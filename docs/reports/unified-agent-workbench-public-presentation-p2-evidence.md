# Unified Agent Workbench Public Presentation P2 Evidence

> Date: 2026-07-20
> Baseline checkpoint: `c96290d fix(workbench): repair live streaming and terminal projection`
> Result: **PASSED**
> Scope: P2 Public Presentation Contract only. P3-P6 were not started.

## Checkpoint provenance

| Layer | Commit | Meaning |
|---|---|---|
| P0-P1 checkpoint | `c96290d` | Live streaming and terminal projection baseline |
| P2 backend commit | `f2f6a30` | Public Presentation schema, projection, API/SSE, tool metadata, protocol boundary and backend tests |
| P2 integrated checkpoint | `392c824` | Complete P2 tree after adding the transparent frontend baseline and minimal Presentation consumption |

The integrated frontend commit intentionally combines two sources:

- the uncommitted three-column Unified Workbench UI that existed before P2;
- the minimal P2 PublicPresentation DTO, API client and conversation projection consumption.

They cannot be reliably split because `UnifiedWorkbench.vue` contains mixed changes, `workbench.ts` mixes pre-P2 fields with P2 DTOs, the Workbench API file mixes earlier command APIs with Presentation APIs, `conversationItems.ts` was already untracked before P2, and the related components have no verifiable pre-P2 snapshot. The frontend commit therefore does not claim that all of its UI was created by P2.

`DefaultStreamingAgentExecutor.java` remains an excluded pre-existing worktree modification. It is not part of either P2 commit. P3-P6 were not started.

## 1. Gap matrix

The read-only audit is recorded in
`docs/reports/unified-agent-workbench-public-presentation-p2-gap-matrix.md`.

The audit found four blocking problems:

1. The middle timeline inferred routing, plans, tool names and phases in TypeScript.
2. Internal routing reason and raw event payload could become fallback user copy.
3. Tool display metadata and argument disclosure policy were not authoritative.
4. A malformed ToolCall envelope could be downgraded into plain final text.

P2 closes these gaps with a deterministic Java projection over existing WorkEvent facts. It does not add a second business fact source or a new persistence table.

## 2. Final schema

```java
public record PublicPresentation(
    String presentationId,
    String workItemId,
    long sequence,
    int schemaVersion,
    PublicPresentationKind kind,
    PublicPresentationStatus status,
    String title,
    String summary,
    List<String> steps,
    PublicPresentationDetail detail,
    String sourceType,
    String sourceId,
    String sourceEventId,
    Instant occurredAt,
    PublicVisibility visibility
) {}
```

Stable kinds are `TASK_UNDERSTANDING`, `ROUTE_SUMMARY`, `STANDARD_PROCESS`, `EXECUTION_PLAN`, `ACTION_STARTED`, `ACTION_COMPLETED`, `TOOL_ACTIVITY`, `AGENT_DELEGATION`, `WAITING_FOR_USER`, `APPROVAL_REQUIRED`, `RETRY`, `RECOVERY`, `FINAL_RESULT`, and `ERROR`.

`schemaVersion=1`. Sequence is deterministic: `WorkEvent.sequence * 10 + ordinal`. `presentationId` is a name-based UUID derived from WorkItem, source event, kind and ordinal. Rebuilding the same source event therefore returns the same ID and order.

## 3. Visibility boundary

| Visibility | Public timeline | Inspector endpoint | Rule |
|---|---:|---:|---|
| `PUBLIC` | yes | yes | User-readable execution summary |
| `INSPECTOR_ONLY` | no | yes | Technical execution state without raw secret fields |
| `INTERNAL` | no | no | Prompt, policy, CAS and raw protocol internals |

Visibility is assigned by Java. The model and frontend cannot override it. The projection rejects or strips password, token, authorization, cookie, API key, SQL, URL, headers, prompt, system prompt, reasoning, chain-of-thought and stack-trace fields.

The public routing copy uses only `routingDecision.userFacingSummary` or a Java safe fallback. Internal route `reason`, model confidence and unvalidated target details are not projected.

## 4. Event mapping

| Authority event | Public presentation | Notes |
|---|---|---|
| `ROUTING_STARTED` | `ACTION_STARTED` | Goal classification started |
| `ROUTING_DECIDED` | `TASK_UNDERSTANDING`, `ROUTE_SUMMARY`, `STANDARD_PROCESS` | Adds `EXECUTION_PLAN` only when explicit `publicPlan` exists |
| `CLARIFICATION_REQUIRED` | `WAITING_FOR_USER` | Persisted pause, no blocked thread |
| `ROUTE_CONFIRMATION_REQUIRED` | `APPROVAL_REQUIRED` | References immutable preview |
| `DISPATCH_STARTED` | `ACTION_STARTED` | No target IDs leaked in copy |
| `DISPATCH_RECONCILED` | `RECOVERY` | UNKNOWN/reconciliation summary |
| `EXECUTION_DISPATCHED` | `ACTION_COMPLETED` | Target execution established |
| `RUN_STARTED` | `ACTION_STARTED` | Primary run started |
| `CONTEXT_PREPARED/COMPACTED` | `ACTION_COMPLETED` | Public context-ready summary |
| `TOOL_REQUESTED/COMPLETED` | `TOOL_ACTIVITY` | Backend metadata, whitelist arguments, duration and attempt |
| `APPROVAL_REQUIRED` | `APPROVAL_REQUIRED` | Frontend attaches authoritative Approval to this item |
| `RUN_RESUMED` | `RECOVERY` | Checkpoint continuation summary |
| `RUN_COMPLETED` | `FINAL_RESULT` | References Primary Run; does not copy answer text |
| `RUN_FAILED/CANCELLED` | `ERROR` | Safe terminal copy, no stack trace |
| `TASK_RETRY_SCHEDULED` | `RETRY` | Bounded retry summary |
| `TASK_LEASE_RECOVERED` | `RECOVERY` | Safe takeover summary; no owner or fencing token |
| Incident task created/assigned | `AGENT_DELEGATION` | Domain Agent delegation summary |
| Recovery preview/waiting/unknown/reconcile | action/approval/recovery | Controlled recovery lifecycle |
| `BUDGET_EXHAUSTED` | `ERROR` | Explains fail-closed behavior |
| Technical CAS/Prompt/Raw events | `INTERNAL` | Never returned by Workbench presentation APIs |
| Other technical events | `INSPECTOR_ONLY` | Kept out of the conversation timeline |

`MODEL_DELTA` is intentionally not copied into PublicPresentation. The existing unified SSE remains the live answer channel. The persisted Primary Run assistant message remains the final-answer authority, and Child Run deltas remain isolated.

## 5. Standard Process vs Execution Plan

`STANDARD_PROCESS` is a product-owned template from `PublicExecutionCatalog`. It describes the normal steps for General, OrderCare, Incident Investigation and Incident Recovery Plan targets.

`EXECUTION_PLAN` is emitted only when an explicit persisted `publicPlan` exists. P2 does not parse ordinary model prose with regex and does not label a product template as the model's real plan. Invalid, oversized or sensitive plan steps are rejected as a whole without blocking execution.

## 6. Tool public metadata

Tool definitions now provide:

```text
publicDisplayName
publicActionSummary
publicArgumentKeys
```

Metadata is registered in the General capability registry, OrderCare catalog, Incident catalog and local tool registry. Only whitelisted scalar or bounded-list values are exposed. Sensitive key names are denied even if accidentally whitelisted. Unknown/MCP tools use a stable generic display and expose no arguments.

`PublicToolPresentation` includes display name, action summary, safe arguments, deterministic result summary/count, authoritative backend duration, and `Attempt N`. Raw ToolCall and ToolResult payloads remain technical data.

## 7. Malformed ToolCall repair

`JsonAgentModelGateway` now treats suspected structured ToolCall responses as protocol data:

- a ToolCall protocol response must begin with `{`;
- parse failures throw `LlmCallException` with `MODEL_PROTOCOL_ERROR`;
- malformed protocol content cannot become `plain_text_fallback` or final assistant text;
- streaming keeps a bounded safety tail and suppresses suspected JSON/ToolCall envelopes from `MODEL_DELTA`.

`ToolResultBoundaryTests` verifies reasoning-prefix envelopes fail closed and malformed streaming envelopes never reach user deltas.

## 8. API and SSE order

P2 selected API option A:

```text
GET /api/agent/work-items/{workItemId}/presentations?afterSequence=-1&limit=500
GET /api/agent/work-items/{workItemId}/presentations/stream?afterSequence=-1
GET /api/agent/work-items/{workItemId}/presentations/inspector?afterSequence=-1&limit=500
```

History and SSE use the same `PublicPresentation` DTO. SSE event IDs are `p:{sequence}`. `Last-Event-ID` and query `afterSequence` resolve to their monotonic maximum. The cursor is exclusive, and deterministic IDs make reconnect deduplication stable.

The unified product sequence is a projection order over a single WorkItem, not a claim of distributed physical time ordering.

## 9. Authorization

Every history, Inspector and SSE request obtains `AuthenticatedPrincipal` from `WorkbenchPrincipalProvider`. `PublicPresentationService` first calls principal-scoped `WorkbenchStore.findWorkItem`; an unauthorized tenant receives not-found semantics. No tenant, owner or role is accepted from the request body or query string.

Controller unit tests lock principal/cursor propagation. PostgreSQL integration rejects cross-tenant reads.

## 10. Idempotency and replay

- Same `sourceEventId + kind + ordinal` produces the same `presentationId`.
- Same source WorkEvent sequence produces the same presentation sequence.
- `afterSequence` is exclusive for history and SSE.
- Replay does not write a second table and cannot create a second business event.
- Concurrent readers rebuild the same immutable DTO; there is no presentation projector claim race.
- The frontend consumes presentation IDs and keeps raw WorkEvents only for the technical Inspector.

## 11. PostgreSQL evidence

`PublicPresentationPostgresIT` passed against PostgreSQL and verifies persisted history, SSE/history DTO equality, exclusive replay cursor, stable IDs, sensitive-field exclusion and cross-tenant rejection.

The P0-P1 and M1-M3 Workbench PostgreSQL gates were then run one suite at a time and all passed:

```text
JdbcWorkbenchStorePostgresIT
JdbcRoutingStorePostgresIT
JdbcDispatchStorePostgresIT
WorkbenchTenantIsolationPostgresIT
DispatchTargetIdempotencyPostgresIT
UnifiedWorkbenchControllerPostgresIT
UnifiedWorkEventProjectorPostgresIT
UnifiedWorkEventStreamPostgresIT
UnifiedWorkHistoryReplayPostgresIT
UnifiedWorkExecutionTreePostgresIT
WorkCommandHandlerPostgresIT
HierarchicalBudgetPostgresIT
PublicPresentationPostgresIT
```

An initial aggregate run was invalidated by a running local backend and stale M1-A cleanup ordering. The backend was stopped, stale rows were removed by exact test identifiers, and the M1-A test cleanup was updated for later Routing/Dispatch foreign keys. The authoritative result is the final isolated serial run above.

## 12. Frontend minimal consumption

The frontend adds the TypeScript PublicPresentation DTO and history/SSE URL client. `conversationItems.ts` now maps backend presentation kinds directly and no longer owns `toolNames`, `planFor(target)`, route-reason fallback, phase/status regex or frontend duration calculation.

Final text still comes from the persisted Primary Run message or the existing live delta buffer. Preview remains a direct interactive card. If an `APPROVAL_REQUIRED` presentation exists, the Approval record is attached to that card instead of rendering a duplicate card.

Evidence:

```text
npm test       -> conversation projection smoke passed
npm run build  -> vue-tsc -b and Vite production build passed
```

## 13. Full regression

```text
P2 targeted unit/controller/PostgreSQL: 23 passed, 0 failed
Backend mvn test:                       282 tests, 0 failures, 0 errors, 11 skipped
Frontend smoke:                         passed
Frontend vue-tsc -b:                    passed
Frontend Vite production build:         passed
git diff --check:                       passed (line-ending warnings only)
```

The 11 skipped tests are existing external FlowOrder, RabbitMQ or real-model E2E gates and are unrelated to P2.

## 14. Modified files

P2 production contract:

- `src/main/java/com/agent/platform/workbench/presentation/*`
- `src/main/java/com/agent/platform/workbench/web/UnifiedWorkController.java`
- `src/main/java/com/agent/platform/runtime/JsonAgentModelGateway.java`
- General, OrderCare, Incident and local tool catalog metadata files

P2 tests:

- `src/test/java/com/agent/platform/workbench/presentation/*`
- `src/test/java/com/agent/platform/runtime/ToolResultBoundaryTests.java`
- `src/test/java/com/agent/platform/workbench/web/UnifiedWorkControllerTests.java`
- constructor adaptation in `UnifiedWorkbenchControllerPostgresIT`
- current-schema cleanup repair in `JdbcWorkbenchStorePostgresIT`

Allowed frontend proof:

- `frontend/src/types/workbench.ts`
- `frontend/src/api/workbench.ts`
- `frontend/src/utils/conversationItems.ts`
- minimal consumption in `frontend/src/views/UnifiedWorkbench.vue`
- `frontend/scripts/conversation-items-smoke.mjs` and package test command

The merged Workbench UI and P2 minimal consumption are transparently recorded together in integrated checkpoint `392c824`. `DefaultStreamingAgentExecutor.java` remains excluded and uncommitted.

## 15. Unfinished items

P2 deliberately does not complete:

- frontend Store/composable restructuring;
- dedicated Presentation SSE consumption in the page (history refresh currently follows unified WorkEvents);
- final live-message rendering refinements;
- Inspector redesign and raw payload drawer;
- Markdown/GFM and visual-token unification;
- screenshot-based responsive acceptance.

These are P3-P6 concerns. They are not hidden as P2 defects and were not started in this turn.

## 16. Next stage recommendation

Proceed to P3 only after review of this contract. P3 should make PublicPresentation the sole user-readable event input in a dedicated frontend store, while retaining raw WorkEvent and Agent Tree as Inspector-only sources. It must preserve Primary Run delta isolation, persisted-message correction and the visibility boundary established here.

## Final boundary check

- P0-P1 checkpoint: `c96290d`.
- P2 backend commit: `f2f6a30`.
- P2 integrated checkpoint: `392c824`.
- P2 Public Presentation Contract: **PASSED**.
- Nothing was pushed.
- `DefaultAgentRuntime.run()` was not modified.
- P3-P6 were not started.
