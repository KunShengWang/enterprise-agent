package com.agent.platform.procurement.erp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Procurement domain contract. No Agent, MCP, or ERPNext DTO dependency. */
public interface ProcurementErpAdapter {
    record Source(String backend, String entityType, String entityId, Instant retrievedAt, Instant version) { }
    record Page<T>(List<T> records, Integer nextOffset) { }
    record Supplier(String id, String name, boolean approved, String capacity, Source source) { }
    record Item(String id, String name, String category, String uom, int gpuMemoryGb, String warranty, Source source) { }
    record ItemSupplier(String id, String itemId, String supplierId, BigDecimal referencePrice,
                        String currency, int leadTimeDays, int capacityUnits, Source source) { }
    record BudgetStatus(String id, String departmentId, BigDecimal allocated, BigDecimal committed,
                        BigDecimal available, String currency, String fiscalYear, Source source) { }
    record PurchaseHistory(String id, String supplierId, String itemId, int quantity, BigDecimal unitPrice,
                           String currency, LocalDate orderedOn, LocalDate deliveredOn, Source source) { }
    record SupplierPerformance(String supplierId, int transactionCount, BigDecimal onTimeRate,
                               BigDecimal qualityScore, Source source) { }
    record Requisition(String id, String departmentId, String itemId, int quantity,
                       LocalDate requiredBy, String status, Source source) { }
    record RfqRequest(String idempotencyKey, String supplierId, String itemId, int quantity,
                      int deliveryDays, String currency) { }
    record RfqRecord(String id, String idempotencyKey, String supplierId, String itemId,
                     int quantity, String status, String requestDigest, Source source) { }
    Optional<Requisition> getRequisition(String id);
    Page<Requisition> listRequisitions(int offset, int limit);
    Optional<Item> getItem(String id);
    Page<Item> listItems(int offset, int limit);
    BudgetStatus getBudgetStatus(String departmentId);
    Optional<Supplier> getSupplier(String id);
    Page<Supplier> listSuppliers(int offset, int limit);
    Page<ItemSupplier> listItemSuppliers(String itemId, int offset, int limit);
    Page<PurchaseHistory> getPurchaseHistory(String supplierId, int offset, int limit);
    Optional<SupplierPerformance> getSupplierPerformance(String supplierId);
    Optional<RfqRecord> getRfq(String id);
    Optional<RfqRecord> findRfqByIdempotencyKey(String key);
    RfqRecord createRfq(RfqRequest request);
}
