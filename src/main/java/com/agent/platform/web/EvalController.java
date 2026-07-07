package com.agent.platform.web;

import com.agent.platform.common.ApiResponse;
import com.agent.platform.common.ErrorCode;
import com.agent.platform.eval.AgentRunEvalEvent;
import com.agent.platform.eval.EvalCase;
import com.agent.platform.eval.EvalCaseRepository;
import com.agent.platform.eval.EvalEventRecorder;
import com.agent.platform.eval.EvalReport;
import com.agent.platform.eval.EvalReportRecorder;
import com.agent.platform.eval.EvalRunner;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/api/agent/evals")
public class EvalController {

    private final EvalRunner evalRunner;

    private final EvalCaseRepository evalCaseRepository;

    private final EvalReportRecorder evalReportRecorder;

    private final EvalEventRecorder evalEventRecorder;

    public EvalController(EvalRunner evalRunner,
                          EvalCaseRepository evalCaseRepository,
                          EvalReportRecorder evalReportRecorder,
                          EvalEventRecorder evalEventRecorder) {
        this.evalRunner = evalRunner;
        this.evalCaseRepository = evalCaseRepository;
        this.evalReportRecorder = evalReportRecorder;
        this.evalEventRecorder = evalEventRecorder;
    }

    @GetMapping("/cases")
    public Mono<ApiResponse<List<EvalCase>>> listCases() {
        return Mono.fromSupplier(() -> ApiResponse.success(evalCaseRepository.list()));
    }

    @PostMapping("/cases")
    public Mono<ApiResponse<EvalCase>> saveCase(@Valid @RequestBody EvalCase evalCase) {
        return Mono.fromSupplier(() -> ApiResponse.success(evalCaseRepository.save(evalCase)));
    }

    @DeleteMapping("/cases/{caseId}")
    public Mono<ApiResponse<String>> deleteCase(@PathVariable String caseId) {
        return Mono.fromSupplier(() -> evalCaseRepository.delete(caseId)
                ? ApiResponse.success("eval case deleted")
                : ApiResponse.failure(ErrorCode.NOT_FOUND, "eval case not found: " + caseId));
    }

    @PostMapping("/run")
    public Mono<ApiResponse<EvalReport>> run(@RequestBody(required = false) EvalRunRequest request) {
        return Mono.fromSupplier(() -> {
                    List<EvalCase> cases = request == null || request.cases() == null ? List.of() : request.cases();
                    EvalReport report = evalRunner.run(cases);
                    evalReportRecorder.record(report);
                    return ApiResponse.success(report);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/regression")
    public Mono<ApiResponse<EvalReport>> regression() {
        return Mono.fromSupplier(() -> {
                    EvalReport report = evalRunner.run(List.of());
                    evalReportRecorder.record(report);
                    return ApiResponse.success(report);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/reports")
    public Mono<ApiResponse<List<EvalReport>>> recentReports(@RequestParam(defaultValue = "10") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(evalReportRecorder.recent(limit)));
    }

    @GetMapping("/reports/{runId}")
    public Mono<ApiResponse<EvalReport>> getReport(@PathVariable String runId) {
        return Mono.fromSupplier(() -> evalReportRecorder.find(runId)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.failure(ErrorCode.NOT_FOUND, "eval report not found: " + runId)));
    }

    @GetMapping("/events")
    public Mono<ApiResponse<List<AgentRunEvalEvent>>> events() {
        return Mono.fromSupplier(() -> ApiResponse.success(evalEventRecorder.snapshot()));
    }

    @DeleteMapping("/reports")
    public Mono<ApiResponse<String>> clearReports() {
        return Mono.fromSupplier(() -> {
            evalReportRecorder.clear();
            return ApiResponse.success("eval reports cleared");
        });
    }

    public record EvalRunRequest(
            List<EvalCase> cases
    ) {

        public EvalRunRequest {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }
}
