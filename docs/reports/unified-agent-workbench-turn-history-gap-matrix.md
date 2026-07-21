# Unified Agent Workbench Turn History Gap Matrix

Date: 2026-07-21
Scope: P6 Manual Acceptance Defect Repair, read-only relationship audit before implementation

## 1. Executive conclusion

Historical execution data is not deleted. The database retains WorkInput, WorkItem, WorkLink, WorkEvent, Runtime messages/events and PublicPresentation source events. The current UI replaces the selected WorkItem scope: both SSE composables call `stop()`, clear arrays, reset cursors and clear deduplication sets before starting the next WorkItem stream. The middle timeline also projects only one `WorkItemDetail` plus one live answer state.

No new business fact table is required. The stable goal-turn identity already exists:

```text
turnId = agent_work_input.input_id = agent_work_item.source_input_id
```

`agent_work_item.source_input_id` has a database unique constraint. Command inputs do not create a new WorkItem. They reference an existing target WorkItem and belong to that WorkItem's attempt/control history rather than becoming a new user-goal Turn.

## 2. Cardinality audit

| Question | Current fact | Consequence |
|---|---|---|
| Inputs per Conversation | Unbounded by the domain model; query API is bounded by `limit` | One conversation can contain many goal and command inputs |
| Does every Input create a WorkItem? | No. `NORMAL_GOAL` creates a WorkItem; Resume, Cancel, Pause, Abandon, Focus and Add Input commands do not | Turn projection must distinguish goal inputs from command inputs |
| WorkItems per goal Input | Zero or one; `agent_work_item.source_input_id` is unique | `sourceInputId` is a stable Turn key |
| Inputs per WorkItem | One source goal input plus zero or more command/additional inputs targeting the WorkItem | WorkItem scope includes attempts and control history |
| Runs per WorkItem | One active Run projection plus historical RUN links/events and resume attempts | Inspector WorkItem scope must not mean only `activeRunId` |
| Incident/Plan per WorkItem | Linked through WorkLink and active Incident/Recovery Plan projections | Turn scope can resolve every target from the WorkItem |

Read-only database evidence from the current local schema:

```text
agent_work_item rows:                 81
distinct agent_work_item.source_input_id: 81
rows with activeRunId:                57
rows with activeIncidentId:            9
rows with activeRecoveryPlanId:         2
```

The database also contains conversations with multiple inputs and multiple WorkItems. Additional inputs include `ADD_INPUT_TO_ACTIVE_WORK`, `RESUME_ACTIVE_WORK`, `CANCEL_ACTIVE_WORK`, `PAUSE_ACTIVE_WORK` and `ABANDON_ACTIVE_WORK`; they correctly do not all create new WorkItems.

## 3. Authoritative relationship chain

```text
conversationId
  -> agent_work_input.input_id
      -> agent_work_item.source_input_id (unique, for NORMAL_GOAL)
          -> routingRequestId -> RoutingDecision / RoutePreview
          -> dispatchRequestId -> WorkLink
              -> RUN / INCIDENT / RECOVERY_PLAN linkedId
          -> activeRunId / activeIncidentId / activeRecoveryPlanId
          -> WorkEvent(workItemId)
          -> PublicPresentation(workItemId, sourceType, sourceId, sourceEventId)
```

PublicPresentation does not carry `sourceInputId` directly, but it carries authoritative `workItemId`; joining to the WorkItem yields the unique source goal Input without timestamp inference. Raw WorkEvent also carries `workItemId`, so it has the same stable Turn association.

## 4. Current frontend replacement points

| Component/composable | Current behavior on WorkItem change | Gap |
|---|---|---|
| `usePresentationStream.stop()` | Clears public/inspector arrays, cursor, reconnect state and `seenPresentationIds` | Previous Turn presentations disappear from frontend memory |
| `usePrimaryRunStream.stop()` | Clears raw events, work/run cursors and both event-ID sets | Previous Turn technical execution disappears from Inspector |
| `useWorkbenchConversation.prepareWork()` | Resets live buffer, persisted answer and projected result when WorkItem changes | Only one live/final answer state exists |
| `useWorkbenchData.loadSelected()` | Replaces Detail, Tree, Budget and Approval refs | Inspector has one WorkItem projection only |
| `UnifiedWorkbench.submit()` | Selects the new WorkItem and calls `stopWorkItemResources()` | New Turn replaces the active frontend execution scope |
| `projectConversationItems()` | Receives one current Detail and one current Presentation list | It cannot render a complete multi-Turn conversation |

The data is present in PostgreSQL and Runtime stores. The loss is a UI scope/cache replacement defect, not persistence deletion.

## 5. Sidebar and center audit

The sidebar already groups fetched WorkItems by `conversationId`, labels each entry with a turn count and selects by Conversation. This is directionally correct, but the count is currently WorkItem count, not an explicit Turn projection, and history discovery still depends on locally remembered conversation IDs.

The center is not yet a true multi-Turn timeline. It reconstructs some historical user/final messages from `workItems` and Runtime messages, but only the selected WorkItem gets PublicPresentation, Preview, Approval, live delta and projected Incident Assessment. Historical execution narratives, tool cards, approvals and Agent summaries are therefore absent.

## 6. Stable Turn model

The frontend will derive, without a new fact table:

```text
ConversationTurn {
  turnId: WorkInput.inputId,
  conversationId,
  inputId,
  workItemId,
  userMessage,
  executionTarget,
  controlState,
  executionState,
  outcome,
  activeRunId,
  activeIncidentId,
  activePlanId,
  createdAt,
  completedAt
}
```

Only WorkItems with a matching source goal Input become goal Turns. Command inputs remain attached to WorkItem inspector history. This preserves the rule that one WorkItem expresses one stable user goal while one Conversation contains multiple user-goal Turns.

## 7. API decision

No new endpoint is required for P6:

- Conversation inputs: existing tenant-scoped `/conversations/{id}/inputs`;
- Conversation WorkItems: existing tenant-scoped `/conversations/{id}/work-items`;
- Turn Detail/Event/Link: existing tenant-scoped `/work-items/{id}`;
- Public/Inspector Presentation: existing WorkItem endpoints;
- Execution Tree and Budget: existing WorkItem endpoints;
- Runtime conversation messages: existing conversation message endpoint.

Turn history will use the existing APIs with bounded parallel lazy loading and per-scope generation tokens. Cross-tenant access remains rejected by the backend ownership checks. Historical and realtime identity both use the same `sourceInputId/workItemId`, so no duplicate event semantics are introduced.

## 8. Required implementation

| Area | Reuse | Required change |
|---|---|---|
| Turn identity | WorkInput + WorkItem `sourceInputId` | Add frontend `ConversationTurn` projection |
| Timeline | Existing renderer and final-answer Markdown | Render grouped Turn sections; retain per-Turn state/cache |
| Realtime | Existing Presentation and unified event SSE | Keep SSE only for current running Turn; merge into its stable cache |
| Historical execution | Existing WorkItem Presentation/Event APIs | Lazy-load and cache per WorkItem instead of replacing arrays |
| Inspector | Existing component projections | Add Turn, WorkItem and Conversation scope selector with generation isolation |
| Narrative | PUBLIC Presentation contract | Add deterministic grouping; retain source Presentation IDs |
| Hidden reasoning | Existing public visibility boundary | Continue excluding INTERNAL payload, Prompt and raw reasoning |

## 9. Risks and gates

- Command inputs must not appear as independent goal Turns.
- A stale SSE callback must be rejected by both generation and WorkItem identity.
- Runtime messages must be matched by authoritative run/link, not merely conversation order.
- Conversation Inspector aggregation must be bounded and lazy to avoid an N-by-500 eager request burst.
- PUBLIC narrative may compress technical status but must preserve every source Presentation ID.
- Raw WorkEvent remains Inspector-only.
- No schema migration, new business table, hidden reasoning projection or backend state-machine change is allowed.
