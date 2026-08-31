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
    public SupplierOffer(String supplierId, String productId, String productName, BigDecimal unitPrice,
                         String currency, int quantity, BigDecimal totalPrice, int leadTimeDays,
                         String warranty, Map<String, Object> specifications, String source, Instant fetchedAt) {
        this(supplierId, productId, productName, unitPrice, currency, quantity, totalPrice, leadTimeDays,
                warranty, specifications, source, fetchedAt, productId, source, fetchedAt, "");
    }

    public SupplierOffer {
        if (supplierId == null || supplierId.isBlank() || productId == null || productId.isBlank()
                || unitPrice == null || unitPrice.signum() < 0 || quantity <= 0 || leadTimeDays < 0
                || currency == null || currency.isBlank() || fetchedAt == null) throw new IllegalArgumentException("invalid supplier offer");
        supplierId = supplierId.trim(); productId = productId.trim(); productName = productName == null ? "" : productName.trim();
        currency = currency.trim().toUpperCase();
        totalPrice = unitPrice.multiply(BigDecimal.valueOf(quantity));
        warranty = warranty == null ? "" : warranty.trim();
        specifications = specifications == null ? Map.of() : Map.copyOf(specifications);
        source = source == null ? "" : source.trim();
        sourceRecordId = sourceRecordId == null || sourceRecordId.isBlank() ? productId : sourceRecordId.trim();
        sourceSnapshot = sourceSnapshot == null || sourceSnapshot.isBlank() ? source : sourceSnapshot.trim();
        sourceAsOf = sourceAsOf == null ? fetchedAt : sourceAsOf;
        sourceDigest = sourceDigest == null || sourceDigest.isBlank()
                ? EvidenceIdFactory.digest(source, sourceRecordId, sourceSnapshot, productId + "|" + unitPrice + "|" + quantity)
                : sourceDigest.trim();
    }
}
