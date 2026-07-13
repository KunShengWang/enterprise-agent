package com.agent.platform.runtime;

import com.agent.platform.rag.RagResult;
import com.agent.platform.rag.RagService;
import com.agent.platform.rag.RetrievedDocument;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolExecutor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DefaultAgentCapabilityExecutor implements AgentCapabilityExecutor {

    private final RagService ragService;
    private final ToolExecutor toolExecutor;

    public DefaultAgentCapabilityExecutor(RagService ragService, ToolExecutor toolExecutor) {
        this.ragService = ragService;
        this.toolExecutor = toolExecutor;
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request) {
        if (DefaultAgentCapabilityRegistry.KNOWLEDGE_SEARCH.equals(request.toolName())) {
            return executeKnowledgeSearch(request);
        }
        return toolExecutor.execute(request);
    }

    private ToolCallResult executeKnowledgeSearch(ToolCallRequest request) {
        String query = stringArgument(request.arguments(), "query");
        if (query.isBlank()) {
            return new ToolCallResult(
                    request.toolName(),
                    false,
                    "",
                    "knowledge_search requires a non-blank query",
                    Map.of("provider", "rag", "readOnly", true)
            );
        }
        int topK = intArgument(request.arguments(), "topK", 3, 1, 10);
        RagResult result = ragService.retrieve(query, topK);
        StringBuilder content = new StringBuilder();
        List<RetrievedDocument> documents = result.documents();
        for (int index = 0; index < documents.size(); index++) {
            RetrievedDocument document = documents.get(index);
            content.append("[").append(index + 1).append("] ")
                    .append(document.content())
                    .append("\nsource=").append(sourceOf(document))
                    .append("; score=").append(document.score())
                    .append('\n');
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "rag");
        metadata.put("readOnly", true);
        metadata.put("query", result.query());
        metadata.put("enoughEvidence", result.enoughEvidence());
        metadata.put("documentCount", documents.size());
        metadata.put("sources", documents.stream().map(this::sourceOf).distinct().toList());
        if (documents.isEmpty()) {
            return new ToolCallResult(
                    request.toolName(),
                    false,
                    "",
                    "knowledge base returned no relevant evidence",
                    metadata
            );
        }
        return new ToolCallResult(request.toolName(), true, content.toString().strip(), "", metadata);
    }

    private String stringArgument(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intArgument(Map<String, Object> arguments,
                            String name,
                            int defaultValue,
                            int min,
                            int max) {
        Object value = arguments == null ? null : arguments.get(name);
        int parsed = defaultValue;
        if (value instanceof Number number) {
            parsed = number.intValue();
        }
        else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value));
            }
            catch (NumberFormatException ignored) {
                parsed = defaultValue;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private String sourceOf(RetrievedDocument document) {
        Object source = document.metadata().get("source");
        if (source != null && !String.valueOf(source).isBlank()) {
            return String.valueOf(source);
        }
        if (document.title() != null && !document.title().isBlank()) {
            return document.title();
        }
        return document.documentId();
    }
}
