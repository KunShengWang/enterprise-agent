package com.agent.platform.runtime;

import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Deterministic canonical JSON contracts. Unknown/text results remain FULL, never substring-truncated. */
final class StructuredToolProjection {
    private final ObjectMapper json = new ObjectMapper();

    @SuppressWarnings("unchecked")
    String project(String content) {
        try {
            Object value = json.readValue(content, Object.class);
            if (!(value instanceof Map<?, ?>)) return content;
            Map<String, Object> root = (Map<String, Object>) value;
            List<Map<String, Object>> offers = maps(root.get("offers"));
            Map<String, Object> recommendation = map(root.get("recommendation"));
            if (offers.isEmpty() && !recommendation.isEmpty()) {
                offers = new ArrayList<>(maps(recommendation.get("alternativeOffers")));
                if (recommendation.get("selectedOffer") instanceof Map<?, ?>)
                    offers.add(map(recommendation.get("selectedOffer")));
            }
            for (Map<String, Object> evaluation : maps(root.get("evaluations"))) {
                int index = offers.indexOf(evaluation.get("offer"));
                if (index >= 0 && root.containsKey("offers")) {
                    evaluation.remove("offer");
                    evaluation.put("offerRef", "$.offers[" + index + "]");
                }
            }
            // Replace only a byte-parseable duplicate specifications suffix. All unique prose/provenance stays.
            for (Map<String, Object> evidence : maps(root.get("evidence"))) {
                String fact = String.valueOf(evidence.getOrDefault("fact", ""));
                int start = fact.indexOf('{');
                if (start < 0) continue;
                for (Map<String, Object> offer : offers) {
                    if (!Objects.equals(evidence.get("supplierId"), offer.get("supplierId"))
                            || evidence.get("sourceDigest") == null
                            || !Objects.equals(evidence.get("sourceDigest"), offer.get("sourceDigest"))) continue;
                    try {
                        if (json.readValue(fact.substring(start), Object.class).equals(offer.get("specifications"))) {
                            evidence.put("fact", fact.substring(0, start) + "see matching offer.specifications");
                            evidence.put("specificationsSupplierId", offer.get("supplierId"));
                        }
                    } catch (RuntimeException ignored) { /* Non-duplicate facts stay verbatim. */ }
                }
            }
            List<Map<String, Object>> records = maps(root.get("records"));
            if (records.size() >= 2) {
                List<Map<String, Object>> flat = records.stream().map(record -> flatten(record, "")).toList();
                Set<String> keys = flat.get(0).keySet();
                if (flat.stream().allMatch(row -> row.keySet().equals(keys))) {
                    Map<String, Object> common = new TreeMap<>();
                    for (String key : keys) {
                        Object first = flat.get(0).get(key);
                        if (flat.stream().allMatch(row -> Objects.equals(first, row.get(key)))) common.put(key, first);
                    }
                    List<String> columns = keys.stream().filter(key -> !common.containsKey(key)).sorted().toList();
                    List<List<Object>> rows = flat.stream().map(row -> columns.stream().map(row::get).toList()).toList();
                    root.remove("records");
                    root.put("recordsTable", Map.of("format", "all rows; dotted columns expand nested objects; common applies to every row",
                            "count", records.size(), "common", common, "columns", columns, "rows", rows));
                }
            }
            return json.writeValueAsString(root);
        } catch (RuntimeException ignored) { return content; }
    }

    private Map<String, Object> flatten(Map<String, Object> value, String prefix) {
        Map<String, Object> result = new TreeMap<>();
        value.forEach((key, entry) -> {
            if (key.contains(".")) throw new IllegalArgumentException("ambiguous table column");
            if (entry instanceof Map<?, ?> nested && !nested.isEmpty()) result.putAll(flatten(map(entry), prefix + key + "."));
            else result.put(prefix + key, entry);
        });
        return result;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
    }
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof Map<?, ?>))) return List.of();
        return list.stream().map(this::map).toList();
    }
}
