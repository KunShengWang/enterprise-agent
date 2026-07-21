# Unified Agent Workbench Turn History Evidence

Date: 2026-07-21
Status: Automated gates passed; P6 remains BLOCKED pending manual browser acceptance

## 1. Data relationship audit

The authoritative audit is recorded in [unified-agent-workbench-turn-history-gap-matrix.md](unified-agent-workbench-turn-history-gap-matrix.md).

Historical events were not deleted. The former UI stopped the old WorkItem SSE, cleared Presentation/Event arrays, cursors and deduplication sets, then replaced the single Detail/Tree/Answer projection with the new WorkItem. PostgreSQL retains all WorkInputs, WorkItems, WorkLinks, WorkEvents and Runtime source events.

The existing schema supplies a stable Turn identity without a new table:

```text
Conversation
  -> NORMAL_GOAL WorkInput (turnId = inputId)
      -> WorkItem (unique sourceInputId)
          -> Routing / Dispatch / WorkLink
          -> Run, Incident or Recovery Plan
          -> WorkEvent and PublicPresentation
```

Command inputs such as Resume, Cancel, Pause and Add Input do not become new goal Turns. They remain part of the target WorkItem's control and attempt history.

## 2. Conversation, Turn and WorkItem model

The frontend derives `ConversationTurn` from the existing WorkInput and WorkItem facts:

```text
turnId = inputId = workItem.sourceInputId
```

It contains the Conversation, WorkItem, target and terminal state coordinates requested by the product contract. `projectConversationTurns` performs a stable chronological sort and ignores command Inputs that have no source WorkItem.

`useWorkbenchTurnHistory` stores an immutable-keyed snapshot per Turn:

- WorkItem Detail and WorkLink;
- PUBLIC and Inspector Presentation;
- WorkEvent;
- Execution Tree;
- Budget;
- Approval;
- authoritative persisted or projected final answer.

Historical terminal snapshots are reused while their WorkItem version is unchanged. Non-terminal/current snapshots are refreshed and the active SSE state is merged only into the matching `turnId/workItemId`.

## 3. Component tree

```text
UnifiedWorkbench
  -> WorkbenchTaskSidebar (Conversation history)
  -> WorkbenchConversationPanel
      -> ConversationTurnSection (one per goal Turn)
          -> ConversationItemRenderer
              -> ExecutionNarrativeGroup
              -> Tool / Preview / Approval / Error
              -> Final Answer
  -> WorkbenchComposer
  -> ExecutionInspector
      -> Scope selector: Turn / WorkItem / Conversation
      -> Activity / Agents / Tools / Evidence / Diagnostics
      -> EventPayloadDrawer
```

Completed Turns collapse only their execution process. User message, final answer and error result remain visible. Running Turns remain expanded. Selecting a Turn locks the Inspector; “跟随当前执行” returns selection to the newest Turn.

## 4. Inspector scope

| Scope | Projection |
|---|---|
| 本轮 | Selected Turn with events limited to WorkItem lifecycle and active Run/Incident/Plan source IDs |
| 当前 WorkItem | All WorkItem events, including old Run attempts and resume/control events |
| 整个 Conversation | Every cached WorkItem grouped by Turn; Agent, Tool and Evidence projections are aggregated |

Every selection/scope change increments `scopeGeneration` and creates a new `scopeRequestKey`. Historical loading has its own Conversation generation token. No asynchronous response from a prior Conversation can update the new scope.

## 5. Public execution narrative

`aggregateExecutionNarrative` consumes only `visibility=PUBLIC` Presentation. It never reads raw WorkEvent payload, Prompt, system messages or model reasoning.

Deterministic rules:

- routing summary and standard process remain explicitly labeled;
- repeated “starting/started execution” states collapse into one action;
- context preparation collapses into one action;
- Tool activity remains a dedicated safe Tool card;
- retry, reconciliation and lease recovery remain explicit;
- all merged steps retain every `sourcePresentationId` for Inspector location;
- INTERNAL Presentation is rejected before grouping.

Incident PublicPresentation now exposes safe domain milestones:

```text
已启动只读 Multi-Agent 调查
已派发 Order / Inventory / MQ Specialist
Specialist 已完成取证
Reviewer 正在汇总证据
已生成事故 Assessment
未执行任何恢复操作
```

These messages are deterministic projections of lifecycle facts. They do not claim or expose hidden model reasoning.

## 6. Automated evidence

Frontend P6 Turn History smoke proves:

- one Conversation with three goal Turns;
- command Input excluded from goal Turns;
- stable Turn order after reversed/reloaded input;
- three independent user messages and final answers;
- Presentation isolation per Turn;
- narrative compression and complete source-ID retention;
- INTERNAL content exclusion;
- Turn selection lock and follow-current recovery;
- Turn scope excludes old Run while WorkItem scope retains it;
- Conversation scope returns all Turn groups;
- 100-Turn projection completes below the 500 ms contract threshold;
- Inspector exposes all three scopes, request key and follow control.

The existing P3 stream smoke continues to prove that a stale SSE callback from the old WorkItem cannot write into the new WorkItem and that Child Run delta cannot overwrite the Primary answer.

Final automated gates:

```text
Backend targeted:              24 tests, 0 failures
Backend full regression:       302 tests, 0 failures, 11 environment-gated skips
PostgreSQL Workbench:          13 suites, 63 tests, 0 failures, 0 skipped
Frontend npm test:             P3-P6 and Turn History smoke passed
TypeScript + Vite build:       passed
Route smoke:                   9/9 HTTP 200
```

The PostgreSQL gate used a schema-only `enterprise_agent_codex_turn_gate` database. It was dropped after the run. Existing tenant-isolation tests verify cross-tenant WorkItem, Presentation, Event and Conversation access is rejected; this repair adds no bypass endpoint.

## 7. Screenshot status

No screenshot is claimed. Both in-app browser control and Windows UI control failed during runtime initialization with the same local error: `failed to write kernel assets: path not found`. The Vite production build and route smoke passed, but a real browser screenshot must come from the required manual acceptance after restarting the 8083 backend.

The manual screenshot must show:

- at least three Turns in one Conversation;
- an expanded running/current Turn and a collapsed completed Turn;
- retained old final answers;
- the Inspector scope selector;
- Conversation Activity grouped by Turn;
- compact domain execution narrative.

## 8. Modified files for this repair

Frontend model and state:

- `frontend/src/types/workbench.ts`
- `frontend/src/types/conversation.ts`
- `frontend/src/utils/conversationTurns.ts`
- `frontend/src/utils/executionNarrative.ts`
- `frontend/src/utils/inspectorScope.ts`
- `frontend/src/composables/useTurnSelection.ts`
- `frontend/src/composables/useWorkbenchTurnHistory.ts`

Frontend UI:

- `frontend/src/views/UnifiedWorkbench.vue`
- `frontend/src/components/WorkbenchConversationPanel.vue`
- `frontend/src/components/ConversationTurnSection.vue`
- `frontend/src/components/ConversationItemRenderer.vue`
- `frontend/src/components/ExecutionInspector.vue`
- `frontend/src/styles.css`

Public contract and tests:

- `src/main/java/com/agent/platform/workbench/presentation/PublicPresentationService.java`
- `src/test/java/com/agent/platform/workbench/presentation/PublicPresentationServiceTests.java`
- `frontend/scripts/workbench-turn-history-smoke.mjs`
- `frontend/package.json`

Documentation:

- `docs/reports/unified-agent-workbench-turn-history-gap-matrix.md`
- `docs/reports/unified-agent-workbench-turn-history-evidence.md`
- `docs/reports/unified-agent-workbench-frontend-p6-final-evidence.md`

## 9. Remaining acceptance work

- restart the user's 8083 backend so the new PublicPresentation projection is active;
- reload the 5173 page at 100% browser zoom;
- execute and inspect a three-Turn Conversation;
- capture the required Turn-history and Inspector screenshots;
- confirm interaction and layout manually.

No P6 checkpoint was created and nothing was pushed.

## 10. Inline execution detail interaction

The final P6 interaction repair separates business inspection from technical inspection:

- clicking an execution record now toggles an inline semantic detail region;
- clicking it again collapses the region;
- only the explicit “在检查器中打开” action locates the source WorkEvent in the right Inspector;
- completed records start collapsed, while the currently active record starts expanded;
- expansion state is local to the Turn's renderer instance and cannot leak into another Turn;
- the final answer remains outside the execution detail region.

The inline region is built from PUBLIC Presentation plus already-public Execution Tree Evidence/Assessment. It displays actor type, role, public status, event category, occurrence time, evidence count, safe task/Incident/Evidence references, deterministic findings and the public output summary. It never serializes WorkEvent payload or the stored Specialist `outputSummary.answer`.

The backend PublicPresentation projection was narrowed to explicit safe fields for Incident records:

```text
role
actorType
eventCategory
incidentId
requiredEvidenceSubtypes
evidenceCount
evidenceIds
targetStatus
sideEffectExecuted
```

Evidence findings use a fixed frontend allowlist for counts, request IDs and queue names from the public Evidence DTO. Assessment findings use public risk, conflict count and confirmed-fact statements. Raw payload, Prompt, system message and chain-of-thought remain excluded.

Validation after this interaction repair:

```text
PublicPresentation targeted tests: 11 passed
Backend full regression:           302 passed, 0 failed, 11 environment-gated skips
Frontend P3-P6 tests:              passed
TypeScript and Vite build:         passed
```
