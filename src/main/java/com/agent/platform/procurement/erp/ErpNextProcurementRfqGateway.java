package com.agent.platform.procurement.erp;

import com.agent.platform.mcp.McpToolGateway;
import com.agent.platform.procurement.tool.ProcurementRfqGateway;
import com.agent.platform.tool.ToolCallRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static com.agent.platform.procurement.erp.ProcurementErpException.Code.*;

/** Invoked by the existing RFQ handler only after exact Approval/Case/version validation. */
@Component
@ConditionalOnProperty(prefix="enterprise-agent.procurement.erpnext",name="enabled",havingValue="true")
public class ErpNextProcurementRfqGateway implements ProcurementRfqGateway {
    private final McpToolGateway mcp;
    private final ObjectMapper json;
    private final RfqAuthorization signer;
    private final ErpRfqApprovalGuard approvals;
    @org.springframework.beans.factory.annotation.Autowired
    public ErpNextProcurementRfqGateway(McpToolGateway mcp,ObjectMapper json,ErpRfqApprovalGuard approvals) {
        this(mcp,json,RfqAuthorization.fromEnvironment(),approvals);
    }
    public ErpNextProcurementRfqGateway(McpToolGateway mcp,ObjectMapper json,RfqAuthorization signer,ErpRfqApprovalGuard approvals) {
        this.mcp=mcp;this.json=json;this.signer=signer;this.approvals=java.util.Objects.requireNonNull(approvals);
    }
    public Receipt create(CreateRequest r) {
        Instant approvalExpiry=approvals.requireApproved(r);
        var domain=new ProcurementErpAdapter.RfqRequest(r.idempotencyKey(),r.supplierId(),r.productDescription(),r.quantity(),r.requiredDeliveryDays(),r.currency(),r.requisitionId());
        long expires=Math.min(Instant.now().getEpochSecond()+120,approvalExpiry.getEpochSecond());
        if(expires<=Instant.now().getEpochSecond()) throw new RejectedRequestException();
        return receipt(call("procurement_create_rfq",Map.of("request",domain,"expiresAt",expires,"signature",signer.sign(domain,expires))));
    }
    public Optional<Receipt> findByIdempotencyKey(String key) {
        try { return Optional.of(receipt(call("procurement_get_rfq",Map.of("idempotencyKey",key)))); }
        catch(ProcurementErpException ex) { if(ex.code()==ERP_NOT_FOUND) return Optional.empty(); throw ex; }
    }
    private ProcurementErpAdapter.RfqRecord call(String name,Map<String,Object> args) {
        var definitions=mcp.discoverTools().stream().filter(d -> d.name().equals("mcp.erpnext."+name)).toList();
        if(definitions.size()!=1) throw new ProcurementErpException(ERP_UNAVAILABLE);
        // Convert nested records to a JSON-compatible map before entering the existing gateway.
        @SuppressWarnings("unchecked") Map<String,Object> payload=json.convertValue(args,Map.class);
        var result=mcp.callTool(definitions.get(0),new ToolCallRequest(definitions.get(0).name(),"erp-rfq-"+UUID.randomUUID(),payload));
        if(!result.success()) {
            try {
                String code=json.readTree(result.content()).path("error").path("code").asText();
                throw new ProcurementErpException(ProcurementErpException.Code.valueOf(code));
            } catch(ProcurementErpException ex) { throw ex; } catch(RuntimeException ex) { throw new ProcurementErpException(ERP_UNAVAILABLE); }
        }
        try { return json.treeToValue(json.readTree(result.content()).get("record"),ProcurementErpAdapter.RfqRecord.class); }
        catch(RuntimeException ex) { throw new ProcurementErpException(ERP_RESPONSE_INVALID); }
    }
    private Receipt receipt(ProcurementErpAdapter.RfqRecord r) {
        if(r==null || r.source()==null || !"Draft".equals(r.status())) throw new ProcurementErpException(ERP_RESPONSE_INVALID);
        // CREATED means the external draft exists, not submitted or emailed.
        return new Receipt(r.id(),r.idempotencyKey(),r.supplierId(),"CREATED",r.source().version(),"ERPNext:Request for Quotation (Draft)");
    }
}
