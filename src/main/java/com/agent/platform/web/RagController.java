package com.agent.platform.web;

import com.agent.platform.common.ApiResponse;
import com.agent.platform.eval.RagEvalCase;
import com.agent.platform.eval.RagEvalReport;
import com.agent.platform.eval.RagEvalRunner;
import com.agent.platform.rag.IngestionReport;
import com.agent.platform.rag.KnowledgeIngestionService;
import com.agent.platform.rag.PgVectorRagRepository;
import com.agent.platform.rag.RagCacheOperations;
import com.agent.platform.rag.RagCacheStats;
import com.agent.platform.rag.RagCorpusStats;
import com.agent.platform.rag.RagResult;
import com.agent.platform.rag.RagService;
import com.agent.platform.rag.RagSourceDeleteReport;
import com.agent.platform.rag.RagRunRecord;
import com.agent.platform.rag.RagRunRecorder;
import com.agent.platform.rag.RagRunReportFile;
import com.agent.platform.rag.RagRunReportService;
import com.agent.platform.rag.RagRunStats;
import com.agent.platform.rag.RagVectorIndexReport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/agent/rag")
public class RagController {

    private final ObjectProvider<KnowledgeIngestionService> ingestionServiceProvider;

    private final ObjectProvider<PgVectorRagRepository> pgVectorRepositoryProvider;

    private final RagService ragService;

    private final RagEvalRunner ragEvalRunner;

    private final RagRunRecorder ragRunRecorder;

    private final RagRunReportService ragRunReportService;

    private final ObjectProvider<RagCacheOperations> ragCacheOperationsProvider;

    public RagController(ObjectProvider<KnowledgeIngestionService> ingestionServiceProvider,
                         ObjectProvider<PgVectorRagRepository> pgVectorRepositoryProvider,
                         RagService ragService,
                         RagEvalRunner ragEvalRunner,
                         RagRunRecorder ragRunRecorder,
                         RagRunReportService ragRunReportService,
                         ObjectProvider<RagCacheOperations> ragCacheOperationsProvider) {
        this.ingestionServiceProvider = ingestionServiceProvider;
        this.pgVectorRepositoryProvider = pgVectorRepositoryProvider;
        this.ragService = ragService;
        this.ragEvalRunner = ragEvalRunner;
        this.ragRunRecorder = ragRunRecorder;
        this.ragRunReportService = ragRunReportService;
        this.ragCacheOperationsProvider = ragCacheOperationsProvider;
    }

    /**
     * 加载文档，向量化等并存入 postgresql
     */
    @PostMapping("/ingest")
    public Mono<ApiResponse<IngestionReport>> ingest() {
        return Mono.fromSupplier(() -> {
                    KnowledgeIngestionService ingestionService = ingestionServiceProvider.getIfAvailable();
                    if (ingestionService == null) {
                        throw new IllegalStateException("No KnowledgeIngestionService is available for current RAG mode");
                    }
                    return ApiResponse.success(ingestionService.ingestConfiguredDirectory());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 创建字段索引，提升检索性能
     */
    @PostMapping("/index")
    public Mono<ApiResponse<RagVectorIndexReport>> createIndex() {
        return Mono.fromSupplier(() -> {
                    PgVectorRagRepository repository = pgVectorRepositoryProvider.getIfAvailable();
                    if (repository == null) {
                        throw new IllegalStateException("Creating RAG vector index is only available in pgvector mode");
                    }
                    return ApiResponse.success(repository.createVectorIndex());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/eval")
    public Mono<ApiResponse<RagEvalReport>> eval(@RequestBody(required = false) RagEvalRequest request) {
        return Mono.fromSupplier(() -> {
                    List<RagEvalCase> cases = request == null ? List.of() : request.cases();
                    return ApiResponse.success(ragEvalRunner.run(cases));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/search")
    public Mono<ApiResponse<RagResult>> search(@Valid @RequestBody RagSearchRequest request) {
        return Mono.fromSupplier(() -> ApiResponse.success(ragService.retrieve(request.query(), request.effectiveTopK())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/runs")
    public Mono<ApiResponse<List<RagRunRecord>>> recentRuns(@RequestParam(defaultValue = "20") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(ragRunRecorder.recent(limit)));
    }

    @GetMapping("/runs/stats")
    public Mono<ApiResponse<RagRunStats>> runStats(@RequestParam(defaultValue = "100") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(ragRunRecorder.stats(limit)));
    }

    @GetMapping("/cache/stats")
    public Mono<ApiResponse<RagCacheStats>> cacheStats() {
        return Mono.fromSupplier(() -> {
            RagCacheOperations operations = ragCacheOperationsProvider.getIfAvailable();
            if (operations == null) {
                return ApiResponse.success(new RagCacheStats(false, 0, 0, 0, 0, 0, 0));
            }
            return ApiResponse.success(operations.cacheStats());
        });
    }

    @DeleteMapping("/cache")
    public Mono<ApiResponse<String>> clearCache() {
        return Mono.fromSupplier(() -> {
            RagCacheOperations operations = ragCacheOperationsProvider.getIfAvailable();
            if (operations != null) {
                operations.clearCache();
            }
            return ApiResponse.success("RAG cache cleared");
        });
    }

    @PostMapping("/runs/report")
    public Mono<ApiResponse<RagRunReportFile>> generateRunReport(@RequestParam(defaultValue = "100") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(ragRunReportService.generate(limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/runs")
    public Mono<ApiResponse<String>> clearRuns() {
        return Mono.fromSupplier(() -> {
            ragRunRecorder.clear();
            return ApiResponse.success("RAG run records cleared");
        });
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<RagCorpusStats>> stats() {
        return Mono.fromSupplier(() -> {
                    PgVectorRagRepository repository = pgVectorRepositoryProvider.getIfAvailable();
                    if (repository == null) {
                        return ApiResponse.success(new RagCorpusStats("memory", 0, List.of()));
                    }
                    return ApiResponse.success(repository.stats());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/source")
    public Mono<ApiResponse<RagSourceDeleteReport>> deleteSource(@Valid @RequestBody RagDeleteSourceRequest request) {
        return Mono.fromSupplier(() -> {
                    PgVectorRagRepository repository = pgVectorRepositoryProvider.getIfAvailable();
                    if (repository == null) {
                        throw new IllegalStateException("Deleting RAG source is only available in pgvector mode");
                    }
                    int deletedChunks = repository.deleteBySource(request.source());
                    return ApiResponse.success(new RagSourceDeleteReport("pgvector", request.source(), deletedChunks));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public record RagSearchRequest(
            @NotBlank
            String query,
            Integer topK
    ) {

        int effectiveTopK() {
            return topK == null || topK <= 0 ? 3 : topK;
        }
    }

    public record RagDeleteSourceRequest(
            @NotBlank
            String source
    ) {
    }

    public record RagEvalRequest(
            List<RagEvalCase> cases
    ) {

        public RagEvalRequest {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }
}
