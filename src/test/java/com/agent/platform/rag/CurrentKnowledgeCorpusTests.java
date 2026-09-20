package com.agent.platform.rag;

import com.agent.platform.config.RagProperties;
import com.agent.platform.eval.DefaultRagEvalRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CurrentKnowledgeCorpusTests {
    private final RagProperties properties = new RagProperties();
    private final LocalDocumentLoader loader = new LocalDocumentLoader();

    @Test
    void defaultCorpusRetainsGenericIncidentKnowledgeButNeverLoadsTheArchivedSop() {
        var documents = loader.load(Path.of(properties.getDocumentDir()));
        assertFalse(documents.isEmpty());
        assertTrue(documents.stream().noneMatch(d -> d.source().contains("ordercare") || d.source().contains("archive/")));
        var incident = documents.stream().filter(d -> d.source().equals("incident-response.md")).findFirst().orElseThrow();
        assertTrue(incident.content().contains("10 分钟"));
        assertTrue(incident.content().contains("复盘报告"));
    }

    @Test
    void ingestionOnlyReplacesLoadedSourcesAndDoesNotPretendToDeleteRemovedVectors() {
        var repository = mock(PgVectorRagRepository.class);
        when(repository.replaceBySources(anyList(), anyList())).thenReturn(new RagSaveReport(0, 1));
        var service = new PgVectorKnowledgeIngestionService(properties, loader, new TextChunker(properties),
                text -> new double[]{1, 0}, repository, mock(RagCacheOperations.class));
        var report = service.ingestConfiguredDirectory();
        assertTrue(report.sources().contains("incident-response.md"));
        assertFalse(report.sources().contains("ordercare-recovery-sop-v1.md"));
        verify(repository).replaceBySources(argThat(sources -> sources.contains("incident-response.md")
                && !sources.contains("ordercare-recovery-sop-v1.md")), anyList());
        verify(repository, never()).deleteBySource(anyString());
    }

    @Test
    void defaultEvalSourceAndKeywordContractsStillMatchTheCurrentLocalCorpus() {
        var documents = loader.load(Path.of(properties.getDocumentDir())).stream()
                .collect(Collectors.toMap(LoadedDocument::source, Function.identity()));
        // Deterministic retrieval fixture: checks corpus/Eval compatibility, not vector ranking quality.
        Map<String, String> sources = Map.of("退款审批流程是什么？", "refund-policy.md",
                "P1 故障应该怎么响应？", "incident-response.md",
                "高风险生产发布需要哪些准备？", "release-process.md", "RAG 的主流程是什么？", "rag-guide.md");
        RagService fixture = (query, topK) -> {
            var document = documents.get(sources.get(query));
            assertNotNull(document, query);
            return new RagResult(query, List.of(new RetrievedDocument(document.source(), document.source(),
                    document.content(), 1, Map.of("source", document.source()))), true);
        };
        var report = new DefaultRagEvalRunner(properties, fixture).run(List.of());
        assertEquals(4, report.totalCases());
        assertEquals(4, report.passedCases());
    }
}
