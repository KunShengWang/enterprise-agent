# Incident Scope Discovery V1 Evidence

Updated: 2026-07-21 (Asia/Shanghai)

## Overall status

- Phase 0: PASSED
- M4-A FlowOrder contracts: PASSED
- M4-B discovery and snapshot: PASSED
- M4-C routing, confirmation and Incident integration: PASSED
- M4-D frontend and E2E: NOT STARTED at this checkpoint
- Manual browser acceptance: PENDING
- Push: not performed

This report distinguishes unit/integration tests, isolated real databases, real HTTP, mocks, and manual acceptance. A mock or stub result is never described as cross-service E2E evidence.

## Repository provenance

| Repository | Path | M4 branch | M4 start HEAD |
|---|---|---|---|
| enterprise-agent | `D:\\JDK\\IDEA\\java_reinforcement_learning\\enterprise-agent` | `feature/incident-scope-discovery-v1` | `fc4e907c7a39fd08efdee24c121897f338791929` |
| FlowOrder | `D:\\JDK\\IDEA\\java_reinforcement_learning\\floworder` | `feature/incident-scope-discovery-v1` | `d359b55df8433e34c950b7afa616cc2d517c4ffd` |

### Phase 0 checkpoints

- enterprise-agent P6 implementation checkpoint: `fc4e907c7a39fd08efdee24c121897f338791929`
- P6 status at M4 start: implementation and automated gates preserved; manual browser acceptance remains pending, so P6 is not represented as fully passed.
- FlowOrder pre-M4 checkpoint branch: `checkpoint/pre-m4-recovery-fixture`
- FlowOrder recovery checkpoint: `d9ddc7c5bf1b33443c24d0d7d7446abe22271191`
- FlowOrder fixture checkpoint: `268c4611b2c982df904b18936d792fd904e3c4d2`
- Recovery/fixture commits were not merged into the M4 branch.

## M4-A FlowOrder scope contracts

Checkpoint: `783d14fb4287d7800d3fe95b2067254cc20942f8`

### APIs and ownership

- `POST /internal/incidents/scopes/order-candidates` is implemented by order-service and reads order-owned facts.
- `POST /internal/incidents/scopes/resource-enrichment` is implemented by resource-service and reads reservation, deduction, stock, and persisted dead-letter facts.
- Both endpoints require `X-FlowOrder-Internal-Token`; missing or incorrect credentials fail closed.
- `fo_mq_dead_letter.dead_queue` is the only authoritative DLQ queue name. No queue name is inferred from naming conventions.
- Resource enrichment classifies identifier relationships as `STRONG`, `WEAK`, or `MISSING`.
- The implementation preserves actual ownership: `fo_reservation_request` is resource-service-owned in this repository, while order-service queries `fo_reservation_order` only.

### SQL and index evidence

The M4 query required this index:

```sql
idx_incident_scope_updated_id_status (updated_at, id, status)
```

Real MySQL `EXPLAIN` changed from a full scan with filesort to a range access using index condition and filtering. The business row count remained `3650` before and after the read-only HTTP validation.

### M4-A validation

- FlowOrder targeted controller/service tests: passed.
- Isolated real-MySQL module regression: passed.
- Real HTTP calls against order-service `18082` and resource-service `18081`: passed.
- Order candidate response: HTTP 200, one candidate.
- Resource enrichment response: HTTP 200, release state `UNRELEASED`, one dead letter, queue `floworder.order.state.dlq`, relation `STRONG`.
- Wrong internal token: HTTP 401.
- RabbitMQ was not required for M4-A; queue names came from persisted authoritative dead-letter facts.

M4-C exposed and closed one backward-compatible contract gap: a standalone `deadLetterId` was a valid product anchor but was not present in the enrichment request. FlowOrder follow-up checkpoint `38bdc3efcd63f1335bb6e6d6b85bcf74f6d945b3` adds bounded positive `deadLetterIds`, resolves their persisted `biz_key` to a real deduction when possible, and never promotes a missing/weak relation to strong. The resource-service regression produced 21 Surefire reports with zero failures or errors.

## M4-B enterprise-agent discovery and snapshot

### Components

- `IncidentScopeDiscoveryCoordinator`
- `IncidentScopeDiscoveryStore` and `JdbcIncidentScopeDiscoveryStore`
- `IncidentTimeRangeResolver`
- `IncidentScopeCandidateAssembler`
- `IncidentScopePolicy`
- `IncidentScopeSnapshot`
- fixed-endpoint `FlowOrderScopeDiscoveryClient`
- strongly typed candidate identifiers and source references

The client accepts only configured FlowOrder base URLs and fixed internal paths. It does not expose arbitrary URL or SQL capabilities to the model.

### Time normalization

Java, not the model, converts user expressions into absolute instants:

| Expression | Product rule |
|---|---|
| `昨晚` | previous local day 18:00 through current local day 06:00, capped at current time |
| `今天` | local midnight through current time |
| `最近/过去 N 小时` | 1 through 24 hours before current time |
| ISO `start/end` | parsed in the requested timezone when no offset is present |

The maximum automatic range is 24 hours. An invalid user timezone falls back to the configured `Asia/Shanghai` timezone and the snapshot records that fallback for preview.

### Supported anomaly types

- `ORDER_TIMEOUT_INVENTORY_UNRELEASED`
- `ORDER_CANCELLED_INVENTORY_UNRELEASED`
- `DEAD_LETTER_PENDING`
- `ORDER_INVENTORY_STATE_MISMATCH`

### Provenance and fingerprint

- Scope-discovered identifiers use `SERVER_RESOLVED_FROM_SCOPE_DISCOVERY`.
- Each identifier carries type, value, source system, source type, source ID, observed time, and resolution source.
- Criteria and candidates have deterministic SHA-256 digests.
- Candidates are normalized, deduplicated, and stably sorted before fingerprinting.
- User confirmation is bound to `snapshotId + version + candidateFingerprint`.

### Snapshot persistence

`agent_incident_scope_snapshot` is a single-row PostgreSQL snapshot with JSONB criteria, candidates, and source health. It stores tenant/principal/work-item ownership, unique `discoveryRequestId`, lifecycle status, version, lease owner/time, fencing token, expiry, confirmation identity/time, and failure code.

Lifecycle:

```text
NEW -> DISCOVERING -> CANDIDATES_READY -> WAITING_CONFIRMATION -> CONFIRMED
                     |                                      |
                     +-> FAILED                             +-> EXPIRED
```

The store provides:

- idempotent create/load by `discoveryRequestId`;
- tenant, principal, and WorkItem isolation;
- competing-instance claim rejection;
- expired-lease takeover with fencing-token increment;
- stale-owner fencing rejection;
- idempotent repeated confirmation;
- fingerprint and version tamper rejection;
- persisted expiration.

The snapshot is an orchestration checkpoint, not the FlowOrder business fact source.

### M4-B test evidence

Targeted tests passed:

- `IncidentTimeRangeResolverTests`
- `IncidentScopeCandidateAssemblerTests`
- `JdbcIncidentScopeDiscoveryStorePostgresIT`

Full Maven regression passed before the checkpoint.

Seventeen isolated real-PostgreSQL suites passed against `enterprise_agent_m4b_full_gate`:

1. `JdbcWorkbenchStorePostgresIT`
2. `JdbcRoutingStorePostgresIT`
3. `JdbcDispatchStorePostgresIT`
4. `DispatchTargetIdempotencyPostgresIT`
5. `WorkCommandHandlerPostgresIT`
6. `UnifiedWorkEventProjectorPostgresIT`
7. `UnifiedWorkExecutionTreePostgresIT`
8. `UnifiedWorkHistoryReplayPostgresIT`
9. `UnifiedWorkEventStreamPostgresIT`
10. `UnifiedWorkbenchControllerPostgresIT`
11. `WorkbenchTenantIsolationPostgresIT`
12. `PublicPresentationPostgresIT`
13. `HierarchicalBudgetPostgresIT`
14. `AgentContinuationRuntimePostgresIT`
15. `JdbcIncidentStorePostgresIT`
16. `JdbcIncidentRecoveryPlanStorePostgresIT`
17. `JdbcIncidentScopeDiscoveryStorePostgresIT`

The isolated database was dropped after the suite completed.

## M4-C routing, confirmation and Incident integration

### Resolution actions

The four frozen execution targets remain unchanged. `INCIDENT_INVESTIGATION` now has a deterministic Java preflight with three internal actions:

| Action | Condition | Result |
|---|---|---|
| `DIRECT_EXECUTION` | explicit, source-valid requestIds are already present | existing route validation and Incident adapter path |
| `DISCOVER_SCOPE` | no internal IDs, but supported business anomaly plus time/order/deduction/dead-letter anchor exists | FlowOrder read-only discovery, immutable Snapshot, preview and explicit confirmation |
| `CLARIFY` | no searchable anchor or no supported anomaly | asks only for time, order number, or a clearer business phenomenon |

The model does not receive a discovery tool and cannot generate internal IDs, SQL, URLs, queue names, or confirmation. Java maps the supported business phenomenon and normalizes time. Discovered identifiers are marked `SERVER_RESOLVED_FROM_SCOPE_DISCOVERY`.

### Preview and confirmation binding

The route preview includes:

- exact absolute time range and timezone;
- anomaly types and source health;
- request/order/deduction/dead-letter/queue counts and identifiers;
- safe candidate summaries and provenance;
- `scopeSnapshotId`, Snapshot version, criteria digest, and candidate fingerprint;
- truncation state and the read-only/no-Recovery boundary.

Confirmation validates the route preview identity, version, validated-input digest, and scope digest. It then confirms the Snapshot using the separately bound Snapshot version and candidate fingerprint before moving the WorkItem to `READY_TO_DISPATCH`. Repeated confirmation is idempotent. An expired or changed Snapshot is rejected and cannot silently reuse the old approval.

### Existing Incident path reuse

- `IncidentInvestigationExecutionAdapter` remains the execution adapter.
- `dispatchRequestId` remains the idempotency key; discovery does not create a second Incident or PRIMARY link.
- `IncidentSnapshot` stores the discovery Snapshot ID, candidate fingerprint, and bounded provenance summary.
- Explicit requestId input continues through the original route without discovery.
- queueNames are no longer globally required. With no authoritative queue, Java creates only Order and Inventory Specialist tasks plus Reviewer. MQ Specialist is added only when persisted dead-letter facts provide an authoritative queue.
- No Recovery action is available from discovery or investigation.

### Public events

The WorkItem event stream and PublicPresentation projection cover:

- `SCOPE_DISCOVERY_STARTED`
- `ORDER_CANDIDATES_DISCOVERED`
- `RESOURCE_ENRICHMENT_COMPLETED`
- `DEAD_LETTERS_RESOLVED`
- `QUEUES_RESOLVED`
- `SCOPE_DISCOVERY_COMPLETED`
- `SCOPE_DISCOVERY_FAILED`
- `SCOPE_CONFIRMATION_REQUIRED`
- `SCOPE_CONFIRMED`
- `SCOPE_EXPIRED`
- `SCOPE_EXPANSION_SUGGESTED`

Public summaries expose business counts and safe references only. SQL, URLs, credentials, prompts, hidden reasoning, and raw database payloads remain outside the public timeline.

### M4-C tests

Targeted tests passed for:

- explicit requestIds preserving the original path;
- fuzzy business conditions entering discovery;
- no-anchor input entering clarification;
- Snapshot version/fingerprint confirmation binding;
- queue-optional direct validation;
- two Specialists without an authoritative queue and MQ inclusion with a queue;
- safe public discovery progress;
- existing execution adapter idempotency.

The full Maven regression passed. Seventeen isolated real-PostgreSQL suites passed after M4-C, including routing, dispatch, command, event/replay/SSE, tenant isolation, presentation, budget, continuation, Incident, recovery-plan, and scope Snapshot stores.

During the fresh-database gate, `UnifiedWorkIntakeService` exposed a pre-existing initialization-order defect: RoutingStore extended Workbench tables before the base owner initialized them. The unified intake now initializes the Workbench base schema before persisting the first input. `WorkCommandHandlerPostgresIT` then passed on a fresh isolated database and in the 17-suite gate.

## E2E truth table

| Boundary | Current evidence |
|---|---|
| Real MySQL | M4-A passed |
| Real PostgreSQL | M4-B passed |
| Real cross-service HTTP | M4-A endpoints passed independently; complete M4-D chain pending |
| RabbitMQ | Not exercised; persisted `dead_queue` only |
| Real model | Not exercised for M4-B |
| Frontend smoke | Not started for M4-D |
| Manual browser | Pending |
| Stub/mock | Unit tests use controlled collaborators where appropriate |

## Protected constraints

- `DefaultAgentRuntime.run()` was not modified by M4-B.
- `src/main/java/com/agent/platform/stream/DefaultStreamingAgentExecutor.java` remains an excluded pre-existing workspace modification.
- Expected protected-file SHA-256: `136BD28ACBFE1C6CF861AE0A6AB7555236847F5169F12E0ABEA3DCE461227B35`.
- No fifth `ExecutionTarget`, arbitrary SQL/URL tool, Recovery execution, or external alert integration was added.

## Remaining work

- M4-D: Scope Preview UI, candidate details, frontend gates, cross-service E2E, refresh/replay evidence.
- Manual browser acceptance remains pending and will not be fabricated.
