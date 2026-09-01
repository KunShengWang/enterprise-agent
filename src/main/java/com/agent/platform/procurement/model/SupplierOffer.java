package com.agent.platform.procurement.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record SupplierOffer(
        String supplierId,
        String productId,
        String productName,
        BigDecimal unitPrice,
        String currency,
        int quantity,
        BigDecimal totalPrice,
        int leadTimeDays,
        String warranty,
        Map<String, Object> specifications,
        String source,
        Instant fetchedAt,
        String sourceRecordId,
        String sourceSnapshot,
        Instant sourceAsOf,
        String sourceDigest
) {
    public SupplierOffer {
        if (supplierId == null || supplierId.isBlank() || productId == null || productId.isBlank()
                || unitPrice == null || unitPrice.signum() < 0 || quantity <= 0 || leadTimeDays < 0
                || currency == null || currency.isBlank() || fetchedAt == null
                || source == null || source.isBlank() || sourceRecordId == null || sourceRecordId.isBlank()
                || sourceSnapshot == null || sourceSnapshot.isBlank() || sourceAsOf == null
                || sourceDigest == null || sourceDigest.isBlank()) throw new IllegalArgumentException("invalid supplier offer");
        supplierId = supplierId.trim(); productId = productId.trim(); productName = productName == null ? "" : productName.trim();
        currency = currency.trim().toUpperCase();
        totalPrice = unitPrice.multiply(BigDecimal.valueOf(quantity));
        warranty = warranty == null ? "" : warranty.trim();
        specifications = specifications == null ? Map.of() : Map.copyOf(specifications);
        source = source.trim();
        sourceRecordId = sourceRecordId.trim();
        sourceSnapshot = sourceSnapshot.trim();
        sourceDigest = sourceDigest.trim();
    }
}
