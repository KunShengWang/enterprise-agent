package com.agent.platform.procurement.erp;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import com.agent.platform.procurement.model.EvidenceIdFactory;
import static com.agent.platform.procurement.erp.ProcurementErpException.Code.*;

public final class ErpNextProcurementAdapter implements ProcurementErpAdapter {
    private final ErpNextClient client;
    private final String company;
    public ErpNextProcurementAdapter(ErpNextClient client, String company) { this.client = client; this.company = company; }
    public static ErpNextProcurementAdapter fromEnvironment() {
        return new ErpNextProcurementAdapter(ErpNextClient.fromEnvironment(), ErpNextClient.env("ERPNEXT_COMPANY"));
    }
    public Optional<Supplier> getSupplier(String id) { return client.get("Supplier", id).map(this::supplier); }
    public Page<Supplier> listSuppliers(int offset, int limit) {
        return page("Supplier", List.of(List.of("custom_procurement_seed", "=", "v2-workstations")), offset, limit, this::supplier);
    }
    public Optional<Item> getItem(String id) { return client.get("Item", id).map(this::item); }
    public Page<Item> listItems(int offset, int limit) {
        return page("Item", List.of(List.of("custom_procurement_seed", "=", "v2-workstations"), List.of("disabled", "=", 0)), offset, limit, this::item);
    }
    public Page<ItemSupplier> listItemSuppliers(String itemId, int offset, int limit) {
        return page("Procurement Item Supplier", List.of(List.of("item", "=", itemId)), offset, limit, n -> new ItemSupplier(
                text(n,"name"), text(n,"item"), text(n,"supplier"), decimal(n,"reference_price"), text(n,"currency"),
                integer(n,"lead_time_days"), integer(n,"capacity_units"), source("Procurement Item Supplier",n)));
    }
    public BudgetStatus getBudgetStatus(String departmentId) {
        var list = client.list("Budget", List.of(List.of("custom_department_id","=",departmentId), List.of("company","=",company)),0,2);
        if (list.isEmpty()) throw new ProcurementErpException(ERP_NOT_FOUND);
        if (list.size()!=1) throw new ProcurementErpException(ERP_CONFLICT);
        JsonNode n=list.get(0); BigDecimal allocated=decimal(n,"budget_amount"), committed=decimal(n,"custom_committed_amount");
        return new BudgetStatus(text(n,"name"),departmentId,allocated,committed,allocated.subtract(committed),
                text(n,"custom_currency"),text(n,"from_fiscal_year"),source("Budget",n));
    }
    public Page<PurchaseHistory> getPurchaseHistory(String supplierId,int offset,int limit) {
        return page("Procurement Purchase History",List.of(List.of("supplier","=",supplierId)),offset,limit,n -> new PurchaseHistory(
                text(n,"name"),text(n,"supplier"),text(n,"item"),integer(n,"quantity"),decimal(n,"unit_price"),text(n,"currency"),
                date(n,"ordered_on"),date(n,"delivered_on"),source("Procurement Purchase History",n)));
    }
    public Optional<SupplierPerformance> getSupplierPerformance(String id) {
        return client.get("Procurement Supplier Performance",id).map(n -> {
            BigDecimal rate=decimal(n,"on_time_rate"), quality=decimal(n,"quality_score");
            if(rate.compareTo(BigDecimal.ONE)>0 || quality.compareTo(new BigDecimal("100"))>0) throw new ProcurementErpException(ERP_RESPONSE_INVALID);
            return new SupplierPerformance(text(n,"supplier"),integer(n,"transaction_count"),rate,quality,source("Procurement Supplier Performance",n));
        });
    }
    public Optional<Requisition> getRequisition(String id) { return client.get("Material Request",id).map(this::requisition); }
    public Page<Requisition> listRequisitions(int offset,int limit) {
        return page("Material Request",List.of(List.of("custom_procurement_seed","=","v2-workstations"),List.of("company","=",company)),offset,limit,
                n -> getRequisition(text(n,"name")).orElseThrow(() -> new ProcurementErpException(ERP_RESPONSE_INVALID)));
    }
    public Optional<RfqRecord> getRfq(String id) { return client.get("Request for Quotation",id).map(this::rfq); }
    public Optional<RfqRecord> findRfqByIdempotencyKey(String key) {
        var rows=client.list("Request for Quotation",List.of(List.of("custom_procurement_key","=",key),List.of("company","=",company)),0,2);
        if(rows.size()>1) throw new ProcurementErpException(ERP_CONFLICT);
        return rows.isEmpty()?Optional.empty():getRfq(text(rows.get(0),"name"));
    }
    public RfqRecord createRfq(RfqRequest r) {
        if(r==null || r.quantity()<=0 || r.deliveryDays()<=0 || r.deliveryDays()>3650 || r.idempotencyKey()==null ||
                !r.idempotencyKey().startsWith("rfq:") || r.idempotencyKey().length()>140 ||
                r.supplierId()==null || r.itemId()==null || r.currency()==null) throw new ProcurementErpException(ERP_VALIDATION_ERROR);
        String digest=EvidenceIdFactory.digest("ERPNext:RFQ",r.idempotencyKey(),"v1",
                new tools.jackson.databind.ObjectMapper().writeValueAsString(r));
        var existing=findRfqByIdempotencyKey(r.idempotencyKey());
        if(existing.isPresent()) return same(existing.get(),digest);
        Supplier supplier=getSupplier(r.supplierId()).orElseThrow(() -> new ProcurementErpException(ERP_NOT_FOUND));
        Item item=getItem(r.itemId()).orElseThrow(() -> new ProcurementErpException(ERP_NOT_FOUND));
        if(!supplier.approved() || !item.active()) throw new ProcurementErpException(ERP_VALIDATION_ERROR);
        String requisitionItem=null;
        if(r.requisitionId()!=null) {
            JsonNode document=client.get("Material Request",r.requisitionId()).orElseThrow(() -> new ProcurementErpException(ERP_NOT_FOUND));
            Requisition requisition=requisition(document);
            requisitionItem=text(single(document,"items"),"name");
            if(!r.requisitionId().equals(requisition.id()) || requisitionItem.isBlank()) throw new ProcurementErpException(ERP_RESPONSE_INVALID);
            if(!requisition.itemId().equals(item.id()) || requisition.quantity()!=r.quantity()
                    || Set.of("Cancelled","Stopped").contains(requisition.status()))
                throw new ProcurementErpException(ERP_VALIDATION_ERROR);
        }
        var relations=listItemSuppliers(item.id(),0,200).records().stream().filter(v -> v.supplierId().equals(supplier.id())).toList();
        if(relations.size()!=1 || relations.get(0).capacityUnits()<r.quantity() || !relations.get(0).currency().equals(r.currency()) ||
                relations.get(0).leadTimeDays()>r.deliveryDays()) throw new ProcurementErpException(ERP_VALIDATION_ERROR);
        Map<String,Object> doc=new LinkedHashMap<>();
        doc.put("company",company); doc.put("transaction_date",LocalDate.now(ZoneOffset.UTC).toString());
        doc.put("naming_series","PUR-RFQ-.YYYY.-"); doc.put("subject","AI workstation inquiry");
        doc.put("status","Draft"); doc.put("custom_procurement_key",r.idempotencyKey());
        doc.put("custom_request_digest",digest); doc.put("custom_procurement_seed","v2-workstations");
        doc.put("suppliers",List.of(Map.of("supplier",supplier.id(),"send_email",0)));
        Map<String,Object> line=new LinkedHashMap<>(Map.of("item_code",item.id(),"qty",r.quantity(),"uom",item.uom(),"stock_uom",item.uom(),
                "conversion_factor",1,"schedule_date",LocalDate.now(ZoneOffset.UTC).plusDays(r.deliveryDays()).toString()));
        if(r.requisitionId()!=null) {
            line.put("material_request",r.requisitionId());
            line.put("material_request_item",requisitionItem);
        }
        doc.put("items",List.of(line));
        try { return same(rfq(client.create("Request for Quotation",doc)),digest); }
        catch(ProcurementErpException ex) {
            // Unique ERP column arbitrates concurrent writers. No blind retry of POST, including timeouts.
            if(ex.code()==ERP_CONFLICT) return same(findRfqByIdempotencyKey(r.idempotencyKey()).orElseThrow(() -> ex),digest);
            throw ex;
        }
    }
    private RfqRecord same(RfqRecord r,String digest) {
        if(!digest.equals(r.requestDigest())) throw new ProcurementErpException(ERP_CONFLICT); return r;
    }
    private Supplier supplier(JsonNode n) { return new Supplier(text(n,"name"),text(n,"supplier_name"),
            integer(n,"disabled")==0 && text(n,"custom_approval_status").equals("Approved"),text(n,"custom_capacity"),source("Supplier",n)); }
    private Item item(JsonNode n) { return new Item(text(n,"name"),text(n,"item_name"),text(n,"item_group"),
            text(n,"stock_uom"),integer(n,"custom_gpu_memory_gb"),text(n,"custom_warranty"),source("Item",n),integer(n,"disabled")==0); }
    private Requisition requisition(JsonNode n) {
        company(n); JsonNode line=single(n,"items");
        return new Requisition(text(n,"name"),text(n,"custom_department_id"),text(line,"item_code"),integer(line,"qty"),
                date(line,"schedule_date"),text(n,"status"),source("Material Request",n));
    }
    private RfqRecord rfq(JsonNode n) {
        company(n); JsonNode line=single(n,"items"),supplier=single(n,"suppliers");
        return new RfqRecord(text(n,"name"),text(n,"custom_procurement_key"),text(supplier,"supplier"),text(line,"item_code"),
                integer(line,"qty"),text(n,"status"),text(n,"custom_request_digest"),source("Request for Quotation",n));
    }
    private void company(JsonNode n) { if(!company.equals(text(n,"company"))) throw new ProcurementErpException(ERP_VALIDATION_ERROR); }
    private JsonNode single(JsonNode n,String field) {
        if(!n.path(field).isArray() || n.path(field).size()!=1) throw new ProcurementErpException(ERP_RESPONSE_INVALID);
        return n.path(field).get(0);
    }
    private <T> Page<T> page(String type,List<List<Object>> filters,int offset,int limit,Function<JsonNode,T> mapping) {
        var rows=client.list(type,filters,offset,limit);
        return new Page<>(rows.stream().map(mapping).toList(),rows.size()==limit?offset+limit:null);
    }
    static String text(JsonNode n,String field) {
        JsonNode v=n.get(field); if(v==null || !v.isTextual() || v.asText().isBlank()) throw new ProcurementErpException(ERP_RESPONSE_INVALID); return v.asText();
    }
    static BigDecimal decimal(JsonNode n,String field) {
        JsonNode v=n.get(field); if(v==null || !v.isNumber()) throw new ProcurementErpException(ERP_RESPONSE_INVALID);
        BigDecimal d=v.decimalValue(); if(d.signum()<0) throw new ProcurementErpException(ERP_RESPONSE_INVALID); return d;
    }
    static int integer(JsonNode n,String field) {
        try { return decimal(n,field).intValueExact(); } catch(ArithmeticException ex) { throw new ProcurementErpException(ERP_RESPONSE_INVALID); }
    }
    static LocalDate date(JsonNode n,String field) {
        try { return LocalDate.parse(text(n,field)); } catch(java.time.DateTimeException ex) { throw new ProcurementErpException(ERP_RESPONSE_INVALID); }
    }
    private Source source(String type,JsonNode n) {
        try { return new Source("ERPNext",type,text(n,"name"),Instant.now(),
                LocalDateTime.parse(text(n,"modified").replace(' ','T')).toInstant(ZoneOffset.UTC)); }
        catch(java.time.DateTimeException ex) { throw new ProcurementErpException(ERP_RESPONSE_INVALID); }
    }
}
