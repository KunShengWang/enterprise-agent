package com.agent.platform.procurement.erp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.agent.platform.procurement.erp.ProcurementErpAdapter.*;
import static com.agent.platform.procurement.erp.ProcurementErpException.Code.*;

/** MCP domain tools depend only on ProcurementErpAdapter. There is no HTTP/fixture reader here. */
public final class ProcurementMcpService {
    public static final List<String> READ_TOOLS=List.of("procurement_get_requisition","procurement_list_requisitions",
            "procurement_get_item","procurement_list_items","procurement_get_budget_status","procurement_list_suppliers",
            "procurement_get_supplier","procurement_list_item_suppliers","procurement_get_purchase_history",
            "procurement_get_supplier_performance","procurement_get_rfq");
    private final ProcurementErpAdapter adapter;
    private final RfqAuthorization authorization;
    private final String department;
    private final ObjectMapper json=new ObjectMapper();
    public ProcurementMcpService(ProcurementErpAdapter adapter,RfqAuthorization authorization,String department) {
        this.adapter=adapter; this.authorization=authorization; this.department=department;
    }
    public List<Map<String,Object>> tools() {
        List<Map<String,Object>> tools=new ArrayList<>();
        tools.add(tool("procurement_get_requisition","Read a saved requisition before interpreting its item, department and deadline.",Map.of("id",string()),List.of("id"),Requisition.class,false));
        tools.add(tool("procurement_list_requisitions","Find saved enterprise requisitions; use nextOffset to read subsequent pages.",paging(),List.of(),Requisition.class,true));
        tools.add(tool("procurement_get_item","Read an exact SKU and GPU specification before sourcing. Use its id as Case productDescription.",Map.of("id",string()),List.of("id"),Item.class,false));
        tools.add(tool("procurement_list_items","Discover AI workstation SKUs before selecting a concrete item. Never invent an item code.",paging(),List.of(),Item.class,true));
        tools.add(tool("procurement_get_budget_status","Read ERP departmental budget available for procurement; this is not an Agent estimate or a reservation.",Map.of("departmentId",string()),List.of("departmentId"),BudgetStatus.class,false));
        tools.add(tool("procurement_list_suppliers","Discover approved/inactive suppliers. Inactive suppliers must not be recommended.",paging(),List.of(),Supplier.class,true));
        tools.add(tool("procurement_get_supplier","Read supplier approval and capacity before interpreting an item-supplier relationship.",Map.of("id",string()),List.of("id"),Supplier.class,false));
        tools.add(tool("procurement_list_item_suppliers","Read supported suppliers, reference prices, delivery days and unit capacity for a known SKU. Reference prices are synthetic ERP facts, not binding quotations.",with(paging(),"itemId",string()),List.of("itemId"),ItemSupplier.class,true));
        tools.add(tool("procurement_get_purchase_history","Read historical transactions for a supplier when comparing sourcing experience. This does not create purchase orders.",with(paging(),"supplierId",string()),List.of("supplierId"),PurchaseHistory.class,true));
        tools.add(tool("procurement_get_supplier_performance","Read historical delivery and quality observations when evaluating sourcing reliability. Never modify supplier records.",Map.of("supplierId",string()),List.of("supplierId"),SupplierPerformance.class,false));
        tools.add(tool("procurement_get_rfq","Read an RFQ by id or reconcile a stored idempotency key; provide exactly one selector. Does not create an RFQ.",Map.of("id",string(),"idempotencyKey",string()),List.of(),RfqRecord.class,false));
        tools.add(tool("procurement_create_rfq","Backend-only approved RFQ creation. Requires a short-lived server signature after HITL; never call from the model directly.",Map.of("request",schema(RfqRequest.class),"expiresAt",Map.of("type","integer"),"signature",string()),List.of("request","expiresAt","signature"),RfqRecord.class,false));
        // Existing V0 Provider backend protocol, not additional model-facing tools.
        tools.add(Map.of("name","search_suppliers","description","Internal ProcurementDataProvider compatibility: exact SKU supplier discovery.",
                "inputSchema",object(Map.of("productCategory",string(),"productDescription",string()),List.of("productCategory","productDescription")),
                "outputSchema",object(Map.of("suppliers",Map.of("type","array","items",object(Map.of("supplierId",string(),"supplierName",string()),List.of("supplierId","supplierName"))),"sourceSnapshot",string(),"sourceAsOf",string()),List.of("suppliers","sourceSnapshot","sourceAsOf")),
                "annotations",Map.of("readOnlyHint",true)));
        tools.add(Map.of("name","get_offers","description","Internal ProcurementDataProvider compatibility: ERP reference offers and provenance.",
                "inputSchema",object(Map.of("productCategory",string(),"productDescription",string(),"quantity",Map.of("type","integer","minimum",1),"currency",string(),"supplierIds",Map.of("type","array","items",string())),List.of("productCategory","productDescription","quantity","currency","supplierIds")),
                "outputSchema",object(Map.of("offers",Map.of("type","array","items",object(Map.of("supplierId",string(),"productId",string(),"productName",string(),"unitPrice",Map.of("type","number"),"currency",string(),"leadTimeDays",Map.of("type","integer"),"warranty",string(),"specifications",Map.of("type","object"),"sourceRecordId",string()),List.of("supplierId","productId","productName","unitPrice","currency","leadTimeDays","warranty","specifications","sourceRecordId"))),"sourceSnapshot",string(),"sourceAsOf",string()),List.of("offers","sourceSnapshot","sourceAsOf")),
                "annotations",Map.of("readOnlyHint",true)));
        return List.copyOf(tools);
    }
    public Object call(String name,JsonNode args) {
        var definition=tools().stream().filter(t->t.get("name").equals(name)).findFirst().orElseThrow(()->new ProcurementErpException(ERP_VALIDATION_ERROR));
        validate(json.valueToTree(definition.get("inputSchema")),args);
        int offset=args.path("offset").asInt(0),limit=args.path("limit").asInt(50);
        return switch(name) {
            case "procurement_get_requisition" -> record(adapter.getRequisition(text(args,"id")));
            case "procurement_list_requisitions" -> adapter.listRequisitions(offset,limit);
            case "procurement_get_item" -> record(adapter.getItem(text(args,"id")));
            case "procurement_list_items" -> adapter.listItems(offset,limit);
            case "procurement_get_budget_status" -> Map.of("record",adapter.getBudgetStatus(text(args,"departmentId")));
            case "procurement_list_suppliers" -> adapter.listSuppliers(offset,limit);
            case "procurement_get_supplier" -> record(adapter.getSupplier(text(args,"id")));
            case "procurement_list_item_suppliers" -> adapter.listItemSuppliers(text(args,"itemId"),offset,limit);
            case "procurement_get_purchase_history" -> adapter.getPurchaseHistory(text(args,"supplierId"),offset,limit);
            case "procurement_get_supplier_performance" -> record(adapter.getSupplierPerformance(text(args,"supplierId")));
            case "procurement_get_rfq" -> {
                if(args.has("id")==args.has("idempotencyKey")) throw new ProcurementErpException(ERP_VALIDATION_ERROR);
                yield record(args.has("id")?adapter.getRfq(text(args,"id")):adapter.findRfqByIdempotencyKey(text(args,"idempotencyKey")));
            }
            case "procurement_create_rfq" -> {
                RfqRequest r=json.treeToValue(args.get("request"),RfqRequest.class);
                authorization.verify(r,args.get("expiresAt").longValue(),text(args,"signature"));
                yield Map.of("record",adapter.createRfq(r));
            }
            case "search_suppliers" -> search(text(args,"productDescription"));
            case "get_offers" -> offers(args);
            default -> throw new ProcurementErpException(ERP_VALIDATION_ERROR);
        };
    }
    private Object search(String itemId) {
        Item item=adapter.getItem(itemId).orElseThrow(()->new ProcurementErpException(ERP_NOT_FOUND));
        var suppliers=relations(itemId).stream().map(r -> adapter.getSupplier(r.supplierId()).orElseThrow(()->new ProcurementErpException(ERP_RESPONSE_INVALID)))
                .filter(Supplier::approved).distinct().map(s -> Map.of("supplierId",s.id(),"supplierName",s.name())).toList();
        return Map.of("suppliers",suppliers,"sourceSnapshot",reference(item.source()),"sourceAsOf",item.source().version().toString());
    }
    private Object offers(JsonNode args) {
        String id=text(args,"productDescription"); int qty=args.path("quantity").intValue();
        Item item=adapter.getItem(id).orElseThrow(()->new ProcurementErpException(ERP_NOT_FOUND));
        BudgetStatus budget=adapter.getBudgetStatus(department);
        Set<String> ids=new HashSet<>(); args.path("supplierIds").forEach(v->ids.add(v.asText()));
        List<Map<String,Object>> offers=new ArrayList<>();
        for(var relation:relations(id)) {
            if(!ids.contains(relation.supplierId())) continue;
            Supplier s=adapter.getSupplier(relation.supplierId()).orElseThrow(()->new ProcurementErpException(ERP_RESPONSE_INVALID));
            SupplierPerformance p=adapter.getSupplierPerformance(s.id()).orElseThrow(()->new ProcurementErpException(ERP_RESPONSE_INVALID));
            if(!s.approved() || qty>relation.capacityUnits() || !relation.currency().equals(text(args,"currency")) ||
                    !budget.currency().equals(relation.currency()) || relation.referencePrice().multiply(BigDecimal.valueOf(qty)).compareTo(budget.available())>0) continue;
            Map<String,Object> specs=new TreeMap<>();
            specs.put("gpuMemoryGb",item.gpuMemoryGb()); specs.put("capacityUnits",relation.capacityUnits());
            specs.put("itemActive",item.active());
            specs.put("itemSource",Map.of("backend",item.source().backend(),"entityType",item.source().entityType(),
                    "entityId",item.id(),"version",item.source().version().toString()));
            specs.put("onTimeRate",p.onTimeRate()); specs.put("qualityScore",p.qualityScore()); specs.put("transactionCount",p.transactionCount());
            specs.put("performanceEntity",p.source().entityType()+":"+p.source().entityId());
            specs.put("performanceVersion",p.source().version().toString()); specs.put("priceBasis","ERP_REFERENCE_PRICE");
            specs.put("budgetId",budget.id()); specs.put("departmentId",department); specs.put("budgetAvailable",budget.available());
            specs.put("budgetVersion",budget.source().version().toString()); specs.put("supplierVersion",s.source().version().toString());
            offers.add(Map.of("supplierId",s.id(),"productId",item.id(),"productName",item.name(),"unitPrice",relation.referencePrice(),
                    "currency",relation.currency(),"leadTimeDays",relation.leadTimeDays(),"warranty",item.warranty(),"specifications",specs,
                    "sourceRecordId",reference(relation.source())));
        }
        // Individual OFFER content/digest includes live ERP performance/version. No filesystem fallback.
        return Map.of("offers",offers,"sourceSnapshot",reference(item.source()),"sourceAsOf",item.source().version().toString());
    }
    private static String reference(Source source) {
        return source.backend()+":"+source.entityType()+":"+source.entityId()+":"+source.version();
    }
    private List<ItemSupplier> relations(String id) {
        List<ItemSupplier> all=new ArrayList<>(); Integer offset=0;
        while(offset!=null) {
            if(offset>10000) throw new ProcurementErpException(ERP_RESPONSE_INVALID);
            var page=adapter.listItemSuppliers(id,offset,200); all.addAll(page.records()); offset=page.nextOffset();
        }
        return all;
    }
    private Object record(Optional<?> value) { return Map.of("record",value.orElseThrow(()->new ProcurementErpException(ERP_NOT_FOUND))); }
    private String text(JsonNode n,String field) { return n.path(field).asText(); }
    private Map<String,Object> tool(String name,String description,Map<String,Object> fields,List<String> required,Class<?> result,boolean page) {
        Object output=page?object(Map.of("records",Map.of("type","array","items",schema(result)),"nextOffset",Map.of("type",List.of("integer","null"))),List.of("records","nextOffset")):
                object(Map.of("record",schema(result)),List.of("record"));
        return Map.of("name",name,"description",description,"inputSchema",object(fields,required),"outputSchema",output,
                "annotations",Map.of("readOnlyHint",!name.equals("procurement_create_rfq"),"destructiveHint",false,"idempotentHint",true));
    }
    private static Map<String,Object> string() { return Map.of("type","string","minLength",1,"maxLength",500); }
    private static Map<String,Object> paging() { return Map.of("offset",Map.of("type","integer","minimum",0,"maximum",10000),"limit",Map.of("type","integer","minimum",1,"maximum",200)); }
    private static Map<String,Object> with(Map<String,Object> values,String key,Object value) { var m=new LinkedHashMap<>(values); m.put(key,value); return m; }
    private static Map<String,Object> object(Map<String,Object> fields,List<String> required) { return Map.of("type","object","additionalProperties",false,"properties",fields,"required",required); }
    private static Object schema(Class<?> type) {
        if(type.isRecord()) {
            Map<String,Object> fields=new LinkedHashMap<>(); for(var c:type.getRecordComponents()) fields.put(c.getName(),schema(c.getType()));
            List<String> required=new ArrayList<>(fields.keySet());
            if(type==RfqRequest.class) required.remove("requisitionId");
            return object(fields,required);
        }
        if(type==boolean.class) return Map.of("type","boolean");
        if(type==int.class) return Map.of("type","integer","minimum",0,"maximum",Integer.MAX_VALUE);
        if(type==BigDecimal.class) return Map.of("type","number");
        if(type==Instant.class) return Map.of("type","string","format","date-time");
        if(type==java.time.LocalDate.class) return Map.of("type","string","format","date");
        return string();
    }
    static void validate(JsonNode schema,JsonNode value) {
        boolean ok=switch(schema.path("type").asText()) {
            case "object" -> value!=null&&value.isObject(); case "array" -> value!=null&&value.isArray();
            case "string" -> value!=null&&value.isTextual()&&!value.asText().isBlank()&&value.asText().length()<=500;
            case "integer" -> value!=null&&value.isIntegralNumber()&&value.canConvertToLong()
                    && (schema.has("maximum") || value.canConvertToInt() || !schema.has("minimum"));
            case "number" -> value!=null&&value.isNumber(); case "boolean" -> value!=null&&value.isBoolean(); default -> false;
        };
        if(!ok) throw new ProcurementErpException(ERP_VALIDATION_ERROR);
        if(value.isObject()) {
            for(JsonNode key:schema.path("required")) if(!value.hasNonNull(key.asText())) throw new ProcurementErpException(ERP_VALIDATION_ERROR);
            for(var entry:value.properties()) {
                JsonNode child=schema.path("properties").get(entry.getKey());
                if(child==null) throw new ProcurementErpException(ERP_VALIDATION_ERROR);
                validate(child,entry.getValue());
            }
        }
        if(value.isArray()) for(JsonNode item:value) validate(schema.path("items"),item);
        if(value.isNumber() && ((schema.has("minimum")&&value.decimalValue().compareTo(schema.get("minimum").decimalValue())<0)||
                (schema.has("maximum")&&value.decimalValue().compareTo(schema.get("maximum").decimalValue())>0))) throw new ProcurementErpException(ERP_VALIDATION_ERROR);
    }
}
