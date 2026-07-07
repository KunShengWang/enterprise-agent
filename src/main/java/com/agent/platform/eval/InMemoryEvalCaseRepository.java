package com.agent.platform.eval;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryEvalCaseRepository implements EvalCaseRepository {

    private final ConcurrentMap<String, EvalCase> cases = new ConcurrentHashMap<>();

    public InMemoryEvalCaseRepository() {
        for (EvalCase evalCase : defaultCases()) {
            cases.put(evalCase.id(), evalCase);
        }
    }

    @Override
    public List<EvalCase> list() {
        return cases.values().stream()
                .sorted((left, right) -> left.id().compareToIgnoreCase(right.id()))
                .toList();
    }

    @Override
    public Optional<EvalCase> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cases.get(id.trim()));
    }

    @Override
    public EvalCase save(EvalCase evalCase) {
        if (evalCase == null || evalCase.id() == null || evalCase.id().isBlank()) {
            throw new IllegalArgumentException("eval case id must not be blank");
        }
        cases.put(evalCase.id(), evalCase);
        return evalCase;
    }

    @Override
    public boolean delete(String id) {
        return id != null && cases.remove(id) != null;
    }

    private List<EvalCase> defaultCases() {
        return List.of(
                new EvalCase(
                        "agent-rag-refund",
                        "RAG 退款流程问答",
                        "退款审批流程是什么？",
                        List.of("客服主管", "财务复核"),
                        List.of("编造", "未知但我猜"),
                        List.of(),
                        true,
                        false,
                        0.7,
                        meta("category", "rag")
                ),
                new EvalCase(
                        "agent-tool-ticket-status",
                        "工具调用查询工单",
                        "查询工单 T1001 的状态",
                        List.of("T1001"),
                        List.of("我猜", "可能是"),
                        List.of("ticket_status"),
                        false,
                        true,
                        0.75,
                        meta("category", "tool")
                ),
                new EvalCase(
                        "agent-chat-smalltalk",
                        "普通对话不应乱用工具",
                        "你好，你能做什么？",
                        List.of("知识库", "工单"),
                        List.of("T9999", "已关闭"),
                        List.of(),
                        false,
                        false,
                        0.65,
                        meta("category", "chat")
                )
        );
    }

    private Map<String, Object> meta(String key, Object value) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(key, value);
        return metadata;
    }
}
