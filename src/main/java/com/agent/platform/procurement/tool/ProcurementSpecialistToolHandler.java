package com.agent.platform.procurement.tool;

import com.agent.platform.multiagent.MultiAgentRole;
import com.agent.platform.multiagent.SubAgentExecutionResult;
import com.agent.platform.multiagent.SubAgentRunner;
import com.agent.platform.procurement.config.ProcurementSpecialistProfileFactory;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.runtime.AgentRunState;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionState;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.tool.ContextualToolHandler;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolExecutionContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 采购 Specialist 的唯一执行边界：从当前 Run 的 Search 观察构造 filtered input，
 * 经 SubAgentRunner 获得窄 child 结果，再以结构化 advisory 结果返回给主 Agent。
 */
@Component
public class ProcurementSpecialistToolHandler implements ContextualToolHandler {
    private static final String INPUT_START = "<procurement_specialist_input trusted_instructions=\"false\">\n";
    private static final String INPUT_END = "\n</procurement_specialist_input>";

    private final ToolExecutionStore toolExecutionStore;
    private final ProcurementCaseStore caseStore;
    private final SubAgentRunner subAgentRunner;
    private final ProcurementSpecialistProfileFactory profileFactory;
    private final ObjectMapper objectMapper;

    public ProcurementSpecialistToolHandler(ToolExecutionStore toolExecutionStore,
                                            ProcurementCaseStore caseStore,
                                            SubAgentRunner subAgentRunner,
                                            ProcurementSpecialistProfileFactory profileFactory,
                                            ObjectMapper objectMapper) {
        this.toolExecutionStore = toolExecutionStore;
        this.caseStore = caseStore;
        this.subAgentRunner = subAgentRunner;
        this.profileFactory = profileFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String toolName) {
        return ProcurementToolCatalog.COMMERCIAL_ANALYSIS.equals(toolName)
                || ProcurementToolCatalog.DELIVERY_ANALYSIS.equals(toolName);
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, ToolExecutionContext context) {
        String toolName = request == null ? "" : request.toolName();
        String focus = focusFor(toolName);
        try {
            if (request == null || !supports(toolName)) {
                return failure(toolName, focus, "unsupported procurement specialist tool", "UNSUPPORTED_TOOL");
            }
            if (!request.arguments().isEmpty()) {
                return failure(toolName, focus, "specialist arguments must be empty", "ARGUMENTS_NOT_EMPTY");
            }
            if (request.requestId() == null || request.requestId().isBlank()) {
                return failure(toolName, focus, "specialist requestId is required", "REQUEST_ID_REQUIRED");
            }
            ToolExecutionContext trusted = requireIdentity(context);
            if ("true".equalsIgnoreCase(trusted.attribute("internalSubAgent"))) {
                return failure(toolName, focus,
                        "specialist delegation is not available to a child Agent", "CHILD_DELEGATION_FORBIDDEN");
            }
            ProcurementCase currentCase = caseStore.findByTenantUserAndConversationId(
                            trusted.tenantId(), trusted.userId(), trusted.sessionId())
                    .orElseThrow(() -> new IllegalArgumentException("procurement Case not found"));
            ToolExecutionRecord search = latestSearch(trusted);
            if (search == null) {
                return failure(toolName, focus,
                        "supplier search result is required before specialist analysis", "SEARCH_REQUIRED");
            }
            JsonNode searchRoot = object(search.result().content(), "supplier search result is invalid");
            long searchVersion = requiredNonNegativeLong(searchRoot, "caseVersion");
            if (currentCase.version() != searchVersion) {
                return failure(toolName, focus,
                        "supplier search result is stale; rerun procurement_supplier_search", "STALE_SEARCH");
            }
            List<JsonNode> eligible = objectArray(searchRoot, "eligibleSuppliers");
            if (eligible.size() < 2) {
                return failure(toolName, focus,
                        "specialist analysis requires at least two eligible suppliers", "SPECIALIST_NOT_APPLICABLE");
            }
            Set<String> eligibleIds = uniqueSupplierIds(eligible);
            List<JsonNode> offers = objectArray(searchRoot, "offers");
            List<JsonNode> evidence = objectArray(searchRoot, "evidence");
            Map<String, Object> packet = filteredPacket(focus, currentCase, eligible, offers, evidence);
            String instruction = focusInstruction(focus) + INPUT_START
                    + objectMapper.writeValueAsString(packet) + INPUT_END;

            SubAgentExecutionResult child = subAgentRunner.run(
                    trusted.runId(), trusted.sessionId(), trusted.userId(), request.requestId(),
                    MultiAgentRole.PROCUREMENT_ANALYST, instruction,
                    profileFactory.createProfile(MultiAgentRole.PROCUREMENT_ANALYST, focus));
            if (child == null || child.message() == null || child.childRunId().isBlank()
                    || child.childSessionId().isBlank() || child.answer().isBlank()
                    || !AgentRunState.COMPLETED.name().equals(String.valueOf(child.message().metadata().get("state")))) {
                return failure(toolName, focus, "specialist child did not complete successfully", "CHILD_FAILED");
            }
            Map<String, Object> analysis = validateChild(child.answer(), focus, eligibleIds, offers, evidence);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", "procurement-specialist-result-v1");
            result.put("focus", focus);
            result.put("caseVersion", searchVersion);
            result.put("analysis", analysis);
            result.put("childRunId", child.childRunId());
            result.put("childSessionId", child.childSessionId());
            result.put("sourceSearchToolCallId", search.toolCallId());
            result.put("advisory", true);
            result.put("authoritativeFacts", false);
            return success(toolName, focus, searchVersion, child, search.toolCallId(), result);
        }
        catch (RuntimeException exception) {
            return failure(toolName, focus, message(exception), "SPECIALIST_REJECTED");
        }
    }

    private ToolExecutionContext requireIdentity(ToolExecutionContext context) {
        if (context == null || context.runId().isBlank() || context.sessionId().isBlank()
                || context.userId().isBlank() || context.tenantId().isBlank()) {
            throw new IllegalArgumentException("trusted runId, tenantId, userId and conversationId are required");
        }
        return context;
    }

    private ToolExecutionRecord latestSearch(ToolExecutionContext context) {
        return toolExecutionStore.findByRun(context.runId()).stream()
                .filter(record -> record.state() == ToolExecutionState.SUCCEEDED
                        && record.result() != null
                        && record.result().success()
                        && ProcurementToolCatalog.SUPPLIER_SEARCH.equals(record.toolName()))
                .max(Comparator.comparing(ToolExecutionRecord::updatedAt)
                        .thenComparing(ToolExecutionRecord::createdAt))
                .orElse(null);
    }

    private Map<String, Object> filteredPacket(String focus,
                                               ProcurementCase currentCase,
                                               List<JsonNode> eligible,
                                               List<JsonNode> offers,
                                               List<JsonNode> evidence) {
        Set<String> eligibleIds = uniqueSupplierIds(eligible);
        Map<String, Object> packet = new LinkedHashMap<>();
        packet.put("caseVersion", currentCase.version());
        ProcurementCaseState state = currentCase.state();
        Map<String, Object> caseFacts = new LinkedHashMap<>();
        if ("COMMERCIAL".equals(focus)) {
            caseFacts.put("budget", state.budget());
            caseFacts.put("currency", state.currency());
            caseFacts.put("preferences", state.preferences());
        }
        else {
            caseFacts.put("requiredDeliveryDays", state.requiredDeliveryDays());
            caseFacts.put("preferences", state.preferences());
        }
        packet.put("case", caseFacts);
        packet.put("eligibleSuppliers", eligible.stream().map(this::supplierView).toList());
        packet.put("offers", offers.stream().filter(value -> eligibleIds.contains(text(value, "supplierId")))
                .map(value -> offerView(focus, value)).toList());
        packet.put("evidence", evidence.stream().filter(value -> eligibleIds.contains(text(value, "supplierId")))
                .map(this::evidenceView).toList());
        return packet;
    }

    private Map<String, Object> supplierView(JsonNode value) {
        return Map.of("supplierId", requiredText(value, "supplierId"),
                "supplierName", requiredText(value, "supplierName"));
    }

    private Map<String, Object> offerView(String focus, JsonNode value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supplierId", requiredText(value, "supplierId"));
        if ("COMMERCIAL".equals(focus)) {
            result.put("unitPrice", requiredNode(value, "unitPrice"));
            result.put("totalPrice", requiredNode(value, "totalPrice"));
            result.put("currency", requiredText(value, "currency"));
            result.put("warranty", requiredText(value, "warranty"));
        }
        else {
            result.put("leadTimeDays", requiredNode(value, "leadTimeDays"));
        }
        return result;
    }

    private Map<String, Object> evidenceView(JsonNode value) {
        return Map.of("evidenceId", requiredText(value, "evidenceId"),
                "supplierId", requiredText(value, "supplierId"),
                "evidenceType", requiredText(value, "evidenceType"),
                "fact", requiredText(value, "fact"),
                "source", requiredText(value, "source"));
    }

    private Map<String, Object> validateChild(String answer,
                                               String expectedFocus,
                                               Set<String> eligibleIds,
                                               List<JsonNode> offers,
                                               List<JsonNode> evidence) {
        JsonNode root = object(answer, "specialist output must be a JSON object");
        Set<String> expectedFields = Set.of("focus", "summary", "supplierIds", "evidenceRefs", "limitations");
        Set<String> actualFields = new HashSet<>();
        root.properties().forEach(entry -> actualFields.add(entry.getKey()));
        if (!actualFields.equals(expectedFields)) {
            throw new IllegalArgumentException("specialist output fields are invalid");
        }
        if (!expectedFocus.equals(requiredText(root, "focus"))) {
            throw new IllegalArgumentException("specialist focus does not match tool");
        }
        String summary = requiredText(root, "summary");
        if (summary.length() > 4_000) throw new IllegalArgumentException("specialist summary is too long");
        List<String> supplierIds = stringArray(root, "supplierIds", true);
        if (new HashSet<>(supplierIds).size() != supplierIds.size() || !eligibleIds.containsAll(supplierIds)) {
            throw new IllegalArgumentException("specialist supplierIds are not grounded in eligible suppliers");
        }
        List<String> evidenceRefs = stringArray(root, "evidenceRefs", true);
        if (new HashSet<>(evidenceRefs).size() != evidenceRefs.size()) {
            throw new IllegalArgumentException("specialist evidenceRefs must not contain duplicates");
        }
        Map<String, JsonNode> evidenceById = new LinkedHashMap<>();
        for (JsonNode item : evidence) {
            String id = requiredText(item, "evidenceId");
            if (evidenceById.putIfAbsent(id, item) != null) {
                throw new IllegalArgumentException("search evidence contains duplicate evidenceId");
            }
        }
        Set<String> outputSupplierIds = new HashSet<>(supplierIds);
        Set<String> offerSuppliers = new HashSet<>();
        Set<String> offerEvidenceSuppliers = new HashSet<>();
        for (JsonNode offer : offers) {
            String supplierId = text(offer, "supplierId");
            if (eligibleIds.contains(supplierId)) offerSuppliers.add(supplierId);
        }
        for (String ref : evidenceRefs) {
            JsonNode item = evidenceById.get(ref);
            if (item == null) throw new IllegalArgumentException("specialist evidenceRef is not grounded");
            String supplierId = requiredText(item, "supplierId");
            if (!eligibleIds.contains(supplierId) || !outputSupplierIds.contains(supplierId)) {
                throw new IllegalArgumentException("specialist evidenceRef is outside analyzed suppliers");
            }
            if ("OFFER".equalsIgnoreCase(requiredText(item, "evidenceType"))) {
                offerEvidenceSuppliers.add(supplierId);
            }
        }
        if (!offerEvidenceSuppliers.containsAll(outputSupplierIds)
                || !offerSuppliers.containsAll(outputSupplierIds)) {
            throw new IllegalArgumentException("each analyzed supplier requires grounded OFFER evidence");
        }
        List<String> limitations = stringArray(root, "limitations", false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("focus", expectedFocus);
        result.put("summary", summary);
        result.put("supplierIds", supplierIds);
        result.put("evidenceRefs", evidenceRefs);
        result.put("limitations", limitations);
        return result;
    }

    private List<String> stringArray(JsonNode root, String field, boolean nonEmpty) {
        JsonNode value = requiredNode(root, field);
        if (!value.isArray()) throw new IllegalArgumentException("specialist " + field + " must be an array");
        List<String> values = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException("specialist " + field + " must contain strings");
            }
            values.add(item.asText().trim());
        }
        if (nonEmpty && values.isEmpty()) {
            throw new IllegalArgumentException("specialist " + field + " must not be empty");
        }
        return List.copyOf(values);
    }

    private List<JsonNode> objectArray(JsonNode root, String field) {
        JsonNode value = requiredNode(root, field);
        if (!value.isArray()) throw new IllegalArgumentException("search result " + field + " must be an array");
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isObject()) throw new IllegalArgumentException("search result " + field + " must contain objects");
            items.add(item);
        }
        return List.copyOf(items);
    }

    private Set<String> uniqueSupplierIds(List<JsonNode> values) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode value : values) {
            String id = requiredText(value, "supplierId");
            if (!ids.add(id)) throw new IllegalArgumentException("search eligibleSuppliers contains duplicate supplierId");
        }
        return Set.copyOf(ids);
    }

    private JsonNode object(String content, String message) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !root.isObject()) throw new IllegalArgumentException(message);
            return root;
        }
        catch (RuntimeException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    private JsonNode requiredNode(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) throw new IllegalArgumentException("missing field: " + field);
        return value;
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = requiredNode(root, field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("field must be a non-blank string: " + field);
        }
        return value.asText().trim();
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private long requiredNonNegativeLong(JsonNode root, String field) {
        JsonNode value = requiredNode(root, field);
        if (!value.isIntegralNumber() || value.asLong() < 0) {
            throw new IllegalArgumentException("field must be a non-negative integer: " + field);
        }
        return value.asLong();
    }

    private String focusFor(String toolName) {
        if (ProcurementToolCatalog.COMMERCIAL_ANALYSIS.equals(toolName)) return "COMMERCIAL";
        if (ProcurementToolCatalog.DELIVERY_ANALYSIS.equals(toolName)) return "DELIVERY";
        return "UNKNOWN";
    }

    private String focusInstruction(String focus) {
        return "只做 " + focus + " 维度的证据分析，不要选择供应商；请严格按 child profile 的 JSON 协议返回。";
    }

    private ToolCallResult success(String toolName, String focus, long caseVersion,
                                   SubAgentExecutionResult child, String searchToolCallId,
                                   Map<String, Object> result) {
        return new ToolCallResult(toolName, true, write(result), "", metadata(
                toolName, focus, caseVersion, child.childRunId(), child.childSessionId(), searchToolCallId));
    }

    private ToolCallResult failure(String toolName, String focus, String message, String errorType) {
        Map<String, Object> metadata = new LinkedHashMap<>(baseMetadata(toolName, focus));
        metadata.put("retryable", false);
        metadata.put("errorType", errorType);
        return new ToolCallResult(toolName, false, "",
                message == null || message.isBlank() ? "specialist analysis rejected" : message,
                Map.copyOf(metadata));
    }

    private Map<String, Object> metadata(String toolName, String focus, long caseVersion,
                                         String childRunId, String childSessionId, String searchToolCallId) {
        Map<String, Object> metadata = new LinkedHashMap<>(baseMetadata(toolName, focus));
        metadata.put("executionKind", "SUB_AGENT");
        metadata.put("advisory", true);
        metadata.put("authoritativeFacts", false);
        metadata.put("retryable", false);
        metadata.put("caseVersion", caseVersion);
        metadata.put("childRunId", childRunId);
        metadata.put("childSessionId", childSessionId);
        metadata.put("sourceSearchToolCallId", searchToolCallId);
        return Map.copyOf(metadata);
    }

    private Map<String, Object> baseMetadata(String toolName, String focus) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "procurement");
        metadata.put("domain", "procurement");
        metadata.put("readOnly", true);
        metadata.put("sideEffect", false);
        metadata.put("focus", focus);
        metadata.put("contractVersion", "procurement-specialist-v1");
        return metadata;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (RuntimeException exception) {
            throw new IllegalArgumentException("failed to serialize specialist result", exception);
        }
    }

    private String message(RuntimeException exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? "specialist analysis rejected" : value;
    }
}
