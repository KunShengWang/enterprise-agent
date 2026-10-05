package com.agent.platform.runtime;

import com.agent.platform.tool.*;
import java.util.Map;
import java.util.concurrent.*;

/** Bounded workers: a non-cooperative handler cannot consume unbounded threads or block the run forever. */
final class ToolExecutionDeadline {
    private static final ExecutorService WORKERS = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "harness-tool"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    static ToolCallResult call(ToolCallRequest request, ToolDefinition definition, Callable<ToolCallResult> action) {
        long remaining = definition.metadata().get("_harnessDeadlineEpochMillis") instanceof Number n
                ? n.longValue() - System.currentTimeMillis() : Long.MAX_VALUE;
        if (remaining <= 0) return failure(request, false, "run deadline exhausted before tool dispatch", "FATAL_ERROR");
        long timeoutMillis = Math.min(remaining, definition.runtimeContract().timeoutMillis());
        Future<ToolCallResult> future;
        try { future = WORKERS.submit(action); }
        catch (RejectedExecutionException full) {
            return failure(request, false, "tool executor capacity exhausted", "RETRYABLE_ERROR");
        }
        try { return future.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (TimeoutException timeout) {
            future.cancel(true);
            // Cancellation is not proof that an external write was rolled back. Never replay it automatically.
            boolean uncertain = definition.runtimeContract().effect() != ToolRuntimeContract.Effect.READ_ONLY;
            return failure(request, uncertain, "tool execution deadline exceeded", "RETRYABLE_ERROR");
        }
        catch (InterruptedException interrupted) {
            future.cancel(true); Thread.currentThread().interrupt();
            return failure(request, definition.runtimeContract().effect() != ToolRuntimeContract.Effect.READ_ONLY,
                    "tool execution interrupted", "FATAL_ERROR");
        }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            return failure(request, false, "tool execution failed", "FATAL_ERROR");
        }
    }

    private static ToolCallResult failure(ToolCallRequest request, boolean uncertain, String message, String outcome) {
        return new ToolCallResult(request.toolName(), false, "", message,
                Map.of("_harnessOutcome", uncertain ? "FATAL_ERROR" : outcome,
                        "retryable", !uncertain && "RETRYABLE_ERROR".equals(outcome), "manualReview", uncertain));
    }
}
