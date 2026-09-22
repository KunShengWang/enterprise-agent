package com.agent.platform.procurement.model;

public record SupplierCandidate(String supplierId, String supplierName, String source) {
    public SupplierCandidate {
        if (supplierId == null || supplierId.isBlank() || supplierName == null || supplierName.isBlank()) {
            throw new IllegalArgumentException("supplier id and name are required");
        }
        supplierId = supplierId.trim(); supplierName = supplierName.trim(); source = source == null ? "" : source.trim();
    }
}
