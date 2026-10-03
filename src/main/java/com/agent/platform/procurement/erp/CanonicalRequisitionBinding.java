package com.agent.platform.procurement.erp;

import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionState;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;

/** Server-owned binding from successful, exact requisition reads in this Run; never proposal arguments. */
public final class CanonicalRequisitionBinding {
    private CanonicalRequisitionBinding() { }

    public static String resolve(List<ToolExecutionRecord> executions, ProcurementCaseState state, ObjectMapper json) {
        var ids=new LinkedHashSet<String>();
        for(var execution:executions) {
            if(execution==null || execution.state()!=ToolExecutionState.SUCCEEDED || execution.result()==null
                    || !execution.result().success() || !"mcp.erpnext.procurement_get_requisition".equals(execution.toolName())) continue;
            var node=json.readTree(execution.result().content()).get("record");
            if(node==null || !node.isObject()) throw new IllegalArgumentException("canonical requisition result missing");
            var requisition=json.treeToValue(node,ProcurementErpAdapter.Requisition.class);
            if(requisition.id()==null || requisition.id().isBlank() || requisition.source()==null
                    || requisition.source().version()==null || !requisition.id().equals(requisition.source().entityId())
                    || !requisition.id().equals(execution.request().arguments().get("id")))
                throw new IllegalArgumentException("canonical requisition provenance mismatch");
            if(requisition.itemId().equals(state.productDescription())
                    && Integer.valueOf(requisition.quantity()).equals(state.quantity())) ids.add(requisition.id());
        }
        if(ids.size()>1) throw new IllegalArgumentException("multiple requisitions match this Case; RFQ binding is ambiguous");
        return ids.isEmpty()?null:ids.iterator().next();
    }
}
