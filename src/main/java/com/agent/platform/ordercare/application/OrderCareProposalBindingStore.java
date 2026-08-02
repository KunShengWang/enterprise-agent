package com.agent.platform.ordercare.application;

import com.agent.platform.storage.JdbcAgentStoreSupport;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** enterprise-agent 只保存 FlowOrder Proposal 的关联引用和不可变审计副本。 */
@Component
public class OrderCareProposalBindingStore {

    private static final String CATEGORY = "ordercare-proposal-binding";

    private final JdbcAgentStoreSupport store;

    public OrderCareProposalBindingStore(JdbcAgentStoreSupport store) {
        this.store = store;
    }

    public synchronized OrderCareProposalBinding bind(OrderCareProposalBinding binding) {
        Optional<OrderCareProposalBinding> existing = find(binding.proposalId());
        if (existing.isPresent()) {
            OrderCareProposalBinding current = existing.get();
            if (!Objects.equals(current.runId(), binding.runId())
                    || !Objects.equals(current.previewToolExecutionId(), binding.previewToolExecutionId())
                    || !Objects.equals(current.actionRequestId(), binding.actionRequestId())
                    || !Objects.equals(current.immutablePreview().previewDigest(),
                    binding.immutablePreview().previewDigest())) {
                throw new IllegalArgumentException("proposalId 已绑定其他 Run 或预演快照");
            }
            return current;
        }
        store.save(CATEGORY, binding.proposalId(), binding, binding.createdAt(), Instant.now());
        return binding;
    }

    public Optional<OrderCareProposalBinding> find(String proposalId) {
        return store.find(CATEGORY, proposalId, OrderCareProposalBinding.class);
    }

    public OrderCareProposalBinding requireForRun(String proposalId, String runId) {
        // 查询 Proposal 绑定记录
        OrderCareProposalBinding binding = find(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("当前 Runtime 没有该 Proposal 的成功 preview 记录"));
        // 验证 Proposal 是否属于当前 Run
        if (runId == null || runId.isBlank() || !Objects.equals(binding.runId(), runId)) {
            throw new IllegalArgumentException("Proposal 不属于当前 Run，禁止跨案例或跨 Run 执行");
        }
        return binding;
    }
}
