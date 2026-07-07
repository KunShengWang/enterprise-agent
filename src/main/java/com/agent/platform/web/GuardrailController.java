package com.agent.platform.web;

import com.agent.platform.approval.ApprovalDecision;
import com.agent.platform.approval.ApprovalRecord;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.approval.ApprovalStore;
import com.agent.platform.common.ApiResponse;
import com.agent.platform.common.ErrorCode;
import com.agent.platform.guardrail.GuardrailAuditRecord;
import com.agent.platform.guardrail.GuardrailAuditRecorder;
import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/agent/guardrails")
public class GuardrailController {

    private final GuardrailService guardrailService;

    private final GuardrailAuditRecorder auditRecorder;

    private final ApprovalStore approvalStore;

    private final ApprovalService approvalService;

    public GuardrailController(GuardrailService guardrailService,
                               GuardrailAuditRecorder auditRecorder,
                               ApprovalStore approvalStore,
                               ApprovalService approvalService) {
        this.guardrailService = guardrailService;
        this.auditRecorder = auditRecorder;
        this.approvalStore = approvalStore;
        this.approvalService = approvalService;
    }

    @PostMapping("/input/check")
    public Mono<ApiResponse<GuardrailDecision>> checkInput(@Valid @RequestBody CheckInputRequest request) {
        return Mono.fromSupplier(() -> ApiResponse.success(guardrailService.checkInput(request.content())));
    }

    @PostMapping("/output/check")
    public Mono<ApiResponse<GuardrailDecision>> checkOutput(@Valid @RequestBody CheckInputRequest request) {
        return Mono.fromSupplier(() -> ApiResponse.success(guardrailService.checkOutput(request.content())));
    }

    @GetMapping("/audits")
    public Mono<ApiResponse<List<GuardrailAuditRecord>>> audits(@RequestParam(defaultValue = "50") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(auditRecorder.recent(limit)));
    }

    @GetMapping("/approvals")
    public Mono<ApiResponse<List<ApprovalRecord>>> approvals(@RequestParam(defaultValue = "50") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(approvalStore.recent(limit)));
    }

    @GetMapping("/approvals/{approvalId}")
    public Mono<ApiResponse<ApprovalRecord>> approval(@PathVariable String approvalId) {
        return Mono.fromSupplier(() -> approvalStore.find(approvalId)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.failure(ErrorCode.NOT_FOUND, "approval not found: " + approvalId)));
    }

    @PostMapping("/approvals/{approvalId}/decide")
    public Mono<ApiResponse<ApprovalDecision>> decide(@PathVariable String approvalId,
                                                      @Valid @RequestBody DecideApprovalRequest request) {
        return Mono.fromSupplier(() -> ApiResponse.success(approvalService.decide(
                approvalId,
                request.approved(),
                request.reviewer(),
                request.reason()
        )));
    }

    public record CheckInputRequest(
            @NotBlank
            String content
    ) {
    }

    public record DecideApprovalRequest(
            boolean approved,
            String reviewer,
            String reason
    ) {
    }
}
