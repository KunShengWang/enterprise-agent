package com.agent.platform.workbench.web;

import com.agent.platform.common.ApiResponse;
import com.agent.platform.workbench.application.ConversationFocusService;
import com.agent.platform.workbench.application.RouteConfirmationService;
import com.agent.platform.workbench.application.UnifiedWorkInputRequest;
import com.agent.platform.workbench.application.UnifiedWorkExecutionTreeService;
import com.agent.platform.workbench.application.UnifiedWorkIntakeResult;
import com.agent.platform.workbench.application.UnifiedWorkIntakeService;
import com.agent.platform.workbench.application.UnifiedWorkLauncher;
import com.agent.platform.workbench.application.UnifiedWorkQueryService;
import com.agent.platform.workbench.application.WorkCommandHandler;
import com.agent.platform.workbench.application.WorkCommandRequest;
import com.agent.platform.workbench.application.WorkCommandResult;
import com.agent.platform.workbench.application.WorkItemBudgetQueryService;
import com.agent.platform.workbench.budget.BudgetAccount;
import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.ConversationWorkState;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.model.UnifiedWorkExecutionTree;
import com.agent.platform.workbench.presentation.PublicPresentation;
import com.agent.platform.workbench.presentation.PublicPresentationService;
import com.agent.platform.workbench.presentation.PublicPresentationStreamService;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.security.WorkbenchPrincipalProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/agent")
@ConditionalOnProperty(prefix = "enterprise-agent.workbench",
        name = {"web.enabled", "routing.enabled", "dispatch.enabled"}, havingValue = "true")
public class UnifiedWorkController {
    private static final Set<String> FORBIDDEN_METADATA = Set.of(
            "userid", "tenant", "tenantid", "roles", "approvedby", "executionprofile",
            "toolwhitelist", "scenarioid", "principalid");

    private final WorkbenchPrincipalProvider principals;
    private final UnifiedWorkIntakeService intake;
    private final UnifiedWorkLauncher launcher;
    private final UnifiedWorkQueryService queries;
    private final RouteConfirmationService confirmations;
    private final ConversationFocusService focusService;
    private final WorkbenchStore workbench;
    private final RoutingStore routing;
    private final UnifiedWorkEventStreamService eventStream;
    private final UnifiedWorkExecutionTreeService executionTrees;
    private final WorkCommandHandler commandHandler;
    private final WorkItemBudgetQueryService budgetQueries;
    private final PublicPresentationService presentations;
    private final PublicPresentationStreamService presentationStream;

    public UnifiedWorkController(WorkbenchPrincipalProvider principals,
                                 UnifiedWorkIntakeService intake,
                                 UnifiedWorkLauncher launcher,
                                 UnifiedWorkQueryService queries,
                                 RouteConfirmationService confirmations,
                                 ConversationFocusService focusService,
                                 WorkbenchStore workbench,
                                 RoutingStore routing,
                                 UnifiedWorkEventStreamService eventStream,
                                 UnifiedWorkExecutionTreeService executionTrees,
                                 WorkCommandHandler commandHandler,
                                 WorkItemBudgetQueryService budgetQueries,
                                 PublicPresentationService presentations,
                                 PublicPresentationStreamService presentationStream) {
        this.principals = principals; this.intake = intake; this.launcher = launcher;
        this.queries = queries; this.confirmations = confirmations; this.focusService = focusService;
        this.workbench = workbench; this.routing = routing; this.eventStream = eventStream;
        this.executionTrees = executionTrees;
        this.commandHandler = commandHandler;
        this.budgetQueries = budgetQueries;
        this.presentations = presentations;
        this.presentationStream = presentationStream;
    }

    @PostMapping("/conversations/{conversationId}/inputs")
    public Mono<ResponseEntity<ApiResponse<?>>> submit(
            @PathVariable String conversationId,
            @RequestHeader("Idempotency-Key") String clientInputId,
            @RequestBody UnifiedInputBody body) {
        AuthenticatedPrincipal principal = principals.current();
        return Mono.fromCallable(() -> submitBlocking(principal, conversationId, clientInputId, body))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/conversations/{conversationId}/work-items")
    public ApiResponse<List<AgentWorkItem>> workItems(@PathVariable String conversationId,
                                                       @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(queries.workItems(principals.current(), conversationId, limit));
    }

    @GetMapping("/conversations/{conversationId}/inputs")
    public ApiResponse<List<AgentConversationTurn>> inputs(@PathVariable String conversationId,
                                                            @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.success(queries.inputs(principals.current(), conversationId, limit));
    }

    @GetMapping("/conversations/{conversationId}/focus")
    public ApiResponse<ConversationWorkState> focus(@PathVariable String conversationId) {
        return ApiResponse.success(queries.focus(principals.current(), conversationId));
    }

    @PutMapping("/conversations/{conversationId}/focus")
    public ApiResponse<ConversationWorkState> switchFocus(@PathVariable String conversationId,
                                                           @RequestBody FocusBody body) {
        AuthenticatedPrincipal principal = principals.current();
        recordControlInput(principal, conversationId, body.clientInputId(), "FOCUS", body.workItemId());
        return ApiResponse.success(focusService.switchFocus(
                principal, conversationId, body.workItemId(), body.expectedVersion()));
    }

    @GetMapping("/work-items/{workItemId}")
    public ApiResponse<UnifiedWorkQueryService.UnifiedWorkItemView> detail(@PathVariable String workItemId) {
        return ApiResponse.success(queries.detail(principals.current(), workItemId));
    }

    @GetMapping("/work-items/{workItemId}/events")
    public ApiResponse<List<WorkEvent>> events(@PathVariable String workItemId,
                                               @RequestParam(defaultValue = "-1") long afterSequence,
                                               @RequestParam(defaultValue = "500") int limit) {
        return ApiResponse.success(workbench.loadEvents(
                principals.current(), workItemId, afterSequence, limit));
    }

    @GetMapping(value = "/work-items/{workItemId}/events/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<UnifiedWorkStreamItem>> eventStream(
            @PathVariable String workItemId,
            @RequestParam(defaultValue = "-1") long afterSequence,
            @RequestParam(defaultValue = "-1") long afterRunSequence,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        UnifiedWorkStreamCursor cursor = UnifiedWorkStreamCursor.resolve(
                afterSequence, afterRunSequence, lastEventId);
        return eventStream.stream(principals.current(), workItemId, cursor);
    }

    @GetMapping("/work-items/{workItemId}/execution-tree")
    public ApiResponse<UnifiedWorkExecutionTree> executionTree(@PathVariable String workItemId) {
        return ApiResponse.success(executionTrees.project(principals.current(), workItemId));
    }

    @GetMapping("/work-items/{workItemId}/budget")
    public ApiResponse<BudgetAccount> budget(@PathVariable String workItemId) {
        return ApiResponse.success(budgetQueries.require(principals.current(), workItemId));
    }

    @GetMapping("/work-items/{workItemId}/presentations")
    public ApiResponse<List<PublicPresentation>> presentations(
            @PathVariable String workItemId,
            @RequestParam(defaultValue = "-1") long afterSequence,
            @RequestParam(defaultValue = "500") int limit) {
        return ApiResponse.success(presentations.publicTimeline(
                principals.current(), workItemId, afterSequence, limit));
    }

    @GetMapping("/work-items/{workItemId}/presentations/inspector")
    public ApiResponse<List<PublicPresentation>> inspectorPresentations(
            @PathVariable String workItemId,
            @RequestParam(defaultValue = "-1") long afterSequence,
            @RequestParam(defaultValue = "500") int limit) {
        return ApiResponse.success(presentations.inspectorTimeline(
                principals.current(), workItemId, afterSequence, limit));
    }

    @GetMapping(value = "/work-items/{workItemId}/presentations/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<PublicPresentation>> presentationStream(
            @PathVariable String workItemId,
            @RequestParam(defaultValue = "-1") long afterSequence,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return presentationStream.stream(principals.current(), workItemId,
                presentationStream.resolveCursor(afterSequence, lastEventId));
    }

    @PostMapping("/work-items/{workItemId}/confirm-route")
    public ApiResponse<AgentWorkItem> confirm(@PathVariable String workItemId,
                                              @RequestBody ConfirmRouteBody body) {
        AuthenticatedPrincipal principal = principals.current();
        AgentWorkItem work = owned(principal, workItemId);
        recordControlInput(principal, work.conversationId(), body.clientInputId(), "CONFIRM_ROUTE", workItemId);
        AgentWorkItem updated = confirmations.confirm(principal, workItemId, body.previewId(),
                body.previewVersion(), body.validatedInputDigest(), body.scopeDigest());
        launcher.dispatchIfReady(principal, workItemId);
        return ApiResponse.success(updated);
    }

    @PostMapping("/work-items/{workItemId}/reject-route")
    public ApiResponse<AgentWorkItem> reject(@PathVariable String workItemId,
                                             @RequestBody RejectRouteBody body) {
        AuthenticatedPrincipal principal = principals.current();
        AgentWorkItem work = owned(principal, workItemId);
        recordControlInput(principal, work.conversationId(), body.clientInputId(), "REJECT_ROUTE", workItemId);
        return ApiResponse.success(confirmations.reject(principal, workItemId, body.previewId()));
    }

    @PostMapping("/work-items/{workItemId}/abandon")
    public Mono<ResponseEntity<ApiResponse<WorkCommandResult>>> abandon(@PathVariable String workItemId,
                                                                         @RequestBody WorkCommandBody body) {
        return executeExplicitCommand(workItemId, WorkCommandType.ABANDON_ACTIVE_WORK, body);
    }

    @PostMapping("/work-items/{workItemId}/commands/{command}")
    public Mono<ResponseEntity<ApiResponse<WorkCommandResult>>> command(@PathVariable String workItemId,
                                                                         @PathVariable String command,
                                                                         @RequestBody WorkCommandBody body) {
        return executeExplicitCommand(workItemId, parseCommand(command), body);
    }

    @PostMapping("/work-items/{workItemId}/pause")
    public Mono<ResponseEntity<ApiResponse<WorkCommandResult>>> pause(@PathVariable String workItemId,
                                                                       @RequestBody WorkCommandBody body) {
        return executeExplicitCommand(workItemId, WorkCommandType.PAUSE_ACTIVE_WORK, body);
    }

    @PostMapping("/work-items/{workItemId}/resume")
    public Mono<ResponseEntity<ApiResponse<WorkCommandResult>>> resume(@PathVariable String workItemId,
                                                                        @RequestBody WorkCommandBody body) {
        return executeExplicitCommand(workItemId, WorkCommandType.RESUME_ACTIVE_WORK, body);
    }

    @PostMapping("/work-items/{workItemId}/cancel")
    public Mono<ResponseEntity<ApiResponse<WorkCommandResult>>> cancel(@PathVariable String workItemId,
                                                                        @RequestBody WorkCommandBody body) {
        return executeExplicitCommand(workItemId, WorkCommandType.CANCEL_ACTIVE_WORK, body);
    }

    private ResponseEntity<ApiResponse<?>> submitBlocking(AuthenticatedPrincipal principal,
                                                           String conversationId,
                                                           String clientInputId,
                                                           UnifiedInputBody body) {
        // 验证元数据是否包含禁止的身份或执行字段
        validateMetadata(body.metadata());
        // 收下用户输入，判定意图，决定是直接执行命令还是创建任务交给 Agent 跑。
        UnifiedWorkIntakeResult result = intake.accept(principal, new UnifiedWorkInputRequest(
                "input-" + UUID.randomUUID(), clientInputId, conversationId, body.content(),
                ClassifierType.MODEL, null, ""));
        if (result.commandOnly()) {
            return commandResponse(commandHandler.handle(principal,
                    new WorkCommandRequest(result.input(), result.commandDecision(), "", null)));
        }
        if (result.workItem() != null) {
            launcher.routeAndDispatch(principal, result.workItem().workItemId(), result.workItem().routingRequestId());
        }
        UnifiedInputResponse response = new UnifiedInputResponse(
                result.input().inputId(), result.workItem() == null ? "" : result.workItem().workItemId(),
                result.workItem() == null ? "COMMAND_RECORDED" : result.workItem().controlState().name(),
                result.commandDecision().commandType() == null ? "" : result.commandDecision().commandType().name(),
                result.commandOnly());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }

    private Mono<ResponseEntity<ApiResponse<WorkCommandResult>>> executeExplicitCommand(
            String workItemId,
            WorkCommandType commandType,
            WorkCommandBody body) {
        AuthenticatedPrincipal principal = principals.current();
        return Mono.fromCallable(() -> {
                    AgentWorkItem work = owned(principal, workItemId);
                    UnifiedWorkIntakeResult intakeResult = intake.accept(principal, new UnifiedWorkInputRequest(
                            "input-" + UUID.randomUUID(), body.clientInputId(), work.conversationId(),
                            "[CONTROL] " + commandType.name() + " workItemId=" + workItemId,
                            ClassifierType.DETERMINISTIC_BUTTON, commandType, ""));
                    WorkCommandResult result = commandHandler.handle(principal, new WorkCommandRequest(
                            intakeResult.input(), intakeResult.commandDecision(), workItemId, body.expectedVersion()));
                    return typedCommandResponse(result);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<ApiResponse<?>> commandResponse(WorkCommandResult result) {
        HttpStatus status = commandStatus(result);
        return ResponseEntity.status(status).body(new ApiResponse<>(result.success(), result.code(),
                result.message(), result));
    }

    private ResponseEntity<ApiResponse<WorkCommandResult>> typedCommandResponse(WorkCommandResult result) {
        HttpStatus status = commandStatus(result);
        return ResponseEntity.status(status).body(new ApiResponse<>(result.success(), result.code(),
                result.message(), result));
    }

    private HttpStatus commandStatus(WorkCommandResult result) {
        if (result.success()) return HttpStatus.OK;
        if ("FORBIDDEN".equals(result.code())) return HttpStatus.FORBIDDEN;
        return HttpStatus.CONFLICT;
    }

    private WorkCommandType parseCommand(String command) {
        String normalized = command == null ? "" : command.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PAUSE", "PAUSE_ACTIVE_WORK" -> WorkCommandType.PAUSE_ACTIVE_WORK;
            case "RESUME", "RESUME_ACTIVE_WORK" -> WorkCommandType.RESUME_ACTIVE_WORK;
            case "CANCEL", "CANCEL_ACTIVE_WORK" -> WorkCommandType.CANCEL_ACTIVE_WORK;
            case "ABANDON", "ABANDON_ACTIVE_WORK" -> WorkCommandType.ABANDON_ACTIVE_WORK;
            case "ADD_INPUT", "ADD_INPUT_TO_ACTIVE_WORK" -> WorkCommandType.ADD_INPUT_TO_ACTIVE_WORK;
            default -> throw new IllegalArgumentException("unsupported work command: " + command);
        };
    }

    private void recordControlInput(AuthenticatedPrincipal principal,
                                    String conversationId,
                                    String clientInputId,
                                    String action,
                                    String workItemId) {
        routing.persistUnclassifiedInput(principal, "input-" + UUID.randomUUID(), clientInputId,
                conversationId, "[CONTROL] " + action + " workItemId=" + workItemId);
    }

    private AgentWorkItem owned(AuthenticatedPrincipal principal, String workItemId) {
        return workbench.findWorkItem(principal, workItemId)
                .orElseThrow(() -> new com.agent.platform.workbench.persistence.WorkbenchNotFoundException("work item not found"));
    }

    private void validateMetadata(Map<String, Object> metadata) {
        if (metadata == null) return;
        boolean forbidden = metadata.keySet().stream()
                .map(key -> key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT))
                .anyMatch(FORBIDDEN_METADATA::contains);
        if (forbidden) throw new IllegalArgumentException("metadata contains a forbidden identity or execution field");
    }

    public record UnifiedInputBody(String content, Map<String, Object> metadata) {
        public UnifiedInputBody {
            if (content == null || content.isBlank())
                throw new IllegalArgumentException("content is required");
        }
    }

    public record UnifiedInputResponse(String inputId, String workItemId, String controlState,
                                       String commandType, boolean commandOnly) { }
    public record FocusBody(String workItemId, long expectedVersion, String clientInputId) { }
    public record ConfirmRouteBody(String previewId, int previewVersion, String validatedInputDigest,
                                   String scopeDigest, String clientInputId) { }
    public record RejectRouteBody(String previewId, String clientInputId) { }
    public record WorkCommandBody(long expectedVersion, String clientInputId) { }
}
