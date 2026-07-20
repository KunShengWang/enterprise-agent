package com.agent.platform.workbench.web;

import com.agent.platform.common.ApiResponse;
import com.agent.platform.workbench.application.ConversationFocusService;
import com.agent.platform.workbench.application.RouteConfirmationService;
import com.agent.platform.workbench.application.UnifiedWorkInputRequest;
import com.agent.platform.workbench.application.UnifiedWorkIntakeResult;
import com.agent.platform.workbench.application.UnifiedWorkIntakeService;
import com.agent.platform.workbench.application.UnifiedWorkLauncher;
import com.agent.platform.workbench.application.UnifiedWorkQueryService;
import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.ConversationWorkState;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.security.WorkbenchPrincipalProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public UnifiedWorkController(WorkbenchPrincipalProvider principals,
                                 UnifiedWorkIntakeService intake,
                                 UnifiedWorkLauncher launcher,
                                 UnifiedWorkQueryService queries,
                                 RouteConfirmationService confirmations,
                                 ConversationFocusService focusService,
                                 WorkbenchStore workbench,
                                 RoutingStore routing) {
        this.principals = principals; this.intake = intake; this.launcher = launcher;
        this.queries = queries; this.confirmations = confirmations; this.focusService = focusService;
        this.workbench = workbench; this.routing = routing;
    }

    @PostMapping("/conversations/{conversationId}/inputs")
    public ResponseEntity<ApiResponse<?>> submit(
            @PathVariable String conversationId,
            @RequestHeader("Idempotency-Key") String clientInputId,
            @RequestBody UnifiedInputBody body) {
        validateMetadata(body.metadata());
        AuthenticatedPrincipal principal = principals.current();
        UnifiedWorkIntakeResult result = intake.accept(principal, new UnifiedWorkInputRequest(
                "input-" + UUID.randomUUID(), clientInputId, conversationId, body.content(),
                ClassifierType.MODEL, null, ""));
        if (result.commandOnly()) {
            WorkCommandType commandType = result.commandDecision().commandType();
            String focusedWorkItemId = result.commandDecision().focusedWorkItemId();
            String executionTarget = focusedWorkItemId == null || focusedWorkItemId.isBlank()
                    ? ""
                    : workbench.findWorkItem(principal, focusedWorkItemId)
                            .map(AgentWorkItem::activeExecutionTarget)
                            .orElse("");
            CommandError error = new CommandError("UNSUPPORTED_FOR_TARGET",
                    commandType == null ? "UNKNOWN" : commandType.name(), executionTarget,
                    focusedWorkItemId == null ? "" : focusedWorkItemId, false,
                    "M1-D records natural-language work commands but does not execute them; use the existing target page");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiResponse<>(false, error.code(), error.message(), error));
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
    public ApiResponse<AgentWorkItem> abandon(@PathVariable String workItemId,
                                              @RequestBody WorkCommandBody body) {
        AuthenticatedPrincipal principal = principals.current();
        AgentWorkItem work = owned(principal, workItemId);
        recordControlInput(principal, work.conversationId(), body.clientInputId(), "ABANDON", workItemId);
        return ApiResponse.success(workbench.abandon(principal, workItemId, body.expectedVersion(), "input-button"));
    }

    @PostMapping("/work-items/{workItemId}/commands/{command}")
    public ResponseEntity<ApiResponse<CommandError>> unsupportedCommand(@PathVariable String workItemId,
                                                                         @PathVariable String command,
                                                                         @RequestBody WorkCommandBody body) {
        AuthenticatedPrincipal principal = principals.current();
        AgentWorkItem work = owned(principal, workItemId);
        String normalized = command.trim().toUpperCase(Locale.ROOT);
        recordControlInput(principal, work.conversationId(), body.clientInputId(), normalized, workItemId);
        CommandError error = new CommandError("UNSUPPORTED_FOR_TARGET", normalized,
                work.activeExecutionTarget(), workItemId, false,
                "M1-D does not expose a cross-executor command handler; use the existing target page");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiResponse<>(false, error.code(), error.message(), error));
    }

    private AgentWorkItem owned(AuthenticatedPrincipal principal, String workItemId) {
        return workbench.findWorkItem(principal, workItemId)
                .orElseThrow(() -> new com.agent.platform.workbench.persistence.WorkbenchNotFoundException("work item not found"));
    }

    private void recordControlInput(AuthenticatedPrincipal principal,
                                    String conversationId,
                                    String clientInputId,
                                    String action,
                                    String workItemId) {
        routing.persistUnclassifiedInput(principal, "input-" + UUID.randomUUID(), clientInputId,
                conversationId, "[CONTROL] " + action + " workItemId=" + workItemId);
    }

    private void validateMetadata(Map<String, Object> metadata) {
        if (metadata == null) return;
        boolean forbidden = metadata.keySet().stream()
                .map(key -> key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT))
                .anyMatch(FORBIDDEN_METADATA::contains);
        if (forbidden) throw new IllegalArgumentException("metadata contains a forbidden identity or execution field");
    }

    public record UnifiedInputBody(String content, Map<String, Object> metadata) {
        public UnifiedInputBody { if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required"); }
    }
    public record UnifiedInputResponse(String inputId, String workItemId, String controlState,
                                       String commandType, boolean commandOnly) { }
    public record FocusBody(String workItemId, long expectedVersion, String clientInputId) { }
    public record ConfirmRouteBody(String previewId, int previewVersion, String validatedInputDigest,
                                   String scopeDigest, String clientInputId) { }
    public record RejectRouteBody(String previewId, String clientInputId) { }
    public record WorkCommandBody(long expectedVersion, String clientInputId) { }
    public record CommandError(String code, String command, String executionTarget, String workItemId,
                               boolean underlyingExecutionChanged, String message) { }
}
