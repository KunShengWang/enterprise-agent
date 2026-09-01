package com.agent.platform.procurement.provider;

import com.agent.platform.procurement.config.ProcurementDataProperties;
import com.agent.platform.procurement.model.CatalogItem;
import com.agent.platform.procurement.model.EvidenceIdFactory;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.SupplierCandidate;
import com.agent.platform.procurement.model.SupplierEvidence;
import com.agent.platform.procurement.model.SupplierOffer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** 将 AWS sample 的原始字段转换为本项目 canonical procurement model。 */
@Component
public class AwsSyntheticProcurementProvider implements ProcurementDataProvider {
    private static final String AWS_SOURCE = "aws-samples/sample-multi-agent-procure-to-pay";
    private static final Instant SCENARIO_AS_OF = Instant.parse("2026-01-01T00:00:00Z");
    private final ObjectMapper objectMapper;
    private final ProcurementDataProperties properties;

    public AwsSyntheticProcurementProvider(ObjectMapper objectMapper, ProcurementDataProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public List<CatalogItem> searchCatalog(ProcurementCaseState state) {
        Set<String> knownGroups = readArray(Path.of(properties.getDataDir(), "02_item_groups.json")).stream()
                .map(node -> text(node, "name")).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return readArray(Path.of(properties.getDataDir(), "03_items.json")).stream()
                .map(this::catalogItem)
                .filter(item -> knownGroups.contains(item.category()))
                .filter(item -> matches(item.category(), item.productName(), state)).toList();
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
            int quantity = state.quantity() == null ? 1 : state.quantity();
            return nodes(scenario.get().path("offers")).stream()
                    .filter(node -> safeCandidates.stream().anyMatch(c -> c.supplierId().equals(text(node, "supplierId"))))
                    .map(node -> offer(node, quantity, state.currency(), scenarioId)).toList();
        }
        // 03_items.json 只有目录基准价，不是供应商报价；没有 supplier-specific quote 时必须返回空。
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

    private CatalogItem catalogItem(JsonNode node) {
        return new CatalogItem(text(node, "item_code"), text(node, "item_name"), text(node, "item_group"),
                decimal(node, "standard_rate"), node.path("lead_time_days").asInt(0),
                Map.of("description", text(node, "description"), "uom", text(node, "stock_uom")),
                AWS_SOURCE + ":03_items.json");
    }

    private SupplierCandidate supplier(JsonNode node) {
        String name = first(node, "supplierName", "supplier_name");
        String id = first(node, "supplierId", "supplier_id");
        if (id.isBlank()) id = "supplier-" + slug(name);
        return new SupplierCandidate(id, name, node.has("supplierId") ? "scenario-fixture" : AWS_SOURCE + ":01_suppliers.json");
    }

    private SupplierOffer offer(JsonNode node, int quantity, String defaultCurrency, String scenarioId) {
        String source = text(node, "source").isBlank() ? "scenario-fixture" : text(node, "source");
        String productId = text(node, "productId");
        return new SupplierOffer(text(node, "supplierId"), text(node, "productId"), text(node, "productName"),
                decimal(node, "unitPrice"), text(node, "currency").isBlank() ? defaultCurrency : text(node, "currency"), quantity, null, node.path("leadTimeDays").asInt(0),
                text(node, "warranty"), object(node.path("specifications")), source, Instant.now(),
                productId, "scenario:" + (scenarioId.isBlank() ? "unknown" : scenarioId), SCENARIO_AS_OF, "");
    }

    private SupplierEvidence evidence(SupplierOffer offer, String type, String fact) {
        String id = EvidenceIdFactory.id(offer.supplierId(), type, offer.source(), offer.sourceRecordId(),
                offer.sourceSnapshot(), offer.sourceAsOf().toString(), fact);
        return new SupplierEvidence(id, offer.supplierId(), type, offer.source(), fact, Instant.now(),
                offer.sourceRecordId(), offer.sourceSnapshot(), offer.sourceAsOf(), offer.sourceDigest());
    }

    private String currencyOf(SupplierCandidate candidate) {
        return readArray(Path.of(properties.getDataDir(), "01_suppliers.json")).stream()
                .filter(node -> ("supplier-" + slug(text(node, "supplier_name"))).equals(candidate.supplierId()))
                .map(node -> text(node, "default_currency"))
                .filter(value -> !value.isBlank()).findFirst().orElse("USD");
    }

    private Optional<JsonNode> scenario(ProcurementCaseState state) {
        String text = normalized(state.productCategory() + " " + state.productDescription());
        if (!text.contains("cuda") && !text.contains("workstation") && !text.contains("工作站")) return Optional.empty();
        String fileName = properties.getScenarioFile();
        if (fileName == null || fileName.isBlank()) return Optional.empty();
        Path path = Path.of(properties.getScenarioDir(), fileName);
        return Files.exists(path) ? Optional.of(read(path)) : Optional.empty();
    }

    private boolean matches(String category, String productName, ProcurementCaseState state) {
        String query = normalized(state.productCategory() + " " + state.productDescription());
        return query.isBlank() || normalized(category + " " + productName).contains(normalized(state.productCategory()))
                || normalized(productName).contains(normalized(state.productDescription()));
    }

    private boolean supplierMatches(JsonNode node, String category) {
        String source = normalized(text(node, "supplier_details") + " " + node.path("_meta").path("categories"));
        return category.isBlank() || Stream.of(category.split("\\s+")).filter(v -> v.length() > 2).anyMatch(source::contains);
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
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
