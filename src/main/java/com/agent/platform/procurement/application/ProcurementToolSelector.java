package com.agent.platform.procurement.application;

import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.model.ProcurementCaseStatus;
import com.agent.platform.procurement.tool.ProcurementToolCatalog;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.ToolDefinition;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.agent.platform.procurement.tool.ProcurementToolCatalog.*;

/** Central contract-backed rule table; derived exposure labels are never persisted as business states. */
public final class ProcurementToolSelector implements ToolSelector {
    private static final String ERP = "mcp.erpnext.";
    private enum Group { CORE_READ, ITEM_DISCOVERY, REQUISITION_DISCOVERY, SUPPLIER_DISCOVERY, SUPPLIER_READ, CASE, SEARCH, FINALIZE, RFQ }
    private static final Map<String, Group> GROUPS = Map.ofEntries(
            Map.entry(CASE_PATCH, Group.CASE), Map.entry(SUPPLIER_SEARCH, Group.SEARCH),
            Map.entry(RECOMMENDATION_FINALIZE, Group.FINALIZE), Map.entry(CREATE_RFQ, Group.RFQ),
            Map.entry(ERP+"procurement_get_requisition", Group.CORE_READ),
            Map.entry(ERP+"procurement_get_item", Group.CORE_READ),
            Map.entry(ERP+"procurement_get_budget_status", Group.CORE_READ),
            Map.entry(ERP+"procurement_get_rfq", Group.CORE_READ),
            Map.entry(ERP+"procurement_list_items", Group.ITEM_DISCOVERY),
            Map.entry(ERP+"procurement_list_requisitions", Group.REQUISITION_DISCOVERY),
            Map.entry(ERP+"procurement_list_suppliers", Group.SUPPLIER_DISCOVERY),
            Map.entry(ERP+"procurement_get_supplier", Group.SUPPLIER_READ),
            Map.entry(ERP+"procurement_list_item_suppliers", Group.SUPPLIER_READ),
            Map.entry(ERP+"procurement_get_purchase_history", Group.SUPPLIER_READ),
            Map.entry(ERP+"procurement_get_supplier_performance", Group.SUPPLIER_READ));
    private final TokenEstimator estimator;
    private final ObjectMapper json = new ObjectMapper();
    public ProcurementToolSelector(TokenEstimator estimator) { this.estimator = estimator; }

    @Override
    public Selection select(AgentExecutionProfile profile, ToolSelectionState business, AgentRunRecord run,
                            List<ToolExecutionRecord> executions, List<ToolDefinition> candidates) {
        if (!profile.stageToolSelectionEnabled()) return new Selection(candidates, Map.of());
        String stage = stage(profile, business, run, executions);
        List<ToolDefinition> selected = new ArrayList<>();
        Map<String,String> hidden = new LinkedHashMap<>();
        for (ToolDefinition tool : candidates) {
            Group group = GROUPS.get(tool.name());
            if (!profile.allows(tool.name()) || group == null || !validContract(tool, group)) {
                hidden.put(tool.name(), "UNKNOWN_OR_INVALID_CONTRACT"); continue;
            }
            if (Boolean.TRUE.equals(tool.metadata().get("singleUse")) && run != null && run.usedTools().contains(tool.name())) {
                hidden.put(tool.name(), "SINGLE_USE_CONSUMED"); continue;
            }
            boolean visible = group == Group.CORE_READ;
            visible |= switch (stage) {
                case "REQUEST_ANALYSIS" -> EnumSet.of(Group.CASE,Group.ITEM_DISCOVERY,Group.REQUISITION_DISCOVERY,
                        Group.SUPPLIER_DISCOVERY,Group.SUPPLIER_READ).contains(group);
                case "SOURCING" -> group != Group.RFQ && group != Group.REQUISITION_DISCOVERY;
                case "RFQ_PREPARATION" -> group == Group.CASE || group == Group.ITEM_DISCOVERY || group == Group.RFQ
                        || tool.name().equals(ERP+"procurement_get_supplier");
                case "POST_RFQ" -> tool.name().equals(ERP+"procurement_get_supplier");
                default -> false;
            };
            if (visible && !stage.equals("UNSUPPORTED_AGENT")) selected.add(tool);
            else hidden.put(tool.name(), "HIDDEN_AT_"+stage);
        }
        Map<String,Object> metrics = new LinkedHashMap<>();
        metrics.put("selection_stage",stage);
        metrics.put("candidate_tools",candidates.stream().map(ToolDefinition::name).toList());
        metrics.put("selected_tool_names",selected.stream().map(ToolDefinition::name).toList());
        metrics.put("hidden_tools",hidden);
        metrics.put("visible_tool_count",selected.size());
        metrics.put("candidate_tool_count",candidates.size());
        metrics.put("hidden_tool_count",hidden.size());
        metrics.put("tool_schema_estimated_tokens",tokens(selected));
        metrics.put("candidate_schema_estimated_tokens",tokens(candidates));
        metrics.put("schema_token_accounting","estimated; ConservativeTokenEstimator; excludes provider framing");
        return new Selection(selected,metrics);
    }

    private String stage(AgentExecutionProfile profile, ToolSelectionState state, AgentRunRecord run,
                         List<ToolExecutionRecord> executions) {
        if (!ProcurementSourcingExecutionProfileFactory.PROFILE_NAME.equals(profile.name())) return "UNSUPPORTED_AGENT";
        if (run == null || state == null || !state.authoritative()) return "SAFE_READ_ONLY";
        if (run.state() == AgentRunState.WAITING_APPROVAL || run.pendingToolCall() != null) return "PENDING_APPROVAL";
        if (run.state() != AgentRunState.RUNNING && run.state() != AgentRunState.CREATED) return "SAFE_READ_ONLY";
        if (run.usedTools().contains(CREATE_RFQ)) return "POST_RFQ";
        if (!state.caseExists()) return "UNCREATED".equals(state.caseStatus()) ? "REQUEST_ANALYSIS" : "SAFE_READ_ONLY";
        if (state.caseId() == null || state.caseId().isBlank() || state.caseVersion()<0) return "SAFE_READ_ONLY";
        ProcurementCaseStatus status;
        try { status = ProcurementCaseStatus.valueOf(state.caseStatus()); }
        catch (RuntimeException invalid) { return "SAFE_READ_ONLY"; }
        if (status == ProcurementCaseStatus.CLARIFICATION_REQUIRED) return "REQUEST_ANALYSIS";
        if (!state.complete()) return "SAFE_READ_ONLY";
        if (state.rfqFieldsReady() && currentFinalize(state,run,executions)) return "RFQ_PREPARATION";
        return "SOURCING";
    }

    private boolean currentFinalize(ToolSelectionState state, AgentRunRecord run, List<ToolExecutionRecord> executions) {
        if (executions == null) return false;
        var latest = executions.stream().filter(e -> e != null && Objects.equals(e.runId(),run.runId())
                && e.state()==ToolExecutionState.SUCCEEDED && RECOMMENDATION_FINALIZE.equals(e.toolName())
                && e.result()!=null && e.result().success())
                .max(Comparator.comparing(ToolExecutionRecord::updatedAt).thenComparing(ToolExecutionRecord::createdAt));
        if (latest.isEmpty()) return false;
        try {
            var value=json.readTree(latest.get().result().content());
            return "verified-provider-snapshot".equals(value.path("source").asText())
                    && state.caseId().equals(value.path("caseId").asText())
                    && value.path("caseVersion").isIntegralNumber() && state.caseVersion()==value.path("caseVersion").asLong()
                    && !value.path("recommendation").path("recommendedSupplier").path("supplierId").asText().isBlank()
                    && value.path("recommendation").path("recommendedSupplier").path("supplierId").asText()
                       .equals(value.path("recommendation").path("selectedOffer").path("supplierId").asText());
        } catch (RuntimeException invalid) { return false; }
    }

    private boolean validContract(ToolDefinition t, Group group) {
        if (t.inputSchema()==null || t.inputSchema().isBlank()) return false;
        try {
            if (!"object".equals(json.readTree(t.inputSchema()).path("type").asText())) return false;
        } catch (RuntimeException invalid) { return false; }
        if (t.name().startsWith(ERP)) return "mcp".equals(t.metadata().get("provider"))
                && "erpnext".equals(t.metadata().get("mcpServerId"))
                && !Boolean.TRUE.equals(t.metadata().get("sideEffect"))
                && !Boolean.FALSE.equals(t.metadata().get("readOnly"))
                && (!t.metadata().containsKey("effect") || "READ_ONLY".equals(t.metadata().get("effect")))
                && t.name().substring(ERP.length()).equals(t.metadata().get("mcpToolName"));
        if (!"procurement".equals(t.metadata().get("provider"))) return false;
        return switch (group) {
            case CASE -> Boolean.FALSE.equals(t.metadata().get("readOnly")) && Boolean.TRUE.equals(t.metadata().get("sideEffect"));
            case RFQ -> Boolean.FALSE.equals(t.metadata().get("readOnly")) && Boolean.TRUE.equals(t.metadata().get("sideEffect")) && Boolean.TRUE.equals(t.metadata().get("singleUse"))
                    && t.riskLevel()==com.agent.platform.tool.ToolRiskLevel.HIGH;
            default -> Boolean.TRUE.equals(t.metadata().get("readOnly")) && Boolean.FALSE.equals(t.metadata().get("sideEffect"));
        };
    }
    private long tokens(List<ToolDefinition> tools) {
        return tools.stream().mapToLong(t -> estimator.estimate(t.name())+estimator.estimate(t.description())+estimator.estimate(t.inputSchema())).sum();
    }
}
