package com.agent.platform.web;

import com.agent.platform.common.ApiResponse;
import com.agent.platform.trace.TraceRecorder;
import com.agent.platform.trace.TraceReplayEvent;
import com.agent.platform.trace.TraceRun;
import com.agent.platform.trace.TraceRunStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/agent/traces")
public class TraceController {

    private final TraceRecorder traceRecorder;

    public TraceController(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    @GetMapping
    public Mono<ApiResponse<List<TraceRun>>> recentRuns(@RequestParam(defaultValue = "20") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(traceRecorder.recentRuns(limit)));
    }

    @GetMapping("/{traceId}")
    public Mono<ApiResponse<TraceRun>> getRun(@PathVariable String traceId) {
        return Mono.fromSupplier(() -> traceRecorder.findRun(traceId)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.failure(com.agent.platform.common.ErrorCode.NOT_FOUND, "trace not found: " + traceId)));
    }

    @GetMapping("/{traceId}/replay")
    public Mono<ApiResponse<List<TraceReplayEvent>>> replay(@PathVariable String traceId) {
        return Mono.fromSupplier(() -> ApiResponse.success(traceRecorder.replay(traceId)));
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<TraceRunStats>> stats(@RequestParam(defaultValue = "100") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(traceRecorder.stats(limit)));
    }
}
