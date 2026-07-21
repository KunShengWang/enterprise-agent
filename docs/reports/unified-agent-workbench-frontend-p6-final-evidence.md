# Unified Agent Workbench Frontend P6 Final Evidence

> Date: 2026-07-21
> Baseline: P5 checkpoint `71d35fc9ecd3b71030444f2c1d74e3e9bb007b71`
> Scope: Visual System, Responsive & Final Acceptance
> Result: BLOCKED - manual acceptance defect repair completed; awaiting user retest

## 0. Manual acceptance defect repair

The user screenshot exposed three blockers: one General Agent model failure, a failed Primary Run whose WorkItem remained `DISPATCHED / RUNNING / UNDETERMINED`, and unreadably small Inspector typography.

The failing cases were traced through their persisted WorkItem, Primary Run and runtime events. The latest case was WorkItem `work-7dd9c8ed-f6f6-47a5-a78a-a1a94060b408` and Run `f4b7c468-3bf4-491a-9e28-ff93cbe74b33`. Its runtime sequence was `RUN_STARTED -> MODEL_STARTED -> MODEL_FAILED -> RUN_FAILED`; the safe error code was `MODEL_PROTOCOL_ERROR`. The Provider transport had reached response protocol classification, so this was not a missing key, model name, connectivity, timeout, rate-limit or context-overflow failure. The exact Provider HTTP status and rejected response body were not persisted and are not reconstructed. `JsonAgentModelGateway` now records only a safe shape classification, response character count and exception class; it never logs the response text, prompt or credentials.

The General execution path was also exposing the default profile's 12 capabilities, including FlowOrder recovery, Incident and ticket write tools. That violated the frozen General Agent permission boundary and unnecessarily encouraged ToolCall envelopes for ordinary knowledge questions. A server-owned `general-agent-v1` profile now limits General execution to `knowledge_search`, `skill_catalog` and read-only `ticket_status`. `GeneralAgentExecutionAdapter` always resolves this profile; clients cannot widen it.

Malformed ToolCall output remains fail-closed and is never emitted as delta or final answer. To tolerate one transient malformed Provider response without weakening that boundary, the runtime now permits one bounded protocol retry by default. The retry is budgeted as another model call, emits explicit failure/retry events, and stops with `MODEL_PROTOCOL_ERROR` when the retry count or budget is exhausted. The setting is `AGENT_MODEL_PROTOCOL_RETRIES` and defaults to `1`.

The state split was a product defect in terminal reconciliation. The Run projection supplied `failureReason=MODEL_ERROR` as `sourceOutcome`, while the authoritative `AGENT_RUN` SQL always returned an empty outcome. The authoritative-match guard correctly rejected the mismatch, leaving the cursor state watermark at `-1`. The SQL projection now reads `record_json.failureReason`. The original screenshot WorkItem was then reconciled in place to `CLOSED / FAILED / FAILED`, with state watermark `version=3, attempt=0`. Ten projector replays leave the terminal WorkItem version unchanged.

The Workbench now performs a bounded, generation-safe authoritative detail/history reload as soon as a terminal Runtime or Presentation event arrives. It rejects callbacks for a different WorkItem or superseded Primary Run. Failure rendering uses a public ERROR presentation with a safe code, retryability and correlation/trace identifier; it does not create an empty `FINAL_ANSWER`. The error card offers a new-task retry and opens Diagnostics.

A later manual Incident acceptance exposed a separate `WAITING_INPUT` presentation defect. The router had correctly persisted `queueNames` and `oneOf:batchId,requestIds` as missing inputs and the WorkItem was correctly suspended as `WAITING_INPUT / NOT_STARTED / UNDETERMINED`, but PublicPresentation replaced those fields with a generic sentence. The frontend also treated every nonterminal execution state as a pending Primary answer, producing an incorrect `FINAL_ANSWER / 等待模型输出` placeholder and a continuously increasing elapsed time.

Clarification presentation now maps the authoritative missing inputs to deterministic public prompts, tells the user that submitting through the composer continues the current task, and exposes an inline action that focuses the composer. `WAITING_INPUT` without a Primary Run keeps the answer state `IDLE`, creates no final-answer placeholder, shows a clarification-specific composer placeholder, and freezes elapsed time at the WorkItem suspension timestamp. The Incident runtime remains unstarted until the required scope is supplied.

Manual follow-up testing then exposed a Conversation boundary mismatch. Each normal goal correctly created a separate WorkItem, but the sidebar rendered every WorkItem as if it were a separate chat while all Runs intentionally shared the Conversation message timeline. A previous response shaped as `{"assistantText":"..."}` was consequently persisted and projected into the next model turn, encouraging the next unrelated answer to repeat the protocol wrapper.

The product projection now follows the intended hierarchy: one sidebar entry represents one Conversation, the middle timeline aggregates its completed WorkItem turns in chronological order, and the Inspector continues to show only the current WorkItem. Submitting another goal in the composer keeps the same Conversation and adds another stable WorkItem; only `新建对话` creates a new Conversation and isolates history.

A subsequent terminal follow-up exposed a separate command-classification defect. After a General WorkItem had already reached `CLOSED / COMPLETED / ANSWERED`, the model classified `给出代码解释` as `ADD_INPUT_TO_ACTIVE_WORK`. The intake persisted it as a `WORK_COMMAND`, and the capability registry correctly rejected that command for `GENERAL_AGENT` with `UNSUPPORTED_FOR_TARGET`. No new WorkItem was created. This was not a General Agent capability failure; it was an invalid command interpretation at the Conversation boundary.

The intake now deterministically normalizes a model-classified `ADD_INPUT_TO_ACTIVE_WORK` to `NORMAL_GOAL` when the focused WorkItem is terminal. Explicit protocol commands such as pause, resume and cancel retain command semantics. The classifier prompt also receives the focused WorkItem control, execution and outcome states and states that explanation or expansion of a completed answer is a new goal. A real two-turn Provider check created two distinct WorkItems under `workbench-p6-followup-e2e-20260721`: the initial question completed, then `给出代码解释` was persisted as `NORMAL_GOAL`, dispatched to a new General Run and completed as `CLOSED / COMPLETED / ANSWERED` without `UNSUPPORTED_FOR_TARGET`.

Command rejection presentation was hardened independently. `WORK_COMMAND_REJECTED` and `WORK_COMMAND_FAILED` now expose their safe command error code instead of defaulting to `RUN_FAILED`. An unsupported command is titled `指令未执行`, is marked non-retryable, and tells the user to submit a new follow-up in the same Conversation. This prevents a command-routing rejection from being rendered as a model execution failure.

Manual Markdown acceptance then exposed a real output truncation that had been mislabeled as success. The affected Run persisted 114 deltas and a final message with the same 3,203 characters, ending inside an unclosed Java fence at `// A需要 B：`. The frontend had not lost deltas; the fixed DeepSeek `max-tokens: 1200` limit truncated the Provider response while the string-only gateway synthesized `finishReason=final_answer`. DeepSeek max tokens are now configurable through `DEEPSEEK_MAX_TOKENS` and default to 4,096. Spring AI Provider finish metadata is retained when available; `length` and equivalent reasons fail with `MODEL_OUTPUT_TRUNCATED`. Because the current streaming adapter does not always expose a finish reason, an unclosed fenced block is also a deterministic fail-closed truncation signal.

Markdown normalization now repairs only deterministic structural defects before `marked` and DOMPurify: missing heading spaces, a heading or prose line attached to an opening fence, language identifiers attached to the first code token, closing fences attached to the last code line, heading text attached to a bold paragraph, and repeated bold list items attached to the previous item. Code blocks wrap long lines and reserve space for the copy action. A real 4,096-token Provider replay produced 5,118 characters, 16 balanced fences and a complete conclusion instead of the earlier mid-code cutoff.

For tool-capable profiles, a legacy response whose root contains only `assistantText` and optional `toolCalls` is now parsed as a protocol envelope. Empty-call final envelopes are unwrapped before delta publication and persistence; nonempty calls remain tool calls; business JSON containing additional domain fields remains untouched. Existing persisted envelopes are normalized in both frontend history and the next model context without rewriting audit data. The General prompt also states that earlier output-format instructions apply only to their original turn unless the current user repeats them.

A real Provider validation on a dedicated repaired backend used the same Chinese Spring Boot knowledge question. Three consecutive WorkItems completed as `CLOSED / COMPLETED / ANSWERED` with profile `general-agent-v1`; each exposed exactly `knowledge_search`, `skill_catalog` and `ticket_status`. A persisted `MODEL_DELTA` preceded `RUN_COMPLETED`, and one final assistant message was stored. This proves the configured API key, model and Provider route were operational during repair while malformed ToolCall output remained fail-closed.

## 1. Implemented visual system

P6 defines discrete font, type scale, spacing, radius and semantic status color tokens. The Unified Workbench uses the required Segoe UI/PingFang SC/Microsoft YaHei/system stack and JetBrains Mono/Cascadia Code/Consolas mono stack.

The primary layout is fixed at 240px left navigation, adaptive 760-820px reading content and 400px Inspector. At 1200px the Inspector becomes a drawer; at 900px the left navigation also becomes a drawer; at 620px conversation and Tool layouts collapse for mobile.

Inspector typography is overridden to a minimum 12px for metadata and 13px for normal technical content. Final answer remains 15-16px with 1.7 line height.

## 2. Interaction and accessibility

- follow-output pauses when the user scrolls away and exposes a return-to-latest action;
- real delta rendering keeps the P4 streaming cursor;
- the conversation composer uses one primary button position: send changes immediately to stop when submission begins, and the technical Inspector has no duplicate task controls;
- pause and stop are no longer presented as competing actions; the user-facing control is a single stop intent;
- stop intent is recorded immediately; once dispatch reaches `STARTING`, the command resolves the Primary Run through the stable `dispatchRequestId` even before `activeRunId` and the WorkLink are written;
- if stop is requested before Dispatch starts, the WorkItem is atomically abandoned so a late routing result cannot launch the target at all;
- a late Dispatch completion may attach the discovered Run but must preserve `CANCEL_REQUESTED` instead of overwriting the WorkItem back to `DISPATCHED / RUNNING`;
- the composer derives running, stopping and waiting feedback from local launch intent plus authoritative WorkItem control/execution state;
- text and source-code attachments are visible and removable before submission, limited to three files and 32 KB per file;
- attachment content is sent inside a bounded workbench attachment envelope while the public Conversation timeline displays only the user goal plus attachment name and size;
- answer and fenced code blocks have copy actions;
- keyboard focus uses visible outlines;
- Event Payload Drawer and responsive side drawers support Escape, Tab wrapping and focus restoration;
- controls have accessible names;
- reduced-motion disables nonessential animation and smooth scrolling;
- code/table/long-text containers scroll rather than overflow.

## 3. Automated acceptance

The P6 smoke validates token presence, all responsive breakpoints, reduced motion, code copy, answer copy, follow-output, drawer focus management and accessible control names.

Final nonvisual gates:

```text
P6 targeted backend:     32 tests, 0 failures, 0 errors
Backend mvn test:        297 tests, 0 failures, 0 errors, 11 skipped
PostgreSQL Workbench:    13 suites, 63 tests, all passed
Frontend npm test:       P3-P6 smoke suites passed
Frontend production:     vue-tsc -b and Vite build passed
Route smoke:             9/9 HTTP 200
Real Provider:           3/3 General WorkItems completed and answered
Real two-turn context:   2 WorkItems / 1 Conversation; no assistantText leakage in turn 2
Real terminal follow-up: 2 WorkItems / 1 Conversation; follow-up classified NORMAL_GOAL and answered
Real early stop:         cancellation accepted during DISPATCHING/STARTING with empty activeRunId; final CLOSED/CANCELLED/CANCELLED and no MODEL_COMPLETED
Real Markdown replay:    5,118 characters, 16 balanced fences, complete conclusion under the 4,096-token default
```

The 11 skipped backend tests remain the existing external FlowOrder, RabbitMQ or real-model gates. The P6 frontend smoke validates token presence, all responsive breakpoints, reduced motion, code copy, answer copy, follow-output, drawer focus management and accessible control names.

## 4. Fenced-code rendering repair

The manual screenshot showed that fenced Java was rendered as monochrome text and that several model-produced lines joined a `//` comment to a following declaration. The repair adds `highlight.js` through `marked-highlight`, with an explicit small language registry for Java, JSON, JavaScript/TypeScript, HTML/XML/Vue, CSS, SQL, Bash/Shell, YAML and plaintext. Unknown languages use the escaped plaintext grammar, and the existing DOMPurify boundary remains authoritative.

A later manual screenshot exposed literal `<span class="hljs-...">` text. The model and persisted assistant message still contained Java; the frontend had registered the highlighting extension on the global `marked` singleton more than once during module reload, causing already-highlighted markup to be escaped by a later pass. The renderer now owns an isolated `Marked` instance. Regression tests require real highlight spans and reject escaped `&lt;span class="hljs-..."` output.

Code blocks now preserve whitespace, use a 13px monospace font with 1.65 line height, scroll horizontally for long source lines and retain the existing copy action. A restrained light syntax palette distinguishes comments, keywords, types, declarations, strings and numbers.

`normalizeMarkdown` performs deterministic Java-fence repairs: it separates an inline comment after a completed semicolon, separates a leading `//` comment from a strongly recognizable Java declaration, assignment or method call, and inserts the conventional space after `//`. When at least three nonblank source lines are demonstrably flat (their indentation differs by no more than one space), it restores four-space nesting from balanced braces. Existing structured indentation is left unchanged.

Malformed GFM tables where a Markdown heading is directly attached to the first header row are split only when the following line is a valid table delimiter. This repairs the observed `### title|column|...|` response without treating arbitrary prose containing `|` as a table.

Regression evidence:

```text
Java keyword and class highlighting: passed
Unknown-language HTML escaping:       passed
DOMPurify malicious HTML boundary:    passed
Merged Java comment/declaration:       passed
Flat Java brace indentation:           passed
Existing Java indentation unchanged:  passed
Heading-attached GFM table repair:     passed
Valid Java remains unchanged:         passed
Text outside Java fences unchanged:   passed
Frontend npm test:                    passed
vue-tsc + Vite production build:      passed
```

## 5. General Agent contextual follow-up repair

The reported two-turn conversation was verified directly from PostgreSQL rather than inferred from the screenshot. Both WorkItems used conversation `workbench-d04d14d8-d486-475f-9aca-2a1ad0c74f69`. The persisted `agent_message` sequence was:

```text
1 USER           介绍下springboot的IOC
2 ASSISTANT_TEXT Spring Boot IoC answer
3 USER           给出Java代码解释
4 ASSISTANT_TEXT unnecessary clarification
```

The second answer itself referenced the prior IoC turn, proving that Conversation context reached the Primary Run. Creating a new terminal-follow-up WorkItem was also correct: one WorkItem retains one stable goal while both WorkItems share the Conversation timeline. The defect was model policy, not context loss.

`general-agent-v1` now requires omitted subjects to be resolved from the immediately relevant conversation when the referent is unique. It includes the exact IoC then Java-code follow-up as a boundary example, limits clarification to genuinely ambiguous referents and forbids public output of internal phrases such as “用户要求”, “根据上下文” and “决定”.

Validation:

```text
General profile + Tool boundary: 16/16 passed
Backend full regression:         297 tests, 0 failures, 11 skipped
PostgreSQL Workbench gate:       all 13 suites passed
SSE isolation rerun:             4/4 passed twice after one non-reproduced timing failure
```

## 6. Incident confirmation and scope repair

Manual acceptance exposed two cards for one Incident start: a nonfunctional “高风险工具” approval and the actual Route Preview confirmation. PostgreSQL evidence showed that the first card had no real Approval or tool call. `ROUTE_CONFIRMATION_REQUIRED` had been incorrectly projected as `APPROVAL_REQUIRED`, while the frontend separately rendered the authoritative Route Preview.

The public presentation contract now uses `CONFIRMATION_REQUIRED` for Route Preview confirmation. The conversation renderer ignores that technical presentation because the authoritative preview is rendered once from `agent_route_preview`. A tool approval card is now emitted only when a real requested Approval record is attached. The remaining card is titled “启动只读 Multi-Agent 事故调查” and displays requestIds, queue names, three domain Specialist roles plus Reviewer, expiry, version and the explicit boundary “范围与资源消耗较高；只读，不恢复”. The MQ Specialist owns both persisted dead-letter facts and Broker runtime observations; the UI no longer presents those two evidence sources as two independent Agents.

The confirmed WorkItem then failed dispatch twice with `candidateRequestIds must contain 1..100`. The router catalog and validator had accepted `batchId` as an executable scope, but no authoritative `batchId -> requestIds` resolver exists and the Incident Snapshot requires bounded requestIds. The repaired contract requires explicit requestIds before Preview. Batch-only input now remains at clarification instead of reaching confirmation and failing after human approval. The adapter also rejects an empty requestId scope before calling the Incident launcher.

Validation:

```text
Routing/adapter/presentation targeted tests: 87/87 passed
Backend full regression:                     299 tests, 0 failures, 11 skipped
Frontend npm test and production build:       passed
PostgreSQL Workbench gate:                    all 13 suites passed in isolated schema
```

The first PostgreSQL attempt against the user's active main database was invalidated because the running `8083` backend appended an event to a test WorkItem. The authoritative gate cloned schema only into `enterprise_agent_codex_incident_gate`, copied no user data, passed all suites and then dropped the temporary database.

### 6.1 Incident terminal answer and delegation completeness

The next manual run reached authoritative terminal state `CLOSED / COMPLETED / ASSESSED`, and `agent_incident.assessment_json` contained the completed Assessment, but the conversation remained at “正在确认最终结果”. This was a presentation-source mismatch: Incident Investigation has no Primary Run final assistant message. Its authoritative user-facing result is the assembled `ExecutionTree.assessment`, so waiting for an Agent Run message can never complete.

`incidentAssessmentMarkdown` now deterministically projects the authoritative Assessment into safe Markdown. `useWorkbenchConversation` treats that projected result as completed, prevents later model deltas from overwriting it, and clears it when switching WorkItems. `UnifiedWorkbench.synchronizeAnswer` applies it only for `INCIDENT_INVESTIGATION` with outcome `ASSESSED`. The P3 smoke proves one completed final answer, late-delta isolation and WorkItem-switch isolation.

The same run exposed that a Commander plan containing only `ORDER_ANALYST` passed the former 1..3 role validator. Phase 1 investigation now requires exactly the three domain roles `ORDER_ANALYST`, `INVENTORY_ANALYST` and `MQ_ANALYST`. An incomplete Commander plan fails validation and falls back to a deterministic read-only three-role plan. The MQ role continues to gather persisted dead-letter and RabbitMQ runtime facts through the existing composite capability; no fourth dead-letter Agent or new tool was introduced.

Validation after this repair:

```text
Delegation validator and core Eval: 13 tests, 0 failures
Backend full regression:           301 tests, 0 failures, 11 skipped
Frontend npm test:                 all P3-P6 smoke suites passed
Frontend production build:        vue-tsc and Vite passed
PostgreSQL Workbench gate:         13 suites, 63 tests, 0 failures, 0 skipped
Protected executor SHA-256:        136BD28ACBFE1C6CF861AE0A6AB7555236847F5169F12E0ABEA3DCE461227B35
```

The PostgreSQL gate used a schema-only clone named `enterprise_agent_codex_incident_gate`; it was dropped after the successful run.

### 6.2 Conversation Turn history and public narrative

The next manual acceptance exposed that a Conversation could contain multiple goal WorkItems while the frontend retained only the selected WorkItem's Detail, Presentation, SSE cursor and deduplication sets. The old execution data remained authoritative in PostgreSQL; it was only replaced in frontend memory.

The repair derives `turnId = WorkInput.inputId = WorkItem.sourceInputId`, caches a complete read-only snapshot per Turn and keeps realtime SSE isolated to the selected running Turn. Completed Turns retain their user message, PublicPresentation, Tool/Approval/Agent summaries and final answer. The Inspector now supports Turn, WorkItem and Conversation scopes with explicit lock/follow behavior and scope generation keys.

The middle timeline no longer renders every generic public status as a separate card. A deterministic narrative aggregator consumes only PUBLIC Presentation, compresses duplicate lifecycle wording, preserves source Presentation IDs and keeps raw WorkEvent in the Inspector. Incident lifecycle projection now emits safe domain milestones for Specialist dispatch/evidence, Reviewer checking and Assessment completion without exposing Prompt or hidden reasoning.

Detailed evidence: [Turn History Evidence](unified-agent-workbench-turn-history-evidence.md). Relationship audit: [Turn History Gap Matrix](unified-agent-workbench-turn-history-gap-matrix.md).

Validation:

```text
Backend full regression:       302 tests, 0 failures, 11 skipped
PostgreSQL Workbench gate:     13 suites, 63 tests, 0 failures, 0 skipped
Frontend tests/build:          passed
Route smoke:                   9/9 HTTP 200
```

Automated browser screenshot remains unavailable because both local browser-control runtimes fail to initialize their kernel assets. P6 remains BLOCKED pending the requested manual three-Turn browser screenshot and interaction acceptance.

### 6.3 Inline execution record details

The execution narrative interaction no longer treats a row click as a request to open the technical Inspector. Each row now has two independent actions:

- row summary / “展开详情”: toggle an inline business-semantic detail region;
- “在检查器中打开”: locate the source Presentation/WorkEvent in the right technical Inspector.

The inline detail uses only PUBLIC Presentation and public Evidence/Assessment DTOs. It shows actor/role, status, event category, time, evidence count, safe references, deterministic findings and public output summary. It does not render raw payload JSON or the stored full Specialist model report. Completed steps default collapsed; an active step defaults expanded. Expansion state is scoped to its Turn renderer.

Validation:

```text
Frontend interaction/narrative smoke: passed
TypeScript + Vite production build:    passed
PublicPresentation tests:              11/11 passed
Backend full regression:               302 tests, 0 failures
```

## 7. Browser acceptance blocker

The user's first browser screenshot supplied real manual evidence and identified the defects above. Automated repair gates now pass, but the user has not yet repeated the required 100% zoom browser acceptance against the repaired backend. P6 therefore remains BLOCKED and no checkpoint may be created.

An automated in-app browser check was attempted after the Conversation repair, but the local browser-control runtime failed while initializing its kernel assets (`path not found`). Build, route and real API evidence are therefore not represented as visual evidence. A fresh user browser acceptance remains required.

## 8. External scenario truthfulness

FlowOrder was available on port 8081. The repaired General Agent was validated against the real model through the dedicated 8084 backend, but the browser has not yet been manually rechecked. ToolCall/ToolResult, OrderCare, Incident Preview, Multi-Agent, Approval and Recovery are not claimed as post-repair browser evidence; their deterministic tests remain isolation evidence only.

## 9. Files pending P6 checkpoint

- `frontend/src/styles.css`
- `frontend/src/components/ConversationItemRenderer.vue`
- `frontend/src/components/EventPayloadDrawer.vue`
- `frontend/src/components/ExecutionInspector.vue`
- `frontend/src/components/WorkbenchConversationPanel.vue`
- `frontend/src/components/WorkbenchTaskSidebar.vue`
- `frontend/src/components/WorkbenchComposer.vue`
- `frontend/src/views/UnifiedWorkbench.vue`
- `frontend/src/composables/usePrimaryRunStream.ts`
- `frontend/src/composables/useWorkbenchConversation.ts`
- `frontend/src/composables/useWorkbenchSelection.ts`
- `frontend/src/types/conversation.ts`
- `frontend/src/types/workbench.ts`
- `frontend/src/utils/conversationItems.ts`
- `frontend/src/utils/markdown.ts`
- `frontend/src/utils/markdownNormalizer.ts`
- `frontend/src/utils/publicContent.ts`
- `frontend/src/utils/incidentAssessment.ts`
- `frontend/scripts/workbench-p4-smoke.mjs`
- `frontend/scripts/conversation-items-smoke.mjs`
- `frontend/scripts/workbench-p6-smoke.mjs`
- `frontend/package.json`
- `frontend/package-lock.json`
- `docs/reports/unified-agent-workbench-frontend-p6-final-evidence.md`
- `src/main/java/com/agent/platform/runtime/JsonAgentModelGateway.java`
- `src/main/java/com/agent/platform/runtime/DefaultAgentRuntime.java` (`executeLoop` bounded protocol retry only; `run()` unchanged)
- `src/main/java/com/agent/platform/config/AgentProperties.java`
- `src/main/java/com/agent/platform/config/GeneralAgentExecutionProfileFactory.java`
- `src/main/java/com/agent/platform/ordercare/config/AgentScenarioProfileResolver.java`
- `src/main/java/com/agent/platform/workbench/dispatch/GeneralAgentExecutionAdapter.java`
- `src/main/java/com/agent/platform/workbench/application/AgentRunWorkCommandAdapter.java`
- `src/main/java/com/agent/platform/workbench/application/WorkCommandHandler.java`
- `src/main/java/com/agent/platform/workbench/persistence/JdbcDispatchStore.java`
- `src/main/java/com/agent/platform/workbench/persistence/JdbcWorkbenchStore.java`
- `src/main/java/com/agent/platform/workbench/application/DefaultWorkCommandClassifier.java`
- `src/main/java/com/agent/platform/workbench/application/UnifiedWorkIntakeService.java`
- `src/main/java/com/agent/platform/workbench/presentation/PublicPresentationService.java`
- `src/main/java/com/agent/platform/workbench/presentation/PublicPresentationKind.java`
- `src/main/java/com/agent/platform/workbench/target/ExecutionTargetRegistry.java`
- `src/main/java/com/agent/platform/workbench/application/RoutePolicyValidator.java`
- `src/main/java/com/agent/platform/workbench/dispatch/IncidentInvestigationExecutionAdapter.java`
- `src/main/java/com/agent/platform/ordercare/incident/application/DelegationPlanValidator.java`
- `src/main/java/com/agent/platform/ordercare/incident/application/SafeDelegationPlanFactory.java`
- `src/main/java/com/agent/platform/ordercare/incident/application/IncidentExecutionProfileFactory.java`
- `src/main/java/com/agent/platform/ordercare/incident/application/IncidentInvestigationOrchestrator.java`
- `src/main/resources/application.yaml`
- `src/test/java/com/agent/platform/config/GeneralAgentExecutionProfileFactoryTests.java`
- `src/test/java/com/agent/platform/ordercare/config/OrderCareExecutionProfileFactoryTests.java`
- `src/test/java/com/agent/platform/runtime/DefaultAgentRuntimeStateTests.java`
- `src/test/java/com/agent/platform/runtime/ToolResultBoundaryTests.java`
- `src/test/java/com/agent/platform/workbench/application/UnifiedWorkEventProjectorPostgresIT.java`
- `src/test/java/com/agent/platform/workbench/application/AgentRunWorkCommandAdapterTests.java`
- `src/test/java/com/agent/platform/workbench/dispatch/ExecutionAdapterUnitTests.java`
- `src/test/java/com/agent/platform/workbench/persistence/JdbcDispatchStorePostgresIT.java`
- `src/test/java/com/agent/platform/workbench/persistence/JdbcRoutingStorePostgresIT.java`
- `src/test/java/com/agent/platform/workbench/presentation/PublicPresentationServiceTests.java`
- `src/test/java/com/agent/platform/workbench/application/M1BRoutingUnitTests.java`
- `src/test/java/com/agent/platform/workbench/eval/WorkbenchM3DPolicyEvalTests.java`
- `src/test/java/com/agent/platform/workbench/eval/WorkbenchRoutingSafetyGateTests.java`
- `src/test/java/com/agent/platform/ordercare/incident/application/DelegationPlanValidatorTests.java`
- `src/test/java/com/agent/platform/ordercare/incident/eval/IncidentCommandCoreEvalTests.java`

## 10. Process cleanup and checkpoint status

The repair backend used port `8084` because the user's IDEA backend already occupied `8083`; it was stopped after the real Provider checks. The isolated PostgreSQL gate database was dropped after its 13-suite run. The preview on `4173` remains available for manual acceptance. The user processes on `8083` and `5173` were not stopped or modified. The `8083` process was started before this repair and must be restarted from IDEA before manual retest.

## 11. Pre-M4 implementation checkpoint

The M4 isolation instruction dated 2026-07-21 authorizes a local P6 implementation checkpoint before final manual acceptance. This checkpoint preserves the current implementation and its verified automated state; it does not change the P6 product result above. P6 remains `BLOCKED` pending the required manual browser retest and screenshots.

The pre-checkpoint gates were rerun from the P5 baseline working tree:

- P6 targeted backend suites: passed;
- full backend `mvn test`: passed on the bounded rerun with 303 tests, 0 failures and 11 environment-gated skips;
- the first full-suite attempt exposed one pre-existing 100 ms local HTTP timing failure in `HttpRabbitMqObservationClientTests.retriesTimeoutExactlyOnce`; the isolated test and bounded full-suite rerun passed without code changes;
- PostgreSQL: all 16 discovered `*PostgresIT` suites passed sequentially against a schema-only isolated clone, including the 13 Workbench suites plus continuation and Incident stores;
- frontend `npm test`: passed;
- `vue-tsc -b` and Vite production build: passed;
- Workbench route HTTP smoke: all 10 declared entries returned the application shell;
- `git diff --check`: passed before staging.

No manual browser result is inferred from these automated gates. The protected `DefaultStreamingAgentExecutor.java` working-tree change remains excluded from the checkpoint.
