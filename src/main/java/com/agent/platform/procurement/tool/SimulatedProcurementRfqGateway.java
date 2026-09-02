package com.agent.platform.procurement.tool;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * process-local 的 simulated external adapter，仅用于验证审批绑定、幂等和对账契约。
 * 它不是 SAP、ERPNext、真实供应商 API 或真实邮件服务，也不能证明跨进程 exactly-once。
 */
@Component
public class SimulatedProcurementRfqGateway implements ProcurementRfqGateway {

    private final ConcurrentMap<String, Receipt> receipts = new ConcurrentHashMap<>();

    @Override
    public Receipt create(CreateRequest request) {
        if (request == null) throw new IllegalArgumentException("RFQ create request is required");
        return receipts.computeIfAbsent(request.idempotencyKey(), key -> new Receipt(
                "rfq-" + UUID.randomUUID(),
                key,
                request.supplierId(),
                "CREATED",
                Instant.now(),
                "simulated-procurement-rfq-gateway"
        ));
    }

    @Override
    public Optional<Receipt> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        return Optional.ofNullable(receipts.get(idempotencyKey.trim()));
    }
}
