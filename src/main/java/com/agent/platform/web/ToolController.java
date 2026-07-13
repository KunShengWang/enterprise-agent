package com.agent.platform.web;

import com.agent.platform.common.ApiResponse;
import com.agent.platform.tool.ToolCallRecord;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolRegistry;
import com.agent.platform.tool.ToolRunRecorder;
import com.agent.platform.tool.ToolRunStats;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/api/agent/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    private final ToolRunRecorder toolRunRecorder;

    private final ToolExecutionStore toolExecutionStore;

    public ToolController(ToolRegistry toolRegistry,
                          ToolRunRecorder toolRunRecorder,
                          ToolExecutionStore toolExecutionStore) {
        this.toolRegistry = toolRegistry;
        this.toolRunRecorder = toolRunRecorder;
        this.toolExecutionStore = toolExecutionStore;
    }

    @GetMapping
    public Mono<ApiResponse<List<ToolDefinition>>> listTools() {
        return Mono.fromSupplier(() -> ApiResponse.success(toolRegistry.listTools()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/runs")
    public ApiResponse<List<ToolCallRecord>> recentRuns(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(toolRunRecorder.recent(limit));
    }

    @GetMapping("/runs/stats")
    public ApiResponse<ToolRunStats> stats() {
        return ApiResponse.success(toolRunRecorder.stats());
    }

    @GetMapping("/executions/{toolCallId}")
    public ApiResponse<ToolExecutionRecord> execution(@PathVariable String toolCallId) {
        return toolExecutionStore.findToolExecution(toolCallId)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.failure(
                        com.agent.platform.common.ErrorCode.NOT_FOUND,
                        "tool execution not found: " + toolCallId
                ));
    }

    @GetMapping("/executions")
    public ApiResponse<List<ToolExecutionRecord>> executionsByRun(@RequestParam String runId) {
        return ApiResponse.success(toolExecutionStore.findByRun(runId));
    }
}
