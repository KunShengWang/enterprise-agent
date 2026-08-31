package com.agent.platform.procurement.model;

import java.math.BigDecimal;
import java.util.Map;

public record CatalogItem(String productId, String productName, String category, BigDecimal standardRate,
                          int leadTimeDays, Map<String, Object> specifications, String source) {
    public CatalogItem {
        specifications = specifications == null ? Map.of() : Map.copyOf(specifications);
        source = source == null ? "" : source.trim();
    }
}
