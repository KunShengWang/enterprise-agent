package com.agent.platform.web;

import com.agent.platform.common.ApiResponse;
import com.agent.platform.tool.ToolCallRecord;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolDefinition;
import com.agent.platform.tool.ToolExecutor;
import com.agent.platform.tool.ToolRegistry;
import com.agent.platform.tool.ToolRunRecorder;
import com.agent.platform.tool.ToolRunStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final ToolExecutor toolExecutor;

    private final ToolRunRecorder toolRunRecorder;

    public ToolController(ToolRegistry toolRegistry,
                          ToolExecutor toolExecutor,
                          ToolRunRecorder toolRunRecorder) {
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.toolRunRecorder = toolRunRecorder;
    }

    @GetMapping
    public Mono<ApiResponse<List<ToolDefinition>>> listTools() {
        return Mono.fromSupplier(() -> ApiResponse.success(toolRegistry.listTools()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/call")
    public Mono<ApiResponse<ToolCallResult>> callTool(@RequestBody ToolCallRequest request) {
        return Mono.fromSupplier(() -> ApiResponse.success(toolExecutor.execute(request)))
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
}
