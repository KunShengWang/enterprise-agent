package com.agent.platform.workbench.presentation;

import com.agent.platform.runtime.AgentCapabilityRegistry;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.RoutingDecisionRecord;
import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.model.WorkEventType;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchNotFoundException;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class PublicPresentationService {

    private static final int MAX_SOURCE_EVENTS = 10_000;
    static final int PRESENTATION_SLOTS_PER_EVENT = 10;
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "secret", "token", "authorization", "cookie", "apikey", "api_key",
            "sql", "url", "headers", "prompt", "systemprompt", "reasoning", "chainofthought", "stacktrace");

    private final WorkbenchStore workbenchStore;
    private final RoutingStore routingStore;
    private final AgentCapabilityRegistry capabilityRegistry;
    private final PublicExecutionCatalog executionCatalog;

    public PublicPresentationService(WorkbenchStore workbenchStore,
                                     RoutingStore routingStore,
                                     AgentCapabilityRegistry capabilityRegistry,
                                     PublicExecutionCatalog executionCatalog) {
        this.workbenchStore = workbenchStore;
        this.routingStore = routingStore;
        this.capabilityRegistry = capabilityRegistry;
        this.executionCatalog = executionCatalog;
    }

    public List<PublicPresentation> publicTimeline(AuthenticatedPrincipal principal, String workItemId,
                                                   long afterSequence, int limit) {
        return timeline(principal, workItemId, afterSequence, limit, false);
    }

    public List<PublicPresentation> inspectorTimeline(AuthenticatedPrincipal principal, String workItemId,
                                                      long afterSequence, int limit) {
        return timeline(principal, workItemId, afterSequence, limit, true);
    }

    private List<PublicPresentation> timeline(AuthenticatedPrincipal principal, String workItemId,
                                              long afterSequence, int limit, boolean includeInspector) {
        AgentWorkItem work = workbenchStore.findWorkItem(principal, workItemId)
                .orElseThrow(() -> new WorkbenchNotFoundException("work item not found"));
        RoutingDecisionRecord routing = routingStore.findEffectiveRouting(principal, workItemId).orElse(null);
        long sourceAfter = afterSequence < 0 ? -1 : Math.max(-1, afterSequence / 10 - 1);
        List<WorkEvent> events = workbenchStore.loadEvents(
                principal, workItemId, sourceAfter, MAX_SOURCE_EVENTS);
        List<PublicPresentation> projected = project(work, routing, events);
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return projected.stream()
                .filter(item -> item.sequence() > afterSequence)
                .filter(item -> item.visibility() == PublicVisibility.PUBLIC
                        || includeInspector && item.visibility() == PublicVisibility.INSPECTOR_ONLY)
                .sorted(java.util.Comparator.comparingLong(PublicPresentation::sequence))
                .limit(safeLimit)
                .toList();
    }

    List<PublicPresentation> project(AgentWorkItem work, RoutingDecisionRecord routing, List<WorkEvent> events) {
        List<PublicPresentation> result = new ArrayList<>();
        for (WorkEvent event : events) {
            result.addAll(projectEvent(work, routing, events, event));
        }
        return List.copyOf(result);
    }

    private List<PublicPresentation> projectEvent(AgentWorkItem work, RoutingDecisionRecord routing,
                                                  List<WorkEvent> events, WorkEvent event) {
        List<PublicPresentation> result = new ArrayList<>();
        String phase = normalize(event.phase());
        if (event.eventType() == WorkEventType.ROUTING_STARTED) {
            result.add(item(work, event, 0, PublicPresentationKind.ACTION_STARTED,
                    PublicPresentationStatus.ACTIVE, "正在理解目标", "系统正在判断适合的执行方式。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
            return result;
        }
        if (event.eventType() == WorkEventType.ROUTING_DECIDED) {
            String targetId = work.activeExecutionTarget();
            if (targetId == null || targetId.isBlank()) targetId = text(event.payload().get("targetId"));
            if (targetId.isBlank() && routing != null
                    && "REQUIRE_CLARIFICATION".equals(text(routing.validation().get("disposition")))) {
                return result;
            }
            PublicExecutionCatalog.Definition definition = executionCatalog.definition(targetId);
            String summary = publicRoutingSummary(routing, definition.label());
            result.add(item(work, event, 0, PublicPresentationKind.TASK_UNDERSTANDING,
                    PublicPresentationStatus.COMPLETED, "已理解任务", summary, List.of(),
                    targetDetail(definition.label()), PublicVisibility.PUBLIC));
            result.add(item(work, event, 1, PublicPresentationKind.ROUTE_SUMMARY,
                    PublicPresentationStatus.COMPLETED, "执行方式", "系统已选择 " + definition.label() + "。",
                    List.of(), targetDetail(definition.label()), PublicVisibility.PUBLIC));
            result.add(item(work, event, 2, PublicPresentationKind.STANDARD_PROCESS,
                    PublicPresentationStatus.COMPLETED, "标准流程", "这是该执行方式的标准流程，不代表模型的隐藏推理。",
                    definition.standardProcess(), targetDetail(definition.label()), PublicVisibility.PUBLIC));
            List<String> publicPlan = publicPlan(event.payload().get("publicPlan"));
            if (!publicPlan.isEmpty()) {
                result.add(item(work, event, 3, PublicPresentationKind.EXECUTION_PLAN,
                        PublicPresentationStatus.COMPLETED, "执行计划", "Agent 已提交可公开的执行计划。",
                        publicPlan, targetDetail(definition.label()), PublicVisibility.PUBLIC));
            }
            return result;
        }
        if (event.eventType() == WorkEventType.CLARIFICATION_REQUIRED) {
            List<String> prompts = clarificationPrompts(routing);
            return List.of(item(work, event, 0, PublicPresentationKind.WAITING_FOR_USER,
                    PublicPresentationStatus.WAITING, "需要补充信息",
                    "请在下方输入框补充以下信息，提交后系统会继续当前任务。",
                    prompts, new PublicPresentationDetail("", "ROUTING_DECISION",
                            routing == null ? "" : routing.decisionId(), null,
                            Map.of("inputMode", "ADD_INPUT")), PublicVisibility.PUBLIC));
        }
        if (event.eventType() == WorkEventType.ROUTE_CONFIRMATION_REQUIRED) {
            return List.of(item(work, event, 0, PublicPresentationKind.CONFIRMATION_REQUIRED,
                    PublicPresentationStatus.WAITING, "需要确认调查范围", "请确认预览中的目标和只读边界后再启动调查。",
                    List.of(), reference("ROUTE_PREVIEW", text(event.payload().get("previewId"))), PublicVisibility.PUBLIC));
        }
        if (event.eventType() == WorkEventType.SCOPE_DISCOVERY_STARTED) {
            return List.of(scopeItem(work, event, PublicPresentationKind.ACTION_STARTED,
                    PublicPresentationStatus.ACTIVE, "正在发现事故范围",
                    "系统正在根据业务时间和异常现象查询 FlowOrder 权威只读事实。"));
        }
        if (event.eventType() == WorkEventType.ORDER_CANDIDATES_DISCOVERED) {
            return List.of(scopeItem(work, event, PublicPresentationKind.ACTION_COMPLETED,
                    PublicPresentationStatus.COMPLETED, "已发现候选订单",
                    "已发现 " + text(event.payload().get("candidateCount")) + " 条候选记录。"));
        }
        if (event.eventType() == WorkEventType.RESOURCE_ENRICHMENT_COMPLETED) {
            return List.of(scopeItem(work, event, PublicPresentationKind.ACTION_COMPLETED,
                    PublicPresentationStatus.COMPLETED, "已核对库存释放状态",
                    "系统已补充库存扣减、释放状态和来源健康度。"));
        }
        if (event.eventType() == WorkEventType.DEAD_LETTERS_RESOLVED) {
            return List.of(scopeItem(work, event, PublicPresentationKind.ACTION_COMPLETED,
                    PublicPresentationStatus.COMPLETED, "已关联持久化死信",
                    "已关联 " + text(event.payload().get("deadLetterCount")) + " 条持久化死信记录。"));
        }
        if (event.eventType() == WorkEventType.QUEUES_RESOLVED) {
            return List.of(scopeItem(work, event, PublicPresentationKind.ACTION_COMPLETED,
                    PublicPresentationStatus.COMPLETED, "已识别相关 DLQ",
                    "已从持久化事实中识别 " + text(event.payload().get("queueCount")) + " 个权威队列。"));
        }
        if (event.eventType() == WorkEventType.SCOPE_DISCOVERY_COMPLETED) {
            return List.of(scopeItem(work, event, PublicPresentationKind.ACTION_COMPLETED,
                    PublicPresentationStatus.COMPLETED, "候选事故范围已生成",
                    "候选范围已冻结，启动调查前仍需明确确认。"));
        }
        if (event.eventType() == WorkEventType.SCOPE_CONFIRMATION_REQUIRED) {
            return List.of(scopeItem(work, event, PublicPresentationKind.ACTION_COMPLETED,
                    PublicPresentationStatus.COMPLETED, "候选范围等待确认",
                    "请在范围预览中确认后启动只读 Multi-Agent 调查。"));
        }
        if (event.eventType() == WorkEventType.SCOPE_CONFIRMED) {
            return List.of(scopeItem(work, event, PublicPresentationKind.ACTION_COMPLETED,
                    PublicPresentationStatus.COMPLETED, "事故范围已确认",
                    "已锁定该 Snapshot 版本和指纹，正在启动调查。"));
        }
        if (event.eventType() == WorkEventType.SCOPE_DISCOVERY_FAILED
                || event.eventType() == WorkEventType.SCOPE_EXPIRED) {
            return List.of(scopeItem(work, event, PublicPresentationKind.ERROR,
                    PublicPresentationStatus.FAILED, "事故范围发现未完成",
                    "请调整时间或业务条件后重试。"));
        }
        if (event.eventType() == WorkEventType.DISPATCH_STARTED) {
            return List.of(item(work, event, 0, PublicPresentationKind.ACTION_STARTED,
                    PublicPresentationStatus.ACTIVE, "正在启动执行", "系统正在创建或恢复目标执行。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
        }
        if (event.eventType() == WorkEventType.DISPATCH_RECONCILED) {
            String summary = "DISPATCHING".equals(phase)
                    ? "执行结果暂时未知，系统正在使用原请求标识进行对账。"
                    : "执行目标已通过原请求标识完成对账。";
            return List.of(item(work, event, 0, PublicPresentationKind.RECOVERY,
                    "DISPATCHING".equals(phase) ? PublicPresentationStatus.ACTIVE : PublicPresentationStatus.COMPLETED,
                    "执行对账", summary, List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
        }
        if (event.eventType() == WorkEventType.EXECUTION_DISPATCHED) {
            return List.of(item(work, event, 0, PublicPresentationKind.ACTION_COMPLETED,
                    PublicPresentationStatus.COMPLETED, "执行已启动", "目标执行已建立并开始处理任务。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
        }
        if (event.eventType() == WorkEventType.ROUTING_FAILED) {
            return List.of(item(work, event, 0, PublicPresentationKind.ERROR,
                    PublicPresentationStatus.FAILED, "执行遇到问题", "请求未能继续处理，请检查任务状态或稍后重试。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
        }
        if (event.eventType() == WorkEventType.WORK_COMMAND_FAILED
                || event.eventType() == WorkEventType.WORK_COMMAND_REJECTED) {
            String code = text(event.payload().get("code"));
            String summary = "UNSUPPORTED_FOR_TARGET".equals(code)
                    ? "该输入未应用到已结束的任务。请将它作为同一对话中的新追问提交。"
                    : "该指令没有改变当前任务状态，请检查任务状态后重新提交。";
            return List.of(item(work, event, 0, PublicPresentationKind.ERROR,
                    PublicPresentationStatus.FAILED, "指令未执行", summary, List.of(),
                    new PublicPresentationDetail("", "WORK_COMMAND", event.sourceId(), null,
                            Map.of("safeErrorCode", code.isBlank() ? "WORK_COMMAND_REJECTED" : code,
                                    "retryable", "false", "correlationId", event.correlationId())),
                    PublicVisibility.PUBLIC));
        }
        if (event.eventType() == WorkEventType.WORK_ITEM_PAUSED) {
            return List.of(item(work, event, 0, PublicPresentationKind.WAITING_FOR_USER,
                    PublicPresentationStatus.WAITING, "任务已暂停", "执行上下文已保存，可以稍后继续。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
        }
        if (event.eventType() == WorkEventType.WORK_ITEM_RESUMED) {
            return List.of(item(work, event, 0, PublicPresentationKind.RECOVERY,
                    PublicPresentationStatus.ACTIVE, "任务已继续", "系统正在从持久化检查点恢复执行。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
        }
        if (event.eventType() == WorkEventType.WORK_ITEM_CANCELLED) {
            return List.of(item(work, event, 0, PublicPresentationKind.ERROR,
                    PublicPresentationStatus.FAILED, "任务已取消", "当前执行已停止。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
        }
        if (event.eventType() == WorkEventType.WORK_ITEM_ABANDONED) {
            return List.of(item(work, event, 0, PublicPresentationKind.ACTION_COMPLETED,
                    PublicPresentationStatus.COMPLETED, "已离开当前任务", "该任务不再作为当前工作目标。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
        }
        if (event.eventType() == WorkEventType.RUN_EVENT_PROJECTED) {
            return projectRunEvent(work, events, event, phase);
        }
        if (event.eventType() == WorkEventType.INCIDENT_EVENT_PROJECTED) {
            return projectIncidentEvent(work, event, phase);
        }
        if (event.eventType() == WorkEventType.RECOVERY_PLAN_EVENT_PROJECTED) {
            return projectRecoveryEvent(work, event, phase);
        }
        return List.of(inspector(work, event, phase));
    }

    private List<PublicPresentation> projectRunEvent(AgentWorkItem work, List<WorkEvent> events,
                                                     WorkEvent event, String phase) {
        return switch (phase) {
            case "RUN_STARTED" -> List.of(item(work, event, 0, PublicPresentationKind.ACTION_STARTED,
                    PublicPresentationStatus.ACTIVE, "开始执行", "主 Agent 已开始处理任务。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
            case "CONTEXT_PREPARED", "CONTEXT_COMPACTED" -> List.of(item(work, event, 0,
                    PublicPresentationKind.ACTION_COMPLETED, PublicPresentationStatus.COMPLETED,
                    "上下文已准备", "完成任务所需的上下文已经加载。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
            case "TOOL_REQUESTED" -> List.of(toolItem(work, events, event, false));
            case "TOOL_COMPLETED" -> List.of(toolItem(work, events, event, true));
            case "APPROVAL_REQUIRED" -> List.of(item(work, event, 0,
                    PublicPresentationKind.APPROVAL_REQUIRED, PublicPresentationStatus.WAITING,
                    "高风险操作等待审批", "继续执行前需要人工确认。", List.of(),
                    reference("APPROVAL", text(event.payload().get("approvalId"))), PublicVisibility.PUBLIC));
            case "RUN_RESUMED" -> List.of(item(work, event, 0, PublicPresentationKind.RECOVERY,
                    PublicPresentationStatus.ACTIVE, "执行已恢复", "系统正在从已保存的检查点继续。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
            case "RUN_COMPLETED" -> List.of(item(work, event, 0, PublicPresentationKind.FINAL_RESULT,
                    PublicPresentationStatus.COMPLETED, "最终结果已生成", "最终正文以 Primary Run 的持久化消息为准。",
                    List.of(), reference("PRIMARY_RUN", event.sourceId()), PublicVisibility.PUBLIC));
            case "RUN_FAILED" -> List.of(runFailureItem(work, events, event));
            case "RUN_CANCELLED" -> List.of(item(work, event, 0, PublicPresentationKind.ERROR,
                    PublicPresentationStatus.FAILED, "执行已取消", "Agent 执行已停止。",
                    List.of(), reference("PRIMARY_RUN", event.sourceId()), PublicVisibility.PUBLIC));
            case "BUDGET_EXHAUSTED" -> List.of(item(work, event, 0, PublicPresentationKind.ERROR,
                    PublicPresentationStatus.FAILED, "执行预算已耗尽",
                    "预算已耗尽，系统不会继续创建新的模型或工具调用。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
            default -> List.of(classifiedTechnicalEvent(work, event, phase));
        };
    }

    private PublicPresentation runFailureItem(AgentWorkItem work, List<WorkEvent> events, WorkEvent event) {
        WorkEvent modelFailure = events.stream()
                .filter(candidate -> candidate.sequence() <= event.sequence())
                .filter(candidate -> candidate.eventType() == WorkEventType.RUN_EVENT_PROJECTED)
                .filter(candidate -> candidate.sourceId().equals(event.sourceId()))
                .filter(candidate -> "MODEL_FAILED".equals(normalize(candidate.phase())))
                .max(java.util.Comparator.comparingLong(WorkEvent::sequence))
                .orElse(null);
        String errorCode = modelFailure == null ? text(event.payload().get("stopReason"))
                : text(modelFailure.payload().get("errorType"));
        boolean modelError = errorCode.startsWith("MODEL_") || "MODEL_ERROR".equals(errorCode);
        String title = modelError ? "模型调用失败" : "执行失败";
        String summary = modelError
                ? "系统未能获得模型响应，本次任务没有形成最终答案。"
                : "Agent 执行已安全终止，本次任务没有形成最终答案。";
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("safeErrorCode", errorCode.isBlank() ? "RUN_FAILED" : errorCode);
        attributes.put("retryable", Boolean.toString(modelError));
        attributes.put("correlationId", modelFailure == null ? event.correlationId() : modelFailure.correlationId());
        attributes.put("traceId", text(event.payload().get("traceId")));
        return item(work, event, 0, PublicPresentationKind.ERROR, PublicPresentationStatus.FAILED,
                title, summary, List.of(), new PublicPresentationDetail("", "PRIMARY_RUN",
                        event.sourceId(), null, Map.copyOf(attributes)), PublicVisibility.PUBLIC);
    }

    private List<PublicPresentation> projectIncidentEvent(AgentWorkItem work, WorkEvent event, String phase) {
        if ("TASK_RETRY_SCHEDULED".equals(phase)) {
            return List.of(item(work, event, 0, PublicPresentationKind.RETRY,
                    PublicPresentationStatus.ACTIVE, "子任务将重试", "系统将在有界重试策略内再次执行该子任务。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
        }
        if ("TASK_LEASE_RECOVERED".equals(phase)) {
            return List.of(item(work, event, 0, PublicPresentationKind.RECOVERY,
                    PublicPresentationStatus.ACTIVE, "执行已安全接管", "原执行节点失去租约，任务已由另一实例安全接管。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
        }
        if ("BUDGET_EXHAUSTED".equals(phase)) {
            return List.of(item(work, event, 0, PublicPresentationKind.ERROR,
                    PublicPresentationStatus.FAILED, "调查预算已耗尽",
                    "预算已耗尽，系统不会继续创建新的模型或工具调用。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
        }
        if ("TASK_ASSIGNMENT".equals(phase)) {
            String role = specialistRole(text(event.payload().get("recipientRole")));
            Map<String, String> attributes = incidentAttributes(event, Map.of(
                    "role", role,
                    "requiredEvidenceSubtypes", publicList(event.payload().get("requiredEvidenceSubtypes"))));
            return List.of(item(work, event, 0, PublicPresentationKind.AGENT_DELEGATION,
                    PublicPresentationStatus.ACTIVE, "已派发 " + role + " Specialist",
                    role + " Specialist 正在收集冻结范围内的只读证据。",
                    List.of(), new PublicPresentationDetail(role, "INCIDENT_TASK",
                            text(event.payload().get("taskId")), null, attributes),
                    PublicVisibility.PUBLIC));
        }
        if ("EVIDENCE_SUBMITTED".equals(phase)) {
            int evidenceCount = event.payload().get("evidenceIds") instanceof List<?> values ? values.size() : 0;
            String role = specialistRole(text(event.payload().get("senderRole")));
            Map<String, String> attributes = incidentAttributes(event, Map.of(
                    "role", role,
                    "evidenceCount", Integer.toString(evidenceCount),
                    "evidenceIds", publicList(event.payload().get("evidenceIds"))));
            return List.of(item(work, event, 0, PublicPresentationKind.ACTION_COMPLETED,
                    PublicPresentationStatus.COMPLETED, role + " Specialist 已完成取证",
                    evidenceCount > 0 ? "已提交 " + evidenceCount + " 条可追溯证据。" : "已提交受控调查结果。",
                    List.of(), new PublicPresentationDetail(role, "INCIDENT_TASK",
                            text(event.payload().get("taskId")), null, attributes),
                    PublicVisibility.PUBLIC));
        }
        if ("INCIDENT_STATE_CHANGED".equals(phase)) {
            String target = text(event.payload().get("targetStatus"));
            if ("PLANNING".equals(target)) {
                return List.of(item(work, event, 0, PublicPresentationKind.ACTION_STARTED,
                        PublicPresentationStatus.ACTIVE, "已启动只读 Multi-Agent 调查",
                        "调查仅收集证据并生成 Assessment，不执行恢复操作。",
                        List.of(), new PublicPresentationDetail("Incident", "INCIDENT", event.sourceId(), null,
                                incidentAttributes(event, Map.of("targetStatus", target))), PublicVisibility.PUBLIC));
            }
            if ("CHECKING_CONSISTENCY".equals(target)) {
                return List.of(item(work, event, 0, PublicPresentationKind.ACTION_COMPLETED,
                        PublicPresentationStatus.COMPLETED, "Specialist 已完成取证",
                        "系统正在执行确定性的跨领域证据一致性检查。",
                        List.of(), new PublicPresentationDetail("Consistency Checker", "INCIDENT",
                                event.sourceId(), null, incidentAttributes(event, Map.of("targetStatus", target))),
                        PublicVisibility.PUBLIC));
            }
            if ("REVIEWING".equals(target)) {
                return List.of(item(work, event, 0, PublicPresentationKind.ACTION_STARTED,
                        PublicPresentationStatus.ACTIVE, "Reviewer 正在汇总证据",
                        "Reviewer 只能引用已持久化的 Evidence 和 Conflict。",
                        List.of(), new PublicPresentationDetail("Reviewer", "INCIDENT", event.sourceId(), null,
                                incidentAttributes(event, Map.of("targetStatus", target))), PublicVisibility.PUBLIC));
            }
            if ("ASSESSED".equals(target) || "PARTIAL".equals(target) || "MANUAL_REVIEW".equals(target)) {
                return List.of(item(work, event, 0, PublicPresentationKind.FINAL_RESULT,
                        PublicPresentationStatus.COMPLETED, "已生成事故 Assessment",
                        "调查已结束，未执行任何恢复操作。",
                        List.of(), new PublicPresentationDetail("Reviewer", "INCIDENT", event.sourceId(), null,
                                incidentAttributes(event, Map.of("targetStatus", target,
                                        "sideEffectExecuted", "false"))), PublicVisibility.PUBLIC));
            }
        }
        if (phase.contains("CLARIFICATION")) {
            return List.of(item(work, event, 0, PublicPresentationKind.WAITING_FOR_USER,
                    PublicPresentationStatus.WAITING, "调查需要补充信息", "Reviewer 需要补充证据后才能继续。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC));
        }
        return List.of(classifiedTechnicalEvent(work, event, phase));
    }

    private String specialistRole(String value) {
        return switch (value) {
            case "ORDER_ANALYST" -> "Order";
            case "INVENTORY_ANALYST" -> "Inventory";
            case "MQ_ANALYST" -> "MQ";
            case "SOP_ANALYST" -> "SOP";
            default -> "Domain";
        };
    }

    private Map<String, String> incidentAttributes(WorkEvent event, Map<String, String> extra) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("actorType", text(event.payload().get("actorType")));
        attributes.put("eventCategory", text(event.payload().get("eventCategory")));
        attributes.put("incidentId", event.sourceId());
        extra.forEach((key, value) -> {
            if (value != null && !value.isBlank()) attributes.put(key, value);
        });
        attributes.values().removeIf(String::isBlank);
        return Map.copyOf(attributes);
    }

    private String publicList(Object value) {
        if (!(value instanceof List<?> values)) return "";
        return values.stream().map(this::text).map(String::trim).filter(item -> !item.isBlank())
                .limit(20).map(item -> item.length() <= 120 ? item : item.substring(0, 120))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private List<PublicPresentation> projectRecoveryEvent(AgentWorkItem work, WorkEvent event, String phase) {
        if (phase.contains("PREVIEW")) {
            return List.of(item(work, event, 0, PublicPresentationKind.ACTION_COMPLETED,
                    PublicPresentationStatus.COMPLETED, "恢复预演已生成", "系统已生成受控恢复预演，尚未执行副作用。",
                    List.of(), reference("RECOVERY_PLAN", event.sourceId()), PublicVisibility.PUBLIC));
        }
        if (phase.contains("APPROVAL") || phase.contains("WAITING")) {
            return List.of(item(work, event, 0, PublicPresentationKind.APPROVAL_REQUIRED,
                    PublicPresentationStatus.WAITING, "恢复计划等待审批", "执行恢复动作前需要人工确认。",
                    List.of(), reference("RECOVERY_PLAN", event.sourceId()), PublicVisibility.PUBLIC));
        }
        if (phase.contains("UNKNOWN")) {
            return List.of(item(work, event, 0, PublicPresentationKind.RECOVERY,
                    PublicPresentationStatus.ACTIVE, "正在核对执行结果",
                    "工具调用结果暂时未知，系统正在使用原请求标识进行对账。",
                    List.of(), reference("RECOVERY_PLAN", event.sourceId()), PublicVisibility.PUBLIC));
        }
        if (phase.contains("RECONCIL")) {
            return List.of(item(work, event, 0, PublicPresentationKind.RECOVERY,
                    PublicPresentationStatus.COMPLETED, "恢复对账已完成", "系统已完成恢复动作与业务状态的对账。",
                    List.of(), reference("RECOVERY_PLAN", event.sourceId()), PublicVisibility.PUBLIC));
        }
        return List.of(classifiedTechnicalEvent(work, event, phase));
    }

    private PublicPresentation toolItem(AgentWorkItem work, List<WorkEvent> events,
                                        WorkEvent event, boolean completed) {
        String toolName = text(event.payload().get("toolName"));
        String callId = text(event.payload().get("toolCallId"));
        Optional<ToolDefinition> definition = capabilityRegistry.findCapability(toolName);
        String displayName = definition.map(value -> metadataText(value, "publicDisplayName"))
                .filter(value -> !value.isBlank()).orElse("工具调用");
        String action = definition.map(value -> metadataText(value, "publicActionSummary"))
                .filter(value -> !value.isBlank()).orElse("执行受控工具调用");
        WorkEvent requested = completed ? findRequested(events, callId) : event;
        long attemptSequence = requested == null ? event.sequence() : requested.sequence();
        int attempt = 0;
        for (WorkEvent candidate : events) {
            if (candidate.sequence() > attemptSequence) break;
            if ("TOOL_REQUESTED".equals(normalize(candidate.phase()))
                    && toolName.equals(text(candidate.payload().get("toolName")))) attempt++;
        }
        Map<String, Object> arguments = completed ? Map.of()
                : publicArguments(definition.orElse(null), event.payload().get("arguments"));
        Long duration = completed && requested != null
                ? Math.max(0, Duration.between(occurredAt(requested), occurredAt(event)).toMillis()) : null;
        Integer resultCount = completed ? resultCount(event.payload()) : null;
        String resultSummary = completed ? safeResultSummary(event.payload(), resultCount) : "正在执行";
        PublicToolPresentation tool = new PublicToolPresentation(
                toolName, displayName, action, arguments, resultSummary, resultCount, duration,
                "Attempt " + attempt);
        return item(work, event, 0, PublicPresentationKind.TOOL_ACTIVITY,
                completed ? (Boolean.FALSE.equals(event.payload().get("success"))
                        ? PublicPresentationStatus.FAILED : PublicPresentationStatus.COMPLETED)
                        : PublicPresentationStatus.ACTIVE,
                displayName, completed ? resultSummary : action, List.of(),
                new PublicPresentationDetail("", "TOOL_CALL", callId, tool, Map.of()), PublicVisibility.PUBLIC);
    }

    private WorkEvent findRequested(List<WorkEvent> events, String callId) {
        return events.stream().filter(event -> "TOOL_REQUESTED".equals(normalize(event.phase())))
                .filter(event -> callId.equals(text(event.payload().get("toolCallId"))))
                .findFirst().orElse(null);
    }

    private Map<String, Object> publicArguments(ToolDefinition definition, Object rawArguments) {
        if (definition == null || !(rawArguments instanceof Map<?, ?> raw)) return Map.of();
        Object configured = definition.metadata().get("publicArgumentKeys");
        if (!(configured instanceof List<?> keys)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Object keyValue : keys) {
            String key = text(keyValue);
            if (key.isBlank() || sensitiveKey(key) || !raw.containsKey(key)) continue;
            Object safeValue = safeValue(raw.get(key));
            if (safeValue != null) result.put(key, safeValue);
        }
        return Map.copyOf(result);
    }

    private Object safeValue(Object value) {
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof String text) return text.length() <= 200 ? text : text.substring(0, 200);
        if (value instanceof List<?> list) return list.stream().map(this::safeValue)
                .filter(java.util.Objects::nonNull).limit(20).toList();
        return null;
    }

    private Integer resultCount(Map<String, Object> payload) {
        Map<String, Object> metadata = map(payload.get("metadata"));
        for (String key : List.of("documentCount", "recordCount", "resultCount")) {
            Object value = metadata.get(key);
            if (value instanceof Number number) return Math.max(0, number.intValue());
        }
        Object sources = metadata.get("sources");
        return sources instanceof List<?> list ? list.size() : null;
    }

    private String safeResultSummary(Map<String, Object> payload, Integer count) {
        if (Boolean.FALSE.equals(payload.get("success"))) return "工具调用未成功，Agent 将根据安全策略继续处理。";
        return count == null ? "工具调用已完成。" : "工具调用已完成，返回 " + count + " 条结果。";
    }

    private PublicPresentation classifiedTechnicalEvent(AgentWorkItem work, WorkEvent event, String phase) {
        if (phase.contains("FENCING_REJECTED")) {
            return item(work, event, 0, PublicPresentationKind.RECOVERY,
                    PublicPresentationStatus.COMPLETED, "已拒绝过期执行节点", "系统已阻止旧执行节点继续写入结果。",
                    List.of(), PublicPresentationDetail.empty(), PublicVisibility.PUBLIC);
        }
        PublicVisibility visibility = phase.contains("PROMPT") || phase.contains("RAW_")
                || phase.contains("POLICY") || phase.contains("CAS_")
                ? PublicVisibility.INTERNAL : PublicVisibility.INSPECTOR_ONLY;
        return item(work, event, 0, PublicPresentationKind.ACTION_COMPLETED,
                PublicPresentationStatus.COMPLETED, "技术执行事件", "执行器记录了一项技术状态变更。",
                List.of(), PublicPresentationDetail.empty(), visibility);
    }

    private PublicPresentation inspector(AgentWorkItem work, WorkEvent event, String phase) {
        return item(work, event, 0, PublicPresentationKind.ACTION_COMPLETED,
                PublicPresentationStatus.COMPLETED, "工作项状态事件", "工作项记录了一项技术状态变更。",
                List.of(), PublicPresentationDetail.empty(), PublicVisibility.INSPECTOR_ONLY);
    }

    private PublicPresentation scopeItem(AgentWorkItem work,
                                         WorkEvent event,
                                         PublicPresentationKind kind,
                                         PublicPresentationStatus status,
                                         String title,
                                         String summary) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String key : List.of("snapshotId", "snapshotVersion", "candidateFingerprint",
                "candidateCount", "deadLetterCount", "queueCount", "truncated", "timeExpression")) {
            String value = text(event.payload().get(key));
            if (!value.isBlank()) attributes.put(key, value);
        }
        return item(work, event, 0, kind, status, title, summary, List.of(),
                new PublicPresentationDetail("Incident Scope Discovery", "INCIDENT_SCOPE_SNAPSHOT",
                        text(event.payload().get("snapshotId")), null, attributes), PublicVisibility.PUBLIC);
    }

    private PublicPresentation item(AgentWorkItem work, WorkEvent event, int ordinal,
                                    PublicPresentationKind kind, PublicPresentationStatus status,
                                    String title, String summary, List<String> steps,
                                    PublicPresentationDetail detail, PublicVisibility visibility) {
        long sequence = presentationSequence(event.sequence(), ordinal);
        String coordinate = work.workItemId() + "|" + event.sourceEventId() + "|" + kind + "|" + ordinal;
        String id = "pp-" + UUID.nameUUIDFromBytes(coordinate.getBytes(StandardCharsets.UTF_8));
        return new PublicPresentation(id, work.workItemId(), sequence,
                PublicPresentation.CURRENT_SCHEMA_VERSION, kind, status, title, summary, steps, detail,
                event.sourceType(), event.sourceId(), event.sourceEventId(), occurredAt(event), visibility);
    }

    static long presentationSequence(long workEventSequence, int ordinal) {
        if (workEventSequence < 0) {
            throw new IllegalArgumentException("work event sequence must be non-negative");
        }
        if (ordinal < 0 || ordinal >= PRESENTATION_SLOTS_PER_EVENT) {
            throw new IllegalArgumentException("presentation ordinal must be between 0 and 9");
        }
        return Math.addExact(Math.multiplyExact(workEventSequence, PRESENTATION_SLOTS_PER_EVENT), ordinal);
    }

    private String publicRoutingSummary(RoutingDecisionRecord routing, String targetLabel) {
        String summary = routing == null ? "" : text(routing.decision().get("userFacingSummary"));
        if (!safePublicText(summary)) return "系统已根据任务类型选择 " + targetLabel + "。";
        return summary;
    }

    private List<String> publicPlan(Object value) {
        if (!(value instanceof List<?> raw)) return List.of();
        List<String> steps = raw.stream().map(this::text).map(String::trim)
                .filter(step -> !step.isBlank() && step.length() <= 200 && safePublicText(step))
                .limit(20).toList();
        return steps.size() == raw.size() ? steps : List.of();
    }

    private List<String> clarificationPrompts(RoutingDecisionRecord routing) {
        Object value = routing == null ? null : routing.decision().get("missingInputs");
        if (value instanceof List<?> missingInputs) {
            List<String> prompts = missingInputs.stream()
                    .map(this::text)
                    .map(String::trim)
                    .filter(input -> !input.isBlank())
                    .map(this::clarificationPrompt)
                    .distinct()
                    .limit(10)
                    .toList();
            if (!prompts.isEmpty()) return prompts;
        }
        Object reasons = routing == null ? null : routing.validation().get("reasons");
        if (reasons instanceof List<?> validationReasons) {
            List<String> prompts = validationReasons.stream()
                    .map(this::text)
                    .map(this::validationClarificationPrompt)
                    .filter(prompt -> !prompt.isBlank())
                    .distinct()
                    .limit(10)
                    .toList();
            if (!prompts.isEmpty()) return prompts;
        }
        return List.of("请说明需要处理的是唯一 OrderCare 单案例，还是批量或多案例事故调查，并提供对应业务标识。");
    }

    private String clarificationPrompt(String input) {
        return switch (input) {
            case "queueNames" -> "消息队列名称（queueNames），可填写一个或多个实际队列名";
            case "oneOf:batchId,requestIds" -> "事故范围：提供一个或多个 requestId；当前尚无 batchId 权威解析接口";
            case "batchId" -> "事故批次 ID（batchId）";
            case "requestIds" -> "一个或多个请求 ID（requestIds）";
            case "requestId" -> "请求 ID（requestId）";
            case "orderNo" -> "订单号（orderNo）";
            case "deductNo" -> "库存扣减号（deductNo）";
            case "incidentId" -> "事故 ID（incidentId）";
            case "executionScope" -> "请明确选择：唯一 OrderCare 单案例，或批量/多案例事故调查";
            default -> "必填信息：" + input;
        };
    }

    private String validationClarificationPrompt(String reason) {
        if (reason.contains("唯一单案例") && reason.contains("事故调查")) {
            return "请明确选择：唯一 OrderCare 单案例，或批量/多案例事故调查";
        }
        if (reason.contains("requestIds or discoverable business conditions")) {
            return "请补充事故调查范围：多个 requestId，或时间范围与明确异常现象";
        }
        if (reason.contains("exactly one requestId, orderNo or deductNo")) {
            return "请只提供一个单案例标识：requestId、orderNo 或 deductNo";
        }
        return "";
    }

    private boolean safePublicText(String value) {
        if (value == null || value.isBlank() || value.length() > 1000) return false;
        String normalized = value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return SENSITIVE_KEYS.stream().noneMatch(normalized::contains)
                && !normalized.contains("chain of thought") && !normalized.contains("system prompt")
                && !normalized.contains("<system") && !normalized.contains("exception at ");
    }

    private boolean sensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return SENSITIVE_KEYS.stream().map(value -> value.replace("_", "")).anyMatch(normalized::contains);
    }

    private String metadataText(ToolDefinition definition, String key) {
        return text(definition.metadata().get(key));
    }

    private PublicPresentationDetail targetDetail(String label) {
        return new PublicPresentationDetail(label, "", "", null, Map.of());
    }

    private PublicPresentationDetail reference(String type, String id) {
        return new PublicPresentationDetail("", type, id, null, Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private Instant occurredAt(WorkEvent event) {
        return event.sourceCreatedAt() == null ? event.projectedAt() : event.sourceCreatedAt();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
