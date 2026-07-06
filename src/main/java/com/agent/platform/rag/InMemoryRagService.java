package com.agent.platform.rag;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class InMemoryRagService implements RagService {

    private final List<RetrievedDocument> documents = List.of(
            new RetrievedDocument(
                    "kb-rag-001",
                    "RAG 检索流程",
                    "RAG 的主流程是加载文档、切分、Embedding、向量检索、TopK 召回、Prompt 拼接和回答生成。回答必须尽量引用检索到的资料。",
                    0,
                    Map.of("source", "rag-guide.md")
            ),
            new RetrievedDocument(
                    "kb-refund-001",
                    "退款审批流程",
                    "退款申请需要先校验订单状态，再由客服主管审批。超过 5000 元的退款需要财务复核，并保留审批记录。",
                    0,
                    Map.of("source", "refund-policy.md")
            ),
            new RetrievedDocument(
                    "kb-release-001",
                    "生产发布规范",
                    "高风险生产发布必须完成发布单审批、回滚预案、灰度验证和发布后监控。未经审批不得直接上线。",
                    0,
                    Map.of("source", "release-process.md")
            ),
            new RetrievedDocument(
                    "kb-incident-001",
                    "故障应急响应",
                    "P1 故障需要 10 分钟内响应，优先恢复核心链路，必要时切换人工坐席，并在恢复后输出复盘报告。",
                    0,
                    Map.of("source", "incident-response.md")
            )
    );

    @Override
    public RagResult retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return RagResult.empty(query);
        }
        List<RetrievedDocument> matched = documents.stream()
                .map(document -> withScore(document, score(query, document)))
                .filter(document -> document.score() > 0)
                .sorted(Comparator.comparingDouble(RetrievedDocument::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
        return new RagResult(query, matched, !matched.isEmpty());
    }

    private RetrievedDocument withScore(RetrievedDocument document, double score) {
        return new RetrievedDocument(document.documentId(), document.title(), document.content(), score, document.metadata());
    }

    private double score(String query, RetrievedDocument document) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String text = (document.title() + " " + document.content()).toLowerCase(Locale.ROOT);
        double score = 0;
        for (String token : List.of("rag", "检索", "退款", "审批", "发布", "应急", "故障", "知识库", "流程")) {
            if (normalizedQuery.contains(token) && text.contains(token)) {
                score += 1;
            }
        }
        return score;
    }
}
