# Unified Agent Workbench Frontend P4 Evidence

> Date: 2026-07-21
> Baseline: P3 checkpoint `c2271e5b2bacd1011f5c1272fce78fe0df7617ac`
> Scope: Conversation Rendering & Streaming UX
> Result: PASSED

## 1. Public rendering contract

The Conversation ViewModel preserves the authoritative `PublicPresentationKind`. The renderer can now distinguish task understanding, standard process, explicit execution plan, compact action state, tool activity, delegation, approval, recovery and final answer without consulting Raw WorkEvent payloads.

The middle timeline continues to reject `INTERNAL` and `INSPECTOR_ONLY` records. Hidden reasoning, system prompts, internal route reasons, raw ToolCall envelopes and Runtime payloads are not rendering inputs.

## 2. Message semantics

- User input remains escaped plain text.
- Task understanding and route summaries use a restrained collapsible public summary.
- Standard process and explicit execution plan retain distinct backend titles.
- Action start/completion uses compact timeline styling instead of large cards.
- Tool cards use only backend-authorized `displayName`, `actionSummary`, `publicArguments`, `resultSummary`, `resultCount`, `durationMs` and `attemptLabel`.
- Approval Presentation and Approval DTO still merge into one interactive card.
- Final answer uses document layout and one copy action, without a wrapping answer card.

## 3. Streaming UX

The P3 answer state machine is now visible:

```text
WAITING     -> waiting for model output
STREAMING   -> real MODEL_DELTA text plus lightweight cursor
FINALIZING  -> confirming authoritative final result
COMPLETED   -> persisted Primary ASSISTANT_TEXT
FAILED      -> explicit failure state
CANCELLED   -> explicit cancellation state
```

There is no synthetic character animation. Persisted text replaces the live buffer under the same answer entry identity, so correction does not produce a second answer.

## 4. Markdown boundary

`marked` remains configured with GFM and line breaks. `DOMPurify` remains the only HTML rendering boundary and explicitly forbids active media/form/frame tags, style and event-handler attributes.

The new finite normalizer repairs only block syntax outside fenced code:

- `###标题` to `### 标题`;
- `-列表` to `- 列表`;
- missing blank lines before recognized heading/list/quote/table blocks.

Complete JSON documents, fenced code, JSON code blocks and URLs are unchanged. The normalizer does not rewrite business content.

## 5. Protocol defense

The backend remains authoritative for suppressing ToolCall protocol output. The frontend adds a final persisted-message guard:

- explicit `assistantText + toolCalls` envelopes are rejected;
- top-level `toolCalls` arrays are rejected;
- malformed explicit envelopes are rejected;
- business JSON with a string field named `toolCalls` remains valid content.

## 6. Automated tests

Frontend smoke covers:

- live delta, finalizing, persisted correction and one final answer;
- Child delta and Child terminal isolation;
- failed/cancelled answer states;
- Chinese and multilevel headings;
- lists, GFM tables, inline code and block quotes;
- fenced Java and JSON code unchanged;
- business JSON and URLs unchanged;
- long text and empty text;
- real DOMPurify removal of script, image, event handler and JavaScript URL content;
- ToolCall envelope and malformed envelope rejection;
- Tool public field mapping;
- approval, tool and plan deduplication;
- hidden/Inspector Presentation exclusion.

Build gates:

```text
npm test      PASSED
vue-tsc -b    PASSED
vite build    PASSED
```

## 7. Files

- `frontend/src/utils/markdownNormalizer.ts`
- `frontend/src/utils/markdown.ts`
- `frontend/src/utils/publicContent.ts`
- `frontend/src/types/conversation.ts`
- `frontend/src/utils/conversationItems.ts`
- `frontend/src/composables/useWorkbenchConversation.ts`
- `frontend/src/components/ConversationItemRenderer.vue`
- `frontend/src/styles.css`
- `frontend/scripts/conversation-items-smoke.mjs`
- `frontend/scripts/workbench-p4-smoke.mjs`
- `frontend/package.json`
- `frontend/package-lock.json`
- `docs/reports/unified-agent-workbench-frontend-p4-evidence.md`

P5 Inspector restructuring has not started in this checkpoint.
