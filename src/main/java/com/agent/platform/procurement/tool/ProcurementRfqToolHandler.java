package com.agent.platform.procurement.tool;

import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionState;
import com.agent.platform.runtime.ToolExecutionStore;
import com.agent.platform.runtime.UncertainToolExecutionResolver;
import com.agent.platform.tool.ContextualToolHandler;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolExecutionContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * RFQ 的受控副作用边界：验证 Runtime 传入的 exact prepared request，只执行一次并在异常后对账。
 */
@Component
public class ProcurementRfqToolHandler implements ContextualToolHandler, UncertainToolExecutionResolver {
    private static final Set<String> CANONICAL_ARGUMENTS = Set.of(
            "caseId", "caseVersion", "supplierId", "productCategory", "productDescription",
            "quantity", "currency", "requiredDeliveryDays", "hardConstraints",
            "sourceRecommendationToolCallId", "idempotencyKey");

    private final ProcurementRfqGateway gateway;
    private final ProcurementCaseStore caseStore;
    private final ToolExecutionStore toolExecutionStore;
    private final ObjectMapper objectMapper;

    public ProcurementRfqToolHandler(ProcurementRfqGateway gateway,
                                     ProcurementCaseStore caseStore,
                                     ToolExecutionStore toolExecutionStore,
                                     ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.caseStore = caseStore;
        this.toolExecutionStore = toolExecutionStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String toolName) {
        return ProcurementToolCatalog.CREATE_RFQ.equals(toolName);
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, ToolExecutionContext context) {
        ParsedPreparedRfq prepared;
        try {
            prepared = validatePreparedRequest(request, context);
        }
        catch (RuntimeException exception) {
            return failure(request, message(exception), "RFQ_REQUEST_REJECTED");
        }

        try {
            // 从 create 调用开始，直到结果构造完成的任何 RuntimeException 都必须对账。
            ProcurementRfqGateway.Receipt receipt = gateway.create(prepared.gatewayRequest());
            if (!matches(receipt, prepared)) {
                throw new IllegalStateException("RFQ gateway returned a mismatched receipt");
            }
            return success(prepared, receipt);
        }
        catch (RuntimeException exception) {
            return reconcileAfterCreateFailure(prepared, exception);
        }
    }

    @Override
    public boolean supports(ToolExecutionRecord execution) {
        return execution != null
                && ProcurementToolCatalog.CREATE_RFQ.equals(execution.toolName())
                && execution.request() != null;
    }

    @Override
    public ToolCallResult resolve(ToolExecutionRecord execution) {
        if (!supports(execution)) {
            return uncertain("stored RFQ execution is missing or unsupported", "");
        }
        try {
            ParsedPreparedRfq stored = parseStoredRequest(execution.request());
            Optional<ProcurementRfqGateway.Receipt> receipt = gateway.findByIdempotencyKey(
                    stored.idempotencyKey());
            if (receipt.isPresent() && matches(receipt.get(), stored)) {
                return success(stored, receipt.get(), true);
            }
            return uncertain("RFQ external state could not be confirmed", stored.idempotencyKey());
        }
        catch (RuntimeException exception) {
            return uncertain("RFQ external state reconciliation failed", idempotencyKey(execution.request()));
        }
    }

    private ParsedPreparedRfq validatePreparedRequest(ToolCallRequest request,
                                                       ToolExecutionContext context) {
        ToolExecutionContext trusted = requireContext(context);
        ParsedPreparedRfq parsed = parseStoredRequest(request);
        ProcurementCase current = caseStore.findByTenantUserAndConversationId(
                        trusted.tenantId(), trusted.userId(), trusted.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("procurement Case not found"));
        if (!current.caseId().equals(parsed.caseId()) || current.version() != parsed.caseVersion()) {
            throw new IllegalArgumentException("prepared RFQ request is stale for the current procurement Case");
        }
        if (!current.state().productCategory().equals(parsed.productCategory())
                || !current.state().productDescription().equals(parsed.productDescription())
                || !Integer.valueOf(parsed.quantity()).equals(current.state().quantity())
                || !current.state().currency().equals(parsed.currency())
                || !Integer.valueOf(parsed.requiredDeliveryDays()).equals(current.state().requiredDeliveryDays())
                || !current.state().hardConstraints().equals(parsed.hardConstraints())) {
            throw new IllegalArgumentException("prepared RFQ request does not match the current Case facts");
        }

        ToolExecutionRecord finalizeExecution = toolExecutionStore
                .findToolExecution(parsed.sourceRecommendationToolCallId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "prepared RFQ request does not reference a known Finalize execution"));
        if (!trusted.runId().equals(finalizeExecution.runId())
                || finalizeExecution.state() != ToolExecutionState.SUCCEEDED
                || finalizeExecution.result() == null
                || !finalizeExecution.result().success()
                || !ProcurementToolCatalog.RECOMMENDATION_FINALIZE.equals(finalizeExecution.toolName())) {
            throw new IllegalArgumentException("prepared RFQ request does not reference a successful current-run Finalize");
        }
        FinalizeSnapshot finalized = readFinalize(finalizeExecution);
        if (!current.caseId().equals(finalized.caseId())
                || current.version() != finalized.caseVersion()
                || !parsed.supplierId().equals(finalized.supplierId())) {
            throw new IllegalArgumentException("prepared RFQ supplier is not grounded in the current Finalize");
        }
        return parsed;
    }

    private ParsedPreparedRfq parseStoredRequest(ToolCallRequest request) {
        if (request == null || !supports(request.toolName())) {
            throw new IllegalArgumentException("unsupported procurement RFQ tool");
        }
        Map<String, Object> arguments = request.arguments();
        if (!CANONICAL_ARGUMENTS.equals(arguments.keySet())) {
            throw new IllegalArgumentException("RFQ request must contain exactly the canonical 11 fields");
        }
        String caseId = requiredString(arguments, "caseId");
        long caseVersion = requiredLong(arguments, "caseVersion");
        String supplierId = requiredString(arguments, "supplierId");
        String productCategory = requiredString(arguments, "productCategory");
        String productDescription = requiredString(arguments, "productDescription");
        int quantity = requiredPositiveInt(arguments, "quantity");
        String currency = requiredString(arguments, "currency");
        int requiredDeliveryDays = requiredPositiveInt(arguments, "requiredDeliveryDays");
        Map<String, String> hardConstraints = stringMap(arguments.get("hardConstraints"), "hardConstraints");
        String sourceRecommendationToolCallId = requiredString(arguments, "sourceRecommendationToolCallId");
        String idempotencyKey = requiredString(arguments, "idempotencyKey");
        if (!idempotencyKey.startsWith("rfq:") || idempotencyKey.length() == "rfq:".length()) {
            throw new IllegalArgumentException("idempotencyKey must be rfq:<non-empty>");
        }
        ProcurementRfqGateway.CreateRequest gatewayRequest = new ProcurementRfqGateway.CreateRequest(
                idempotencyKey, supplierId, productCategory, productDescription,
                quantity, currency, requiredDeliveryDays, hardConstraints);
        return new ParsedPreparedRfq(caseId, caseVersion, supplierId, productCategory, productDescription,
                quantity, currency, requiredDeliveryDays, hardConstraints,
                sourceRecommendationToolCallId, idempotencyKey, gatewayRequest);
    }

    private ToolExecutionContext requireContext(ToolExecutionContext context) {
        if (context == null || context.runId().isBlank() || context.sessionId().isBlank()
                || context.userId().isBlank() || context.tenantId().isBlank()) {
            throw new IllegalArgumentException("trusted runId, tenantId, userId and conversationId are required");
        }
        return context;
    }

    private ToolCallResult reconcileAfterCreateFailure(ParsedPreparedRfq prepared, RuntimeException cause) {
        try {
            Optional<ProcurementRfqGateway.Receipt> receipt = gateway.findByIdempotencyKey(prepared.idempotencyKey());
            if (receipt.isPresent() && matches(receipt.get(), prepared)) {
                try {
                    return success(prepared, receipt.get(), true);
                }
                catch (RuntimeException materializationFailure) {
                    return uncertain("RFQ reconciliation found a receipt but result materialization failed: "
                                    + message(materializationFailure), prepared.idempotencyKey());
                }
            }
        }
        catch (RuntimeException reconciliationFailure) {
            // 查询、receipt 匹配或结果读取失败本身就是 external state unknown。
            return uncertain("RFQ create failed and reconciliation failed: " + message(reconciliationFailure),
                    prepared.idempotencyKey());
        }
        return uncertain("RFQ create failed and external state is uncertain: " + message(cause),
                prepared.idempotencyKey());
    }

    private boolean matches(ProcurementRfqGateway.Receipt receipt, ParsedPreparedRfq prepared) {
        return receipt != null
                && "CREATED".equals(receipt.status())
                && prepared.idempotencyKey().equals(receipt.idempotencyKey())
                && prepared.supplierId().equals(receipt.supplierId());
    }

    private ToolCallResult success(ParsedPreparedRfq prepared, ProcurementRfqGateway.Receipt receipt) {
        return success(prepared, receipt, false);
    }

    private ToolCallResult success(ParsedPreparedRfq prepared,
                                   ProcurementRfqGateway.Receipt receipt,
                                   boolean reconciled) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", prepared.caseId());
        result.put("caseVersion", prepared.caseVersion());
        result.put("supplierId", prepared.supplierId());
        result.put("rfqId", receipt.rfqId());
        result.put("idempotencyKey", receipt.idempotencyKey());
        result.put("status", receipt.status());
        result.put("createdAt", receipt.createdAt());
        result.put("source", receipt.source());
        result.put("sourceRecommendationToolCallId", prepared.sourceRecommendationToolCallId());
        result.put("approvalBound", true);
        Map<String, Object> metadata = baseMetadata(prepared.idempotencyKey());
        metadata.put("rfqId", receipt.rfqId());
        metadata.put("supplierId", receipt.supplierId());
        metadata.put("reconciled", reconciled);
        return new ToolCallResult(ProcurementToolCatalog.CREATE_RFQ, true, write(result), "", Map.copyOf(metadata));
    }

    private ToolCallResult failure(ToolCallRequest request, String message, String errorType) {
        Map<String, Object> metadata = baseMetadata(idempotencyKey(request));
        metadata.put("retryable", false);
        metadata.put("errorType", errorType);
        return new ToolCallResult(request == null ? ProcurementToolCatalog.CREATE_RFQ : request.toolName(),
                false, "", message, Map.copyOf(metadata));
    }

    private ToolCallResult uncertain(String reason, String idempotencyKey) {
        Map<String, Object> metadata = baseMetadata(idempotencyKey);
        metadata.put("manualReview", true);
        metadata.put("retryable", false);
        metadata.put("uncertainExternalState", true);
        return new ToolCallResult(ProcurementToolCatalog.CREATE_RFQ, false, "", reason, Map.copyOf(metadata));
    }

    private Map<String, Object> baseMetadata(String idempotencyKey) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "procurement");
        metadata.put("domain", "procurement");
        metadata.put("contractVersion", "procurement-rfq-v1");
        metadata.put("readOnly", false);
        metadata.put("sideEffect", true);
        metadata.put("approvalBound", true);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) metadata.put("idempotencyKey", idempotencyKey);
        return metadata;
    }

    private FinalizeSnapshot readFinalize(ToolExecutionRecord execution) {
        try {
            JsonNode root = objectMapper.readTree(execution.result().content());
            if (root == null || !root.isObject() || !"verified-provider-snapshot".equals(text(root, "source"))) {
                throw new IllegalArgumentException("Finalize result is not a verified provider snapshot");
            }
            String caseId = requiredText(root, "caseId");
            long caseVersion = requiredNonNegativeLong(root, "caseVersion");
            JsonNode recommendation = requiredObject(root, "recommendation");
            String recommendedSupplierId = requiredText(requiredObject(recommendation, "recommendedSupplier"), "supplierId");
            String selectedOfferSupplierId = requiredText(requiredObject(recommendation, "selectedOffer"), "supplierId");
            if (!recommendedSupplierId.equals(selectedOfferSupplierId)) {
                throw new IllegalArgumentException("Finalize supplier sources do not match");
            }
            return new FinalizeSnapshot(caseId, caseVersion, recommendedSupplierId);
        }
        catch (IllegalArgumentException exception) {
            throw exception;
        }
        catch (Exception exception) {
            throw new IllegalArgumentException("Finalize result is invalid", exception);
        }
    }

    private JsonNode requiredObject(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isObject()) throw new IllegalArgumentException("Finalize field must be an object: " + field);
        return value;
    }

    private String requiredText(JsonNode root, String field) {
        String value = text(root, field);
        if (value.isBlank()) throw new IllegalArgumentException("Finalize field is required: " + field);
        return value;
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private long requiredNonNegativeLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || value.asLong() < 0) {
            throw new IllegalArgumentException("Finalize field must be a non-negative integer: " + field);
        }
        return value.asLong();
    }

    private String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return text.trim();
    }

    private long requiredLong(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        long parsed = ((Number) value).longValue();
        if (parsed < 0) throw new IllegalArgumentException(name + " must not be negative");
        return parsed;
    }

    private int requiredPositiveInt(Map<String, Object> arguments, String name) {
        long parsed = requiredLong(arguments, name);
        if (parsed <= 0 || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
        return (int) parsed;
    }

    private Map<String, String> stringMap(Object value, String name) {
        if (!(value instanceof Map<?, ?> source)) throw new IllegalArgumentException(name + " must be an object");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()
                    || !(entry.getValue() instanceof String item) || item.isBlank()) {
                throw new IllegalArgumentException(name + " must contain non-blank string entries");
            }
            result.put(key.trim(), item.trim());
        }
        return Map.copyOf(result);
    }

    private String idempotencyKey(ToolCallRequest request) {
        return request == null || request.arguments() == null ? "" : optionalString(request.arguments().get("idempotencyKey"));
    }

    private String idempotencyKey(ToolExecutionRecord execution) {
        return execution == null ? "" : idempotencyKey(execution.request());
    }

    private String optionalString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String message(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception exception) {
            throw new IllegalStateException("failed to serialize RFQ result", exception);
        }
    }

    private record FinalizeSnapshot(String caseId, long caseVersion, String supplierId) {
    }

    private record ParsedPreparedRfq(
            String caseId,
            long caseVersion,
            String supplierId,
            String productCategory,
            String productDescription,
            int quantity,
            String currency,
            int requiredDeliveryDays,
            Map<String, String> hardConstraints,
            String sourceRecommendationToolCallId,
            String idempotencyKey,
            ProcurementRfqGateway.CreateRequest gatewayRequest
    ) {
    }
}
