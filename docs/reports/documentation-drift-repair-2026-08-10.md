# Documentation Drift Repair — 2026-08-10

## 1. Scope

This repair aligns current-facing documentation with committed implementation baseline:

```text
b6207a48d6db295e6d7c6e8c054c3d1df5d952db
```

Branch at audit time:

```text
feature/incident-scope-discovery-v1
```

The worktree contained pre-existing uncommitted Java, test and learning-note changes. They were not edited and are not represented as stable product capability.

## 2. Main drift found

1. README and architecture stopped at the direct single-Agent Runtime and OrderCare M3, omitting Unified Workbench, Incident Scope Discovery and current Multi-Agent execution.
2. Documentation still described `JsonAgentModelGateway` as the default and native Tool Calling as future work, while `NativeToolCallingAgentModelGateway` is the committed default.
3. Frontend documentation described the old Runtime learning console instead of `/` Unified Agent Workbench, PublicPresentation, Primary Run delta and Execution Inspector.
4. API documentation omitted the unified input, WorkItem, Presentation, confirmation, command and execution-tree APIs.
5. Build instructions omitted the Workbench feature flags, Incident flags, FlowOrder Scope token and the distinction between PowerShell environment variables and Spring profiles.
6. OrderCare status stopped at single-case M3 and used “M4” for deployment hardening, colliding with the later M4 Incident Scope Discovery milestone.
7. Interview and gap documents incorrectly claimed no native Tool Calling adapter and used obsolete test counts.
8. Frozen blueprints and progress reports lacked an explicit historical-status banner, so old “only design / next step” text looked current.

## 3. Repaired current-facing documents

- `README.md`
- `docs/documentation-index.md`
- `docs/architecture.md`
- `docs/api-guide.md`
- `docs/build-and-run.md`
- `docs/frontend-learning-console.md`
- `docs/learning-guide.md`
- `docs/interview-guide.md`
- `docs/remaining-gaps.md`
- `docs/ordercare-implementation-status.md`
- `docs/future-scenario-backlog.md`
- `docs/design-decisions.md`
- `docs/unified-agent-workbench-interview-runbook.md`

## 4. Historical documents annotated, not rewritten

- `docs/enterprise-agent-master-blueprint.md`
- `docs/ordercare-incident-command-v1-design.md`
- `docs/unified-agent-workbench-v1-design.md`
- `docs/reports/unified-agent-workbench-progress.md`

Their original stage requirements and test counts remain historical evidence. New banners point readers to current state instead of rewriting checkpoint history.

## 5. Current implementation conclusions reflected

- Four stable Workbench ExecutionTargets; no fifth discovery target.
- Input-first persistence, WorkCommand separation, WorkItem three-dimensional state, idempotent routing/dispatch and terminal projection.
- Native Provider Tool Calling as default; JSON mode as compatibility.
- Runtime-controlled capability/profile/visibility/guardrail/approval/claim boundaries.
- Controlled, read-only SubAgent Tools with bounded parallel execution and evidence-based Reviewer validation.
- Incident Scope Discovery through fixed FlowOrder read-only APIs, Snapshot version/fingerprint/TTL and explicit confirmation.
- Bounded time parsing only; no claim of arbitrary natural-language time understanding.
- Production authentication, migration governance, sandbox, alerting and capacity/SLO evidence remain incomplete.

## 6. Validation

- `git diff --check`: passed.
- Markdown relative file-link scan across README and `docs/**/*.md`: passed.
- Mojibake scan on all modified current-facing documents: passed.
- Stale-claim scan for default JSON Tool Calling, “design only”, old M4 deployment naming and obsolete test count: resolved in current-facing documents.

No Maven or frontend build was required because this repair changes Markdown only. No commit or push was performed.
