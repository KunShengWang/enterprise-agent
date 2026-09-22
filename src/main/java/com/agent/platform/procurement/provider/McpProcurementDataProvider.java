package com.agent.platform.procurement.provider;

import com.agent.platform.mcp.McpToolGateway;
import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.model.EvidenceIdFactory;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.SupplierCandidate;
import com.agent.platform.procurement.model.SupplierEvidence;
import com.agent.platform.procurement.model.SupplierOffer;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 通过冻结的 MCP Gateway 读取供应商事实，并在本地重建采购 canonical model。 */
@Component
@ConditionalOnProperty(prefix = "enterprise-agent.procurement", name = "provider", havingValue = "mcp")
public class McpProcurementDataProvider implements ProcurementDataProvider {
    private static final String SEARCH_SUPPLIERS = "search_suppliers";
    private static final String GET_OFFERS = "get_offers";

    private final ObjectMapper objectMapper;
    private final ProcurementDataProperties properties;
    private final ObjectProvider<McpToolGateway> gatewayProvider;

    public McpProcurementDataProvider(ObjectMapper objectMapper,
                                      ProcurementDataProperties properties,
                                      ObjectProvider<McpToolGateway> gatewayProvider) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.gatewayProvider = gatewayProvider;
    }

    @Override
    public List<SupplierCandidate> searchSuppliers(ProcurementCaseState state) {
        requireState(state);
        McpToolGateway gateway = requireGateway();
        ToolDefinition definition = resolveTool(gateway, SEARCH_SUPPLIERS);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("productCategory", state.productCategory());
        request.put("productDescription", state.productDescription());

        JsonNode response = invoke(gateway, definition, request);
        requireObject(response, "supplier search response");
        RemoteSnapshotMeta snapshot = snapshot(response, "supplier search");
        JsonNode suppliers = requiredArray(response, "suppliers");
        String source = source(definition);
        Set<String> supplierIds = new LinkedHashSet<>();
        List<SupplierCandidate> result = new ArrayList<>();
        for (JsonNode supplier : suppliers) {
            requireObject(supplier, "supplier");
            String supplierId = requiredText(supplier, "supplierId", true);
            String supplierName = requiredText(supplier, "supplierName", true);
            if (!supplierIds.add(supplierId)) {
                throw failure("duplicate supplierId in MCP supplier search response");
            }
            result.add(new SupplierCandidate(supplierId, supplierName, source));
        }
        // The snapshot is validated even though SupplierCandidate has no snapshot field.
        if (snapshot.sourceSnapshot().isBlank()) {
            throw failure("MCP supplier search snapshot is blank");
        }
        return List.copyOf(result);
    }

    @Override
    public List<SupplierOffer> getSupplierOffers(ProcurementCaseState state,
                                                   List<SupplierCandidate> candidates) {
        requireState(state);
        McpToolGateway gateway = requireGateway();
        ToolDefinition definition = resolveTool(gateway, GET_OFFERS);
        List<SupplierCandidate> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
        Set<String> requestedSupplierIds = candidateIds(safeCandidates);
        int quantity = state.quantity() == null ? 0 : state.quantity();
        if (quantity <= 0) {
            throw failure("Procurement CaseState quantity is required for MCP offers");
        }
        if (state.currency() == null || state.currency().isBlank()) {
            throw failure("Procurement CaseState currency is required for MCP offers");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("productCategory", state.productCategory());
        request.put("productDescription", state.productDescription());
        request.put("quantity", quantity);
        request.put("currency", state.currency());
        request.put("supplierIds", List.copyOf(requestedSupplierIds));

        JsonNode response = invoke(gateway, definition, request);
        requireObject(response, "offer response");
        RemoteSnapshotMeta snapshot = snapshot(response, "offer");
        JsonNode offers = requiredArray(response, "offers");
        String source = source(definition);
        Set<String> seenSupplierIds = new LinkedHashSet<>();
        List<SupplierOffer> result = new ArrayList<>();
        for (JsonNode offer : offers) {
            requireObject(offer, "offer");
            RemoteOffer remote = remoteOffer(offer);
            if (!requestedSupplierIds.contains(remote.supplierId())) {
                throw failure("MCP offer supplier is not in the requested candidate set");
            }
            if (!seenSupplierIds.add(remote.supplierId())) {
                throw failure("multiple MCP offers for the same supplier are not supported by the current procurement contract");
            }
            Map<String, Object> canonicalPayload = canonicalOfferPayload(remote);
            String sourceDigest = EvidenceIdFactory.digest(source, remote.sourceRecordId(),
                    snapshot.sourceSnapshot(), canonicalOfferJson(canonicalPayload));
            result.add(new SupplierOffer(remote.supplierId(), remote.productId(), remote.productName(),
                    remote.unitPrice(), remote.currency(), quantity, null, remote.leadTimeDays(),
                    remote.warranty(), remote.specifications(), source, Instant.now(),
                    remote.sourceRecordId(), snapshot.sourceSnapshot(), snapshot.sourceAsOf(), sourceDigest));
        }
        return List.copyOf(result);
    }

    @Override
    public List<SupplierEvidence> getSupplierEvidence(String supplierId, ProcurementCaseState state) {
        if (supplierId == null || supplierId.isBlank()) {
            return List.of();
        }
        String requestedId = supplierId.trim();
        List<SupplierCandidate> candidates = searchSuppliers(state).stream()
                .filter(candidate -> candidate.supplierId().equals(requestedId))
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<SupplierEvidence> result = new ArrayList<>();
        for (SupplierOffer offer : getSupplierOffers(state, candidates)) {
            String offerFact = "商品 " + offer.productName() + " 单价 " + offer.unitPrice() + " "
                    + offer.currency() + "，总价 " + offer.totalPrice() + "，交期 "
                    + offer.leadTimeDays() + " 天，规格 " + canonicalJson(offer.specifications());
            result.add(evidence(offer, "OFFER", offerFact));
            if (!offer.warranty().isBlank()) {
                result.add(evidence(offer, "WARRANTY", "质保：" + offer.warranty()));
            }
        }
        return List.copyOf(result);
    }

    private McpToolGateway requireGateway() {
        if (gatewayProvider == null) {
            throw failure("MCP procurement gateway is unavailable");
        }
        try {
            McpToolGateway gateway = gatewayProvider.getIfAvailable();
            if (gateway == null) {
                throw failure("MCP procurement gateway is unavailable");
            }
            return gateway;
        }
        catch (IllegalStateException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw failure("MCP procurement gateway is unavailable", exception);
        }
    }

    private ToolDefinition resolveTool(McpToolGateway gateway, String suffix) {
        String prefix = properties == null ? "" : properties.getMcpToolPrefix();
        if (prefix == null || prefix.isBlank()) {
            throw failure("MCP procurement tool prefix is blank");
        }
        String expectedName = prefix + suffix;
        List<ToolDefinition> discovered;
        try {
            discovered = gateway.discoverTools();
        }
        catch (RuntimeException exception) {
            throw failure("MCP procurement tool discovery failed", exception);
        }
        if (discovered == null) {
            throw failure("MCP procurement tool discovery returned null");
        }
        List<ToolDefinition> matches = new ArrayList<>();
        for (ToolDefinition definition : discovered) {
            if (definition == null) {
                throw failure("MCP procurement tool discovery returned a null definition");
            }
            if (expectedName.equals(definition.name())) {
                matches.add(definition);
            }
        }
        if (matches.isEmpty()) {
            throw failure("required MCP procurement tool is missing: " + expectedName);
        }
        if (matches.size() > 1) {
            throw failure("required MCP procurement tool is duplicated: " + expectedName);
        }
        return matches.get(0);
    }

    private JsonNode invoke(McpToolGateway gateway, ToolDefinition definition,
                            Map<String, Object> arguments) {
        ToolCallResult result;
        try {
            result = gateway.callTool(definition,
                    new ToolCallRequest(definition.name(), "procurement-mcp-" + UUID.randomUUID(), arguments));
        }
        catch (RuntimeException exception) {
            throw failure("MCP procurement tool call failed", exception);
        }
        if (result == null || !result.success() || result.content() == null || result.content().isBlank()) {
            throw failure("MCP procurement tool call failed");
        }
        try {
            JsonNode response = objectMapper.readTree(result.content());
            if (response == null || !response.isObject()) {
                throw failure("MCP procurement tool response must be a JSON object");
            }
            return response;
        }
        catch (IllegalStateException exception) {
            throw exception;
        }
        catch (Exception exception) {
            throw failure("MCP procurement tool response is malformed", exception);
        }
    }

    private RemoteOffer remoteOffer(JsonNode node) {
        String supplierId = requiredText(node, "supplierId", true);
        String productId = requiredText(node, "productId", true);
        String productName = requiredText(node, "productName", true);
        BigDecimal unitPrice = requiredNonNegativeDecimal(node, "unitPrice");
        String currency = requiredText(node, "currency", true);
        int leadTimeDays = requiredNonNegativeInt(node, "leadTimeDays");
        String warranty = requiredText(node, "warranty", false);
        JsonNode specificationsNode = node.get("specifications");
        if (specificationsNode == null || !specificationsNode.isObject()) {
            throw failure("MCP offer specifications must be an object");
        }
        Object specificationsValue = canonicalValue(specificationsNode);
        if (!(specificationsValue instanceof Map<?, ?> specifications)) {
            throw failure("MCP offer specifications must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typedSpecifications = (Map<String, Object>) specifications;
        String sourceRecordId = requiredText(node, "sourceRecordId", true);
        return new RemoteOffer(supplierId, productId, productName, unitPrice, currency,
                leadTimeDays, warranty, typedSpecifications, sourceRecordId);
    }

    private RemoteSnapshotMeta snapshot(JsonNode node, String operation) {
        String sourceSnapshot = requiredText(node, "sourceSnapshot", true);
        String sourceAsOfText = requiredText(node, "sourceAsOf", true);
        try {
            return new RemoteSnapshotMeta(sourceSnapshot, Instant.parse(sourceAsOfText));
        }
        catch (RuntimeException exception) {
            throw failure("MCP " + operation + " sourceAsOf is invalid", exception);
        }
    }

    private Set<String> candidateIds(List<SupplierCandidate> candidates) {
        Set<String> result = new LinkedHashSet<>();
        for (SupplierCandidate candidate : candidates) {
            if (candidate == null || candidate.supplierId() == null || candidate.supplierId().isBlank()) {
                throw failure("MCP offer candidate id is blank");
            }
            if (!result.add(candidate.supplierId())) {
                throw failure("duplicate supplierId in requested candidates");
            }
        }
        return result;
    }

    private Map<String, Object> canonicalOfferPayload(RemoteOffer offer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supplierId", offer.supplierId());
        payload.put("productId", offer.productId());
        payload.put("productName", offer.productName());
        payload.put("unitPrice", offer.unitPrice());
        payload.put("currency", offer.currency());
        payload.put("leadTimeDays", offer.leadTimeDays());
        payload.put("warranty", offer.warranty());
        payload.put("specifications", offer.specifications());
        payload.put("sourceRecordId", offer.sourceRecordId());
        return payload;
    }

    private String canonicalOfferJson(Map<String, Object> payload) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        payload.forEach((key, value) -> ordered.put(key, canonicalJavaValue(value)));
        try {
            return objectMapper.writeValueAsString(ordered);
        }
        catch (Exception exception) {
            throw failure("failed to serialize canonical MCP offer fact", exception);
        }
    }

    private SupplierEvidence evidence(SupplierOffer offer, String type, String fact) {
        String evidenceId = EvidenceIdFactory.id(offer.supplierId(), type, offer.source(),
                offer.sourceRecordId(), offer.sourceSnapshot(), offer.sourceAsOf().toString(),
                offer.sourceDigest(), fact);
        return new SupplierEvidence(evidenceId, offer.supplierId(), type, offer.source(), fact,
                Instant.now(), offer.sourceRecordId(), offer.sourceSnapshot(), offer.sourceAsOf(),
                offer.sourceDigest());
    }

    private String source(ToolDefinition definition) {
        Object serverIdValue = definition.metadata().get("mcpServerId");
        String serverId = serverIdValue == null ? "" : String.valueOf(serverIdValue).trim();
        if (serverId.isBlank()) {
            throw failure("MCP procurement tool has no safe server identity");
        }
        return "mcp:" + serverId;
    }

    private JsonNode requiredArray(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isArray()) {
            throw failure("MCP response field must be an array: " + name);
        }
        return value;
    }

    private String requiredText(JsonNode node, String name, boolean nonBlank) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw failure("MCP response field must be text: " + name);
        }
        String text = value.asText().trim();
        if (nonBlank && text.isBlank()) {
            throw failure("MCP response field must not be blank: " + name);
        }
        return text;
    }

    private BigDecimal requiredNonNegativeDecimal(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isNumber()) {
            throw failure("MCP response field must be numeric: " + name);
        }
        try {
            BigDecimal decimal = new BigDecimal(value.asText());
            if (decimal.signum() < 0) {
                throw failure("MCP response numeric field must not be negative: " + name);
            }
            return decimal;
        }
        catch (IllegalStateException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw failure("MCP response numeric field is invalid: " + name, exception);
        }
    }

    private int requiredNonNegativeInt(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber()) {
            throw failure("MCP response field must be an integer: " + name);
        }
        long number = value.longValue();
        if (number < 0 || number > Integer.MAX_VALUE) {
            throw failure("MCP response integer field is invalid: " + name);
        }
        return (int) number;
    }

    private void requireObject(JsonNode node, String label) {
        if (node == null || !node.isObject()) {
            throw failure("MCP " + label + " must be a JSON object");
        }
    }

    private void requireState(ProcurementCaseState state) {
        if (state == null) {
            throw failure("Procurement CaseState is required");
        }
    }

    private Object canonicalValue(JsonNode node) {
        if (node == null || node.isNull()) {
            throw failure("MCP specifications contain null");
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.properties().forEach(entry -> names.add(entry.getKey()));
            Collections.sort(names);
            Map<String, Object> result = new LinkedHashMap<>();
            for (String name : names) {
                result.put(name, canonicalValue(node.get(name)));
            }
            return result;
        }
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonNode child : node) {
                result.add(canonicalValue(child));
            }
            return result;
        }
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) {
            try {
                return new BigDecimal(node.asText());
            }
            catch (RuntimeException exception) {
                throw failure("MCP specifications contain an invalid number", exception);
            }
        }
        throw failure("MCP specifications contain an unsupported value");
    }

    private Object canonicalJavaValue(Object value) {
        if (value == null) {
            throw failure("canonical procurement fact contains null");
        }
        if (value instanceof Map<?, ?> map) {
            List<String> keys = map.keySet().stream().map(String::valueOf).sorted().toList();
            Map<String, Object> result = new LinkedHashMap<>();
            for (String key : keys) {
                Object entry = map.entrySet().stream()
                        .filter(item -> String.valueOf(item.getKey()).equals(key))
                        .findFirst().orElseThrow().getValue();
                result.put(key, canonicalJavaValue(entry));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalJavaValue).toList();
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw failure("canonical procurement fact contains an unsupported value");
    }

    private String canonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(canonicalJavaValue(value));
        }
        catch (IllegalStateException exception) {
            throw exception;
        }
        catch (Exception exception) {
            throw failure("failed to serialize canonical MCP procurement fact", exception);
        }
    }

    private IllegalStateException failure(String message) {
        return new IllegalStateException(message);
    }

    private IllegalStateException failure(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }

    private record RemoteSnapshotMeta(String sourceSnapshot, Instant sourceAsOf) { }

    private record RemoteOffer(String supplierId, String productId, String productName,
                               BigDecimal unitPrice, String currency, int leadTimeDays,
                               String warranty, Map<String, Object> specifications,
                               String sourceRecordId) { }
}
