package com.agent.platform.eval;

import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Primary
@Component
@ConditionalOnProperty(prefix = "enterprise-agent.storage", name = "mode", havingValue = "jdbc", matchIfMissing = true)
public class JdbcEvalCaseRepository implements EvalCaseRepository {

    private static final String CATEGORY = "eval_case";

    private final JdbcAgentStoreSupport store;

    public JdbcEvalCaseRepository(JdbcAgentStoreSupport store) {
        this.store = store;
    }

    @Override
    public List<EvalCase> list() {
        seedDefaultsIfNeeded();
        return store.recent(CATEGORY, EvalCase.class, Integer.MAX_VALUE).stream()
                .sorted((left, right) -> left.id().compareToIgnoreCase(right.id()))
                .toList();
    }

    @Override
    public Optional<EvalCase> find(String id) {
        seedDefaultsIfNeeded();
        return store.find(CATEGORY, id, EvalCase.class);
    }

    @Override
    public EvalCase save(EvalCase evalCase) {
        if (evalCase == null || evalCase.id() == null || evalCase.id().isBlank()) {
            throw new IllegalArgumentException("eval case id must not be blank");
        }
        store.save(CATEGORY, evalCase.id(), evalCase, Instant.now(), Instant.now());
        return evalCase;
    }

    @Override
    public boolean delete(String id) {
        return store.delete(CATEGORY, id);
    }

    private void seedDefaultsIfNeeded() {
        if (store.count(CATEGORY) > 0) {
            return;
        }
        for (EvalCase evalCase : defaults()) {
            save(evalCase);
        }
    }

    private List<EvalCase> defaults() {
        return List.of(
                new EvalCase("agent-rag-refund", "RAG 退款流程问答", "退款审批流程是什么？",
                        List.of("客服主管", "财务复核"), List.of("编造", "未知但我猜"), List.of(), true, false, 0.7, Map.of("category", "rag")),
                new EvalCase("agent-tool-ticket-status", "工具调用查询工单", "查询工单 T1001 的状态",
                        List.of("T1001"), List.of("我猜", "可能是"), List.of("ticket_status"), false, true, 0.75, Map.of("category", "tool")),
                new EvalCase("agent-chat-smalltalk", "普通对话不应乱用工具", "你好，你能做什么？",
                        List.of("知识库", "工单"), List.of("T9999", "已关闭"), List.of(), false, false, 0.65, Map.of("category", "chat"))
        );
    }
}
