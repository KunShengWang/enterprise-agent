# Unified Agent Workbench Frontend P3 Gap Matrix

> Audit date: 2026-07-21
> Baseline: P2 documented checkpoint `4078270`
> Scope: frontend state boundaries and realtime Presentation consumption only

## 1. Current responsibilities

`frontend/src/views/UnifiedWorkbench.vue` currently owns all of the following in one component:

- conversation and WorkItem selection;
- WorkInput, WorkItem, WorkItemDetail and Primary message loading;
- PublicPresentation history loading;
- Execution Tree, Budget and Approval loading;
- Raw WorkEvent history and unified SSE transport;
- Primary `MODEL_DELTA` buffering;
- work/run cursors, reconnect, gap recovery and event ID deduplication;
- conversation projection, scroll-follow state and drawer state;
- submit, focus, preview, approval and runtime command actions;
- the complete three-column page template.

This makes WorkItem-switch isolation and transport behavior difficult to test independently.

## 2. Current data flow

| Concern | Current implementation | P3 gap |
|---|---|---|
| Presentation history | `loadAllPresentations()` calls the P2 history API | Loaded, but stored directly in the page |
| Presentation SSE | Not subscribed | Raw WorkEvent triggers another history GET instead |
| Unified SSE | One `EventSource` receives WorkEvent, MODEL_DELTA, gap and heartbeat | Raw events and Primary answer transport share page-local mutable state |
| MODEL_DELTA | Appended to `liveAnswer` after event ID deduplication | No explicit answer state; source/run binding is implicit |
| Persisted answer | Conversation messages are loaded by the 5-second page refresh | Delayed correction; no `FINALIZING` state |
| Conversation timeline | WorkInput + PUBLIC Presentation + Preview/Approval + live/persisted answer | Boundary is mostly correct, but projection and transport are coupled |
| Inspector | Raw WorkEvent + Execution Tree + Budget + Approval | Missing Presentation Inspector history and explicit transport diagnostics |
| WorkItem switch | `closeStream()` plus generation counters | Only one SSE exists; pending HTTP and Presentation history refresh can race |
| Reconnect | Query cursor and event ID sets | Presentation reconnect/dedup does not exist |
| Gap | Unified stream reloads WorkEvent history | Presentation stream has no recovery path |

## 3. Duplicate and race risks

- A Presentation can be loaded repeatedly because realtime updates are simulated by full history GET.
- Tool requested and completed presentations use different Presentation IDs and can render as two tool cards.
- A persisted answer and live buffer are selected ad hoc rather than by an explicit state machine.
- `FINAL_RESULT` has no explicit conversation status representation.
- A late history response can overwrite the selected WorkItem's newer state unless every caller repeats identity checks.
- Approval DTO and `APPROVAL_REQUIRED` must be joined by reference rather than rendered independently.
- WorkItem switching resets a single stream but cannot close a separate Presentation connection because none exists.

## 4. P3 target boundaries

| Layer | New owner |
|---|---|
| WorkItem selection and conversation history | `useWorkbenchSelection.ts` |
| Server snapshots and authoritative DTOs | `useWorkbenchData.ts` |
| Presentation history + SSE + inspector history | `usePresentationStream.ts` |
| Raw WorkEvent + Primary MODEL_DELTA transport | `usePrimaryRunStream.ts` |
| Primary answer state and Conversation ViewModel | `useWorkbenchConversation.ts` |
| Left task navigation | `WorkbenchTaskSidebar.vue` |
| Middle timeline and scroll behavior | `WorkbenchConversationPanel.vue` |
| Input composer | `WorkbenchComposer.vue` |
| Technical details | existing `ExecutionInspector.vue`, with explicit source/transport props |

`UnifiedWorkbench.vue` will retain layout coordination and top-level user actions only.

## 5. Authority rules

The middle timeline will consume only:

1. `agent_work_input` DTOs;
2. `PublicPresentation` records with `visibility=PUBLIC`;
3. Primary Run MODEL_DELTA held in the answer state machine;
4. the persisted Primary `ASSISTANT_TEXT`;
5. authoritative Preview and Approval DTOs.

Raw WorkEvent, Runtime payload, Agent Tree, Budget, Trace, Evidence and Inspector Presentation remain technical Inspector inputs. No phase regex, route reason, frontend tool dictionary, tree objective, raw ToolCall or hidden reasoning is allowed into the conversation projection.

## 6. Expected modified files

New files:

- `frontend/src/composables/useWorkbenchSelection.ts`
- `frontend/src/composables/useWorkbenchData.ts`
- `frontend/src/composables/usePresentationStream.ts`
- `frontend/src/composables/usePrimaryRunStream.ts`
- `frontend/src/composables/useWorkbenchConversation.ts`
- `frontend/src/components/WorkbenchTaskSidebar.vue`
- `frontend/src/components/WorkbenchConversationPanel.vue`
- `frontend/src/components/WorkbenchComposer.vue`
- frontend state/transport smoke tests
- `docs/reports/unified-agent-workbench-frontend-p3-evidence.md`

Modified files:

- `frontend/src/views/UnifiedWorkbench.vue`
- `frontend/src/types/conversation.ts`
- `frontend/src/utils/conversationItems.ts`
- `frontend/src/api/workbench.ts`
- `frontend/src/components/ConversationItemRenderer.vue`
- `frontend/src/components/ExecutionInspector.vue`
- `frontend/package.json`
- minimal P2 backend sequence assertion/tests and ToolCall protocol boundary tests if audit proves gaps

No P4-P6 visual redesign is in scope.
