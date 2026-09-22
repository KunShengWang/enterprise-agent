package com.agent.platform.procurement.tool;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 外部 RFQ 副作用的最小适配器边界。询价事实读取仍由 ProcurementDataProvider 负责。
 */
public interface ProcurementRfqGateway {

    Receipt create(CreateRequest request);

    Optional<Receipt> findByIdempotencyKey(String idempotencyKey);

    record CreateRequest(
            String idempotencyKey,
            String supplierId,
            String productCategory,
            String productDescription,
            int quantity,
            String currency,
            int requiredDeliveryDays,
            Map<String, String> hardConstraints
    ) {
        public CreateRequest {
            idempotencyKey = required(idempotencyKey, "idempotencyKey");
            supplierId = required(supplierId, "supplierId");
            productCategory = required(productCategory, "productCategory");
            productDescription = required(productDescription, "productDescription");
            if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
            currency = required(currency, "currency").toUpperCase();
            if (requiredDeliveryDays <= 0) {
                throw new IllegalArgumentException("requiredDeliveryDays must be positive");
            }
            hardConstraints = hardConstraints == null ? Map.of() : Map.copyOf(hardConstraints);
        }

        private static String required(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
            return value.trim();
        }
    }

    record Receipt(
            String rfqId,
            String idempotencyKey,
            String supplierId,
            String status,
            Instant createdAt,
            String source
    ) {
        public Receipt {
            rfqId = required(rfqId, "rfqId");
            idempotencyKey = required(idempotencyKey, "idempotencyKey");
            supplierId = required(supplierId, "supplierId");
            status = required(status, "status");
            createdAt = createdAt == null ? Instant.now() : createdAt;
            source = required(source, "source");
        }

        private static String required(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
            return value.trim();
        }
    }
}
