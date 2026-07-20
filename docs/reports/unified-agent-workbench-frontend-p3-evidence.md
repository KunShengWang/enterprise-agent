# Unified Agent Workbench Frontend P3 Evidence

> Date: 2026-07-21
> Baseline: P2 documented checkpoint `4078270`
> Scope: Frontend State & Realtime Presentation Consumption
> Result: PASSED

## 1. Scope and gap audit

The pre-change audit is recorded in
`docs/reports/unified-agent-workbench-frontend-p3-gap-matrix.md`.

P3 removes transport, server snapshot and answer-state ownership from the page component. It does not redesign Markdown, typography, responsive behavior or the final Inspector visuals.

## 2. Component tree

```text
UnifiedWorkbench.vue
|- WorkbenchTaskSidebar.vue
|- WorkbenchConversationPanel.vue
|  `- ConversationItemRenderer.vue
|- WorkbenchComposer.vue
`- ExecutionInspector.vue

UnifiedWorkbench orchestration
|- useWorkbenchSelection
|- useWorkbenchData
|- usePresentationStream
|- usePrimaryRunStream
`- useWorkbenchConversation
```

`UnifiedWorkbench.vue` now owns page layout, selected WorkItem coordination and top-level commands. It no longer owns the implementation of history loading, both SSE transports, cursor/dedup state or the Primary answer state machine.

## 3. State layers

| Layer | Authoritative state | Owner |
|---|---|---|
| Server snapshot | WorkItem detail, WorkInput, Primary messages, Preview, Approval, Tree and Budget | `useWorkbenchData` |
| Presentation transport | history, PUBLIC/Inspector views, presentation cursor, reconnect, gap, error and IDs | `usePresentationStream` |
| Runtime transport | raw WorkEvent history, Primary delta, work/run cursors, reconnect, gap, error and IDs | `usePrimaryRunStream` |
| Conversation state | live buffer, persisted message, Primary run binding and answer state | `useWorkbenchConversation` |
| Selection state | conversation, WorkItem, task history and search | `useWorkbenchSelection` |
| Local UI state | Inspector tab, follow output, drawers, expanded items and composer state | page/components |

No Pinia dependency was introduced.

## 4. Data flow and authority boundary

```text
agent_work_input -------------------------------> USER_MESSAGE
PublicPresentation history + SSE (PUBLIC) ------> public execution entries
Unified SSE Primary MODEL_DELTA ----------------> one live answer buffer
Primary persisted ASSISTANT_TEXT ---------------> authoritative final answer
Preview / Approval DTO -------------------------> interactive cards

Raw WorkEvent -----------+
Inspector Presentation --+
Execution Tree ----------+----------------------> ExecutionInspector only
Budget / Evidence -------+
```

The middle timeline does not consume raw WorkEvent payloads, Runtime payloads, Tree objectives, budgets, hidden reasoning, protocol JSON or `INSPECTOR_ONLY` Presentation records. `INTERNAL` Presentation records are rejected before frontend state insertion.

## 5. Presentation SSE

The page now formally subscribes to:

```text
GET /api/agent/work-items/{workItemId}/presentations
GET /api/agent/work-items/{workItemId}/presentations/inspector
GET /api/agent/work-items/{workItemId}/presentations/stream?afterSequence={cursor}
```

Sequence:

```text
select WorkItem
-> load PUBLIC and Inspector history
-> merge by presentationId and sort by sequence
-> open Presentation SSE after the latest PUBLIC sequence
-> append unseen Presentation records
-> on disconnect: reconnect from cursor
-> on gap/sync-error: reload history, rebuild dedup state, reopen SSE
```

The old WorkEvent-triggered Presentation polling is no longer the realtime mechanism. WorkItem switch and component unmount close the Presentation connection. A generation token invalidates late HTTP and EventSource callbacks from the previous WorkItem.

## 6. Primary answer state machine

```text
IDLE -> WAITING -> STREAMING -> FINALIZING -> COMPLETED
                   |                |
                   +-> FAILED       +-> FAILED
                   `-> CANCELLED    `-> CANCELLED
```

- `WAITING`: input accepted, no Primary delta yet.
- `STREAMING`: the first accepted Primary `MODEL_DELTA` has arrived.
- `FINALIZING`: terminal Run or `FINAL_RESULT` arrived before the persisted answer is visible.
- `COMPLETED`: persisted Primary `ASSISTANT_TEXT` is loaded.
- `FAILED` / `CANCELLED`: authoritative terminal failure without a persisted final answer.

The persisted message replaces the live buffer and keeps the same conversation entry identity. The page therefore renders one answer entry, not a live answer plus a duplicate final answer. Child Run deltas and Child Run terminal events do not mutate the Primary answer.

## 7. Cursor, replay and isolation rules

- `presentationCursor` is the PublicPresentation sequence.
- `workCursor` is the unified WorkEvent sequence.
- `runCursor` is the complete Runtime source sequence, not the number of deltas.
- Presentation dedup uses `presentationId`.
- WorkEvent and delta dedup use their authoritative `eventId` values.
- A normal reconnect resumes from current cursors without duplicating text.
- A declared gap reloads WorkEvent history and replays Runtime events from run cursor `-1`.
- Gap replay clears only an unpersisted live buffer before deterministic replay.
- WorkItem switches close both EventSource instances, reset all cursors/ID sets and invalidate old generations.
- Primary source filtering uses the authoritative active run ID. Child source content is Inspector data only.

## 8. Conversation deduplication

| Duplicate source | Rule |
|---|---|
| Presentation history + SSE | same `presentationId` is one item |
| reconnect replay | event ID and cursor dedup |
| live + persisted answer | persisted text replaces buffer in one entry |
| Tool requested/completed | merge by authoritative tool call reference ID |
| Approval Presentation + DTO | DTO enriches Presentation; fallback DTO card only when Presentation is absent |
| Standard process + identical plan | identical step arrays render once |
| FINAL_RESULT + answer text | FINAL_RESULT is status only and never copies answer text |
| WorkEvent + Presentation | WorkEvent remains Inspector-only |

## 9. Inspector boundary

The Inspector receives independent source values rather than one synthetic state:

- WorkControlState, WorkExecutionState and WorkOutcome;
- Run/Incident/Recovery Plan state from detail and Tree;
- raw WorkEvent history;
- Inspector Presentation history;
- Execution Tree and Budget;
- Presentation SSE and delta SSE connection state;
- work, run and presentation cursors;
- last event time, gap and sync error.

P3 does not perform the final P4 Inspector visual redesign.

## 10. Presentation sequence audit

Schema v1 remains compatible:

```text
presentationSequence = workEventSequence * 10 + ordinal
```

`PublicPresentationService` now exposes one checked sequence function and fixes the contract at ten slots per WorkEvent. It rejects negative WorkEvent sequences and ordinals outside `0..9`; arithmetic overflow remains fail-closed through `Math.multiplyExact` / `Math.addExact`. Unit tests prove slot uniqueness, event ordering and invalid ordinal rejection. No historical schema or sequence formula changed.

## 11. ToolCall JSON regression

When tools are available, JSON is no longer classified as ToolCall merely because it begins with `{` or contains the text `toolCalls`.

- Valid business JSON remains final content and its complete streamed text is delivered.
- A top-level ToolCall list, or the explicit `assistantText + toolCalls` envelope, is treated as protocol.
- Protocol JSON is suppressed from `MODEL_DELTA` and final assistant text.
- A malformed explicit ToolCall envelope still fails closed as `MODEL_PROTOCOL_ERROR`.
- Profiles without capabilities continue treating domain JSON as final content.

`DefaultAgentRuntime.run()` was not changed.

## 12. Automated evidence

Backend targeted gate:

```text
UnifiedWorkControllerTests
PublicPresentationServiceTests
PublicPresentationStreamServiceTests
ToolResultBoundaryTests

25 tests, 0 failures, 0 errors
```

Full backend regression:

```text
mvn test
285 tests, 0 failures, 0 errors, 11 skipped
```

The skipped tests are the existing external FlowOrder, RabbitMQ or real-model E2E gates.

PostgreSQL Workbench gate, run serially:

```text
13 suites, 60 tests, 0 failures, 0 errors
```

It covers Workbench/Router/Dispatch persistence, tenant isolation, target idempotency, Controller, projector, unified SSE, replay, execution tree, commands, hierarchical budget and PublicPresentation persistence.

Frontend gates:

```text
npm test       -> conversation projection and P3 transport/state smoke passed
npm run build  -> vue-tsc -b and Vite production build passed
9 route smoke  -> all returned HTTP 200 with the application shell
```

The P3 smoke covers history/SSE merge, ID dedup, reconnect, visibility filtering, WorkItem generation isolation, Primary delta dedup, Child delta/terminal isolation, gap replay, `FINALIZING`, persisted correction, one final answer, failed/cancelled states, connection cleanup and approval/tool/plan deduplication.

Routes checked: `/`, `/workbench`, `/runs`, `/approvals`, `/incident-command`, `/capabilities`, `/knowledge`, `/observability`, `/api-lab`.

## 13. Modified files

Frontend state and components:

- `frontend/src/composables/useWorkbenchSelection.ts`
- `frontend/src/composables/useWorkbenchData.ts`
- `frontend/src/composables/usePresentationStream.ts`
- `frontend/src/composables/usePrimaryRunStream.ts`
- `frontend/src/composables/useWorkbenchConversation.ts`
- `frontend/src/components/WorkbenchTaskSidebar.vue`
- `frontend/src/components/WorkbenchConversationPanel.vue`
- `frontend/src/components/WorkbenchComposer.vue`
- `frontend/src/components/ConversationItemRenderer.vue`
- `frontend/src/components/ExecutionInspector.vue`
- `frontend/src/views/UnifiedWorkbench.vue`
- `frontend/src/api/workbench.ts`
- `frontend/src/types/conversation.ts`
- `frontend/src/types/workbench.ts`
- `frontend/src/utils/conversationItems.ts`

Tests and reports:

- `frontend/scripts/conversation-items-smoke.mjs`
- `frontend/scripts/workbench-p3-smoke.mjs`
- `frontend/package.json`
- `src/test/java/com/agent/platform/runtime/ToolResultBoundaryTests.java`
- `src/test/java/com/agent/platform/workbench/presentation/PublicPresentationServiceTests.java`
- `docs/reports/unified-agent-workbench-frontend-p3-gap-matrix.md`
- `docs/reports/unified-agent-workbench-frontend-p3-evidence.md`

Minimal backend contract hardening:

- `src/main/java/com/agent/platform/runtime/JsonAgentModelGateway.java`
- `src/main/java/com/agent/platform/workbench/presentation/PublicPresentationService.java`

`src/main/java/com/agent/platform/stream/DefaultStreamingAgentExecutor.java` is a protected pre-existing working-tree change. P3 did not modify it and it is excluded from the P3 file set.

## 14. Deferred work

P4-P6 have not started. The following remain explicitly deferred:

- Markdown rendering fixes;
- final streaming animation/cursor behavior;
- final Execution Inspector visual redesign;
- typography, spacing, radius and color token normalization;
- final responsive and screenshot acceptance;
- broader browser-level realtime E2E against a live model/backend.

No P3 commit was created and nothing was pushed.
