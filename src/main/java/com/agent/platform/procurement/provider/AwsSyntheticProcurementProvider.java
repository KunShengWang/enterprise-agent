package com.agent.platform.procurement.provider;

import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.model.EvidenceIdFactory;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.SupplierCandidate;
import com.agent.platform.procurement.model.SupplierEvidence;
import com.agent.platform.procurement.model.SupplierOffer;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 将 AWS supplier base 与项目 scenario fixture 转换为 canonical procurement model。 */
@Component
@ConditionalOnProperty(prefix = "enterprise-agent.procurement", name = "provider",
        havingValue = "synthetic", matchIfMissing = true)
public class AwsSyntheticProcurementProvider implements ProcurementDataProvider {
    private static final String AWS_SOURCE = "aws-samples/sample-multi-agent-procure-to-pay";
    private final ObjectMapper objectMapper;
    private final ProcurementDataProperties properties;

    public AwsSyntheticProcurementProvider(ObjectMapper objectMapper, ProcurementDataProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public List<SupplierCandidate> searchSuppliers(ProcurementCaseState state) {
        Optional<JsonNode> scenario = scenario(state);
        if (scenario.isPresent()) {
            return nodes(scenario.get().path("suppliers")).stream().map(this::supplier).toList();
        }
        String category = normalized(state.productCategory() + " " + state.productDescription());
        return readArray(Path.of(properties.getDataDir(), "01_suppliers.json")).stream()
                .filter(node -> node.path("disabled").asInt(0) == 0)
                .filter(node -> category.isBlank() || supplierMatches(node, category))
                .map(this::supplier).toList();
    }

    @Override
    public List<SupplierOffer> getSupplierOffers(ProcurementCaseState state, List<SupplierCandidate> candidates) {
        List<SupplierCandidate> safeCandidates = candidates == null ? List.of() : candidates;
        Optional<JsonNode> scenario = scenario(state);
        if (scenario.isPresent()) {
            String scenarioId = text(scenario.get(), "scenarioId");
            Instant sourceAsOf = sourceAsOf(scenario.get());
            int quantity = state.quantity() == null ? 1 : state.quantity();
            return nodes(scenario.get().path("offers")).stream()
                    .filter(node -> safeCandidates.stream().anyMatch(c -> c.supplierId().equals(text(node, "supplierId"))))
                    .map(node -> offer(node, quantity, state.currency(), scenarioId, sourceAsOf)).toList();
        }
        return List.of();
    }

    @Override
    public List<SupplierEvidence> getSupplierEvidence(String supplierId, ProcurementCaseState state) {
        if (supplierId == null || supplierId.isBlank()) return List.of();
        List<SupplierCandidate> candidates = searchSuppliers(state).stream()
                .filter(candidate -> candidate.supplierId().equals(supplierId.trim())).toList();
        if (candidates.isEmpty()) return List.of();
        List<SupplierOffer> offers = getSupplierOffers(state, candidates);
        List<SupplierEvidence> result = new ArrayList<>();
        for (SupplierOffer offer : offers) {
            result.add(evidence(offer, "OFFER",
                    "商品 " + offer.productName() + " 单价 " + offer.unitPrice() + " " + offer.currency()
                            + "，总价 " + offer.totalPrice() + "，交期 " + offer.leadTimeDays()
                            + " 天，规格 " + offer.specifications()));
            if (!offer.warranty().isBlank()) {
                result.add(evidence(offer, "WARRANTY", "质保：" + offer.warranty()));
            }
        }
        return List.copyOf(result);
    }

    private SupplierCandidate supplier(JsonNode node) {
        String name = first(node, "supplierName", "supplier_name");
        String id = first(node, "supplierId", "supplier_id");
        if (id.isBlank()) id = "supplier-" + slug(name);
        return new SupplierCandidate(id, name, node.has("supplierId") ? "scenario-fixture" : AWS_SOURCE + ":01_suppliers.json");
    }

    private SupplierOffer offer(JsonNode node, int quantity, String defaultCurrency, String scenarioId,
                                Instant sourceAsOf) {
        String source = text(node, "source").isBlank() ? "scenario-fixture" : text(node, "source");
        String productId = text(node, "productId");
        String sourceRecordId = scenarioId + ":" + productId;
        String sourceSnapshot = "scenario:" + (scenarioId.isBlank() ? "unknown" : scenarioId);
        String sourceDigest = EvidenceIdFactory.digest(source, sourceRecordId, sourceSnapshot, node.toString());
        return new SupplierOffer(text(node, "supplierId"), text(node, "productId"), text(node, "productName"),
                decimal(node, "unitPrice"), text(node, "currency").isBlank() ? defaultCurrency : text(node, "currency"), quantity, null, node.path("leadTimeDays").asInt(0),
                text(node, "warranty"), object(node.path("specifications")), source, Instant.now(),
                sourceRecordId, sourceSnapshot, sourceAsOf, sourceDigest);
    }

    private SupplierEvidence evidence(SupplierOffer offer, String type, String fact) {
        String id = EvidenceIdFactory.id(offer.supplierId(), type, offer.source(), offer.sourceRecordId(),
                offer.sourceSnapshot(), offer.sourceAsOf().toString(), offer.sourceDigest(), fact);
        return new SupplierEvidence(id, offer.supplierId(), type, offer.source(), fact, Instant.now(),
                offer.sourceRecordId(), offer.sourceSnapshot(), offer.sourceAsOf(), offer.sourceDigest());
    }

    private Optional<JsonNode> scenario(ProcurementCaseState state) {
        String text = normalized(state.productCategory() + " " + state.productDescription());
        if (!text.contains("cuda") && !text.contains("workstation") && !text.contains("工作站")) return Optional.empty();
        String fileName = properties.getScenarioFile();
        if (fileName == null || fileName.isBlank()) return Optional.empty();
        Path path = Path.of(properties.getScenarioDir(), fileName);
        return Files.exists(path) ? Optional.of(read(path)) : Optional.empty();
    }

    private Instant sourceAsOf(JsonNode scenario) {
        String value = text(scenario, "sourceAsOf");
        if (value.isBlank()) throw new IllegalStateException("scenario sourceAsOf is required");
        try { return Instant.parse(value); }
        catch (RuntimeException exception) { throw new IllegalStateException("scenario sourceAsOf is invalid", exception); }
    }

    private boolean supplierMatches(JsonNode node, String category) {
        String source = normalized(text(node, "supplier_details") + " " + node.path("_meta").path("categories"));
        return category.isBlank() || java.util.stream.Stream.of(category.split("\\s+")).filter(v -> v.length() > 2).anyMatch(source::contains);
    }

    private JsonNode read(Path path) {
        try { return objectMapper.readTree(Files.readString(path)); }
        catch (IOException e) { throw new IllegalStateException("failed to read procurement data: " + path, e); }
    }

    private List<JsonNode> readArray(Path path) {
        JsonNode root = read(path);
        List<JsonNode> result = new ArrayList<>();
        root.forEach(result::add);
        return result;
    }

    private List<JsonNode> nodes(JsonNode node) {
        List<JsonNode> result = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(result::add);
        return result;
    }

    private Map<String, Object> object(JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        try { return objectMapper.convertValue(node, Map.class); }
        catch (IllegalArgumentException ignored) { return Map.of(); }
    }

    private BigDecimal decimal(JsonNode node, String name) { return new BigDecimal(node.path(name).asText("0")); }
    private String first(JsonNode node, String... names) { for (String name : names) if (!text(node, name).isBlank()) return text(node, name); return ""; }
    private String text(JsonNode node, String name) { return node == null ? "" : node.path(name).asText("").trim(); }
    private String normalized(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", ""); }
    private String slug(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""); }
}
