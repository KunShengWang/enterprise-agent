package com.agent.platform.web;

import com.agent.platform.common.ApiResponse;
import com.agent.platform.common.ErrorCode;
import com.agent.platform.workflow.WorkflowRecorder;
import com.agent.platform.workflow.WorkflowRunRecord;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/agent/workflows")
public class WorkflowController {

    private final WorkflowRecorder workflowRecorder;

    public WorkflowController(WorkflowRecorder workflowRecorder) {
        this.workflowRecorder = workflowRecorder;
    }

    @GetMapping
    public Mono<ApiResponse<List<WorkflowRunRecord>>> recent(@RequestParam(defaultValue = "20") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(workflowRecorder.recent(limit)));
    }

    @GetMapping("/{traceId}")
    public Mono<ApiResponse<WorkflowRunRecord>> find(@PathVariable String traceId) {
        return Mono.fromSupplier(() -> workflowRecorder.find(traceId)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.failure(ErrorCode.NOT_FOUND, "workflow run not found: " + traceId)));
    }
}
