package com.agent.platform.procurement.erp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.agent.platform.procurement.erp.ProcurementErpException.Code.*;

/** HTTP contract fixtures, deliberately not represented as a real ERPNext integration test. */
class ErpNextAdapterTests {
    final ObjectMapper json=new ObjectMapper();
    HttpServer server;
    java.util.concurrent.ExecutorService executor;
    ErpNextClient client;
    ErpNextProcurementAdapter adapter;
    int status=200;
    Object response=Map.of();
    String raw, query, auth;
    long delay;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/api/resource",exchange->{
            try {
                auth=exchange.getRequestHeaders().getFirst("Authorization"); query=exchange.getRequestURI().getRawQuery();
                if(delay>0) Thread.sleep(delay);
                byte[] body=(raw==null?json.writeValueAsString(Map.of("data",response)):raw).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,body.length); exchange.getResponseBody().write(body);
            } catch(InterruptedException ex){Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });
        server.start(); client=client(Duration.ofSeconds(2)); adapter=new ErpNextProcurementAdapter(client,"Synthetic AI Labs");
    }
    ErpNextClient client(Duration timeout) {return new ErpNextClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-key","test-secret",timeout);}
    @AfterEach void stop(){server.stop(0);executor.shutdownNow();}
    Map<String,Object> row(Object... fields){
        Map<String,Object> r=new LinkedHashMap<>(Map.of("name","ID-1","modified","2026-10-01 01:02:03.123456"));
        for(int i=0;i<fields.length;i+=2)r.put((String)fields[i],fields[i+1]);return r;
    }
    @Test void supplierAndItemMappingHasCanonicalProvenance(){
        response=row("supplier_name","Fictional Supplier","disabled",0,"custom_approval_status","Approved","custom_capacity","HIGH");
        var s=adapter.getSupplier("ID-1").orElseThrow();assertTrue(s.approved());assertEquals("Fictional Supplier",s.name());
        assertEquals("ERPNext",s.source().backend());assertEquals("Supplier",s.source().entityType());
        assertEquals(Instant.parse("2026-10-01T01:02:03.123456Z"),s.source().version());assertEquals("token test-key:test-secret",auth);
        response=row("item_name","Workstation","item_group","AI Workstation","stock_uom","Nos","custom_gpu_memory_gb",24,"custom_warranty","3 years");
        var item=adapter.getItem("ID-1").orElseThrow();assertEquals(24,item.gpuMemoryGb());assertEquals("Nos",item.uom());
    }
    @Test void decimalBudgetAndPurchaseHistoryMapping(){
        response=List.of(row("budget_amount",new BigDecimal("300000.27"),"custom_committed_amount",new BigDecimal("100.12"),"custom_currency","CNY","from_fiscal_year","2026"));
        assertEquals(new BigDecimal("299900.15"),adapter.getBudgetStatus("V2-ALG").available());
        response=List.of(row("supplier","SUP","item","SKU","quantity",20,"unit_price",new BigDecimal("12800.25"),"currency","CNY","ordered_on","2026-01-01","delivered_on","2026-01-15"));
        var history=adapter.getPurchaseHistory("SUP",0,50).records().get(0);
        assertEquals(new BigDecimal("12800.25"),history.unitPrice());assertEquals(LocalDate.of(2026,1,15),history.deliveredOn());
    }
    @Test void relationPerformanceRequisitionAndRfqMapping(){
        response=List.of(row("item","SKU","supplier","SUP","reference_price",12800,"currency","CNY","lead_time_days",12,"capacity_units",80));
        assertEquals(80,adapter.listItemSuppliers("SKU",0,50).records().get(0).capacityUnits());
        response=row("supplier","SUP","transaction_count",6,"on_time_rate",0.98,"quality_score",96);
        assertEquals(new BigDecimal("0.98"),adapter.getSupplierPerformance("SUP").orElseThrow().onTimeRate());
        response=row("company","Synthetic AI Labs","custom_department_id","V2-ALG","status","Draft","items",List.of(Map.of("item_code","SKU","qty",20.0,"schedule_date","2026-10-31")));
        assertEquals(20,adapter.getRequisition("ID-1").orElseThrow().quantity());
        response=row("company","Synthetic AI Labs","status","Draft","custom_procurement_key","rfq:1","custom_request_digest","digest","items",List.of(Map.of("item_code","SKU","qty",20)),"suppliers",List.of(Map.of("supplier","SUP")));
        assertEquals("rfq:1",adapter.getRfq("ID-1").orElseThrow().idempotencyKey());
    }
    @Test void paginationEmptyNotFoundAndConflictingBudget(){
        response=List.of(row("supplier_name","S","disabled",0,"custom_approval_status","Approved","custom_capacity","HIGH"));
        assertEquals(3,adapter.listSuppliers(2,1).nextOffset());assertTrue(query.contains("limit_start=2"));assertTrue(query.contains("limit_page_length=1"));
        response=List.of();assertNull(adapter.listSuppliers(3,1).nextOffset());
        assertCode(ERP_NOT_FOUND,()->adapter.getBudgetStatus("missing"));
        response=List.of(row(),row());assertCode(ERP_CONFLICT,()->adapter.getBudgetStatus("ambiguous"));
        status=404;assertTrue(adapter.getSupplier("missing").isEmpty());
    }
    @Test void errorsAreTypedAndNeverExposeRemoteBody(){
        raw="private credentials and stack trace";
        for(var pair:Map.of(401,ERP_AUTH_ERROR,403,ERP_AUTH_ERROR,409,ERP_CONFLICT,417,ERP_VALIDATION_ERROR,422,ERP_VALIDATION_ERROR,503,ERP_UNAVAILABLE).entrySet()){
            status=pair.getKey();var ex=assertCode(pair.getValue(),()->client.get("Supplier","x"));assertFalse(ex.getMessage().contains(raw));
        }
        status=500;raw="{\"exc_type\":\"DuplicateEntryError\"}";assertCode(ERP_CONFLICT,()->client.get("Supplier","x"));
        status=417;raw="{\"exc_type\":\"UniqueValidationError\"}";assertCode(ERP_CONFLICT,()->client.get("Supplier","x"));
        status=200;raw="not json";assertCode(ERP_RESPONSE_INVALID,()->client.get("Supplier","x"));
        raw="{\"data\":null}";assertCode(ERP_RESPONSE_INVALID,()->client.get("Supplier","x"));
    }
    @Test void timeoutAndUnavailableDoNotFallback(){
        delay=300;assertCode(ERP_TIMEOUT,()->client(Duration.ofMillis(40)).get("Supplier","x"));
        server.stop(0);assertCode(ERP_UNAVAILABLE,()->client.get("Supplier","x"));
    }
    @Test void invalidNumbersDatesAndWrongCompanyFailClosed(){
        response=row("supplier","SUP","transaction_count",6,"on_time_rate",1.2,"quality_score",96);
        assertCode(ERP_RESPONSE_INVALID,()->adapter.getSupplierPerformance("SUP"));
        response=row("company","other","custom_department_id","V2-ALG","status","Draft","items",List.of());
        assertCode(ERP_VALIDATION_ERROR,()->adapter.getRequisition("x"));
        assertCode(ERP_RESPONSE_INVALID,()->ErpNextProcurementAdapter.integer(json.valueToTree(Map.of("n",1.5)),"n"));
        assertCode(ERP_RESPONSE_INVALID,()->ErpNextProcurementAdapter.date(json.valueToTree(Map.of("d","invalid")),"d"));
        assertCode(ERP_VALIDATION_ERROR,()->client.list("Supplier",List.of(),0,201));
    }
    @Test void rfqCreatesOnceAndReconcilesUniqueRaceWithoutBlindPostRetry(){
        ErpNextClient transport=org.mockito.Mockito.mock(ErpNextClient.class);
        var erp=new ErpNextProcurementAdapter(transport,"Synthetic AI Labs");
        var stored=new java.util.concurrent.atomic.AtomicReference<tools.jackson.databind.JsonNode>();
        AtomicInteger posts=new AtomicInteger();
        org.mockito.Mockito.when(transport.list(org.mockito.ArgumentMatchers.eq("Request for Quotation"),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(0),org.mockito.ArgumentMatchers.eq(2)))
                .thenAnswer(i->stored.get()==null?List.of():List.of(stored.get()));
        org.mockito.Mockito.when(transport.get("Request for Quotation","ID-1")).thenAnswer(i->Optional.ofNullable(stored.get()));
        org.mockito.Mockito.when(transport.get("Supplier","SUP")).thenReturn(Optional.of(json.valueToTree(row("name","SUP","supplier_name","Fictional","disabled",0,"custom_approval_status","Approved","custom_capacity","HIGH"))));
        org.mockito.Mockito.when(transport.get("Item","SKU")).thenReturn(Optional.of(json.valueToTree(row("name","SKU","item_name","WS","item_group","AI Workstation","stock_uom","Nos","custom_gpu_memory_gb",24,"custom_warranty","3 years"))));
        org.mockito.Mockito.when(transport.list(org.mockito.ArgumentMatchers.eq("Procurement Item Supplier"),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(0),org.mockito.ArgumentMatchers.eq(200)))
                .thenReturn(List.of(json.valueToTree(row("item","SKU","supplier","SUP","reference_price",12800,"currency","CNY","lead_time_days",12,"capacity_units",80))));
        org.mockito.Mockito.when(transport.create(org.mockito.ArgumentMatchers.eq("Request for Quotation"),org.mockito.ArgumentMatchers.anyMap())).thenAnswer(i->{
            posts.incrementAndGet();Map<String,Object> doc=new LinkedHashMap<>(i.getArgument(1));doc.put("name","ID-1");doc.put("modified","2026-10-01 00:00:00");
            stored.set(json.valueToTree(doc));throw new ProcurementErpException(ERP_CONFLICT);
        });
        var request=new ProcurementErpAdapter.RfqRequest("rfq:1","SUP","SKU",20,21,"CNY");
        var first=erp.createRfq(request);assertEquals(first.id(),erp.createRfq(request).id());assertEquals(1,posts.get());
        assertCode(ERP_CONFLICT,()->erp.createRfq(new ProcurementErpAdapter.RfqRequest("rfq:1","SUP","SKU",21,21,"CNY")));
        assertEquals(1,posts.get());
    }
    static ProcurementErpException assertCode(ProcurementErpException.Code code,org.junit.jupiter.api.function.Executable action){
        var ex=assertThrows(ProcurementErpException.class,action);assertEquals(code,ex.code());return ex;
    }
}
