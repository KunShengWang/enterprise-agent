package com.agent.platform.ordercare.application;

import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.model.OrderCareCaseSnapshot;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

@Service
public class OrderCareCaseInspector {

    private static final Set<String> SUPPORTED_IDENTIFIER_TYPES = Set.of(
            "REQUEST_ID", "ORDER_NO", "DEDUCT_NO", "DEAD_LETTER_ID"
    );

    private final FlowOrderClient flowOrderClient;

    public OrderCareCaseInspector(FlowOrderClient flowOrderClient) {
        this.flowOrderClient = flowOrderClient;
    }

    public OrderCareCaseSnapshot inspect(String identifierType,
                                         String identifierValue,
                                         String traceId) {
        String normalizedType = identifierType == null
                ? ""
                : identifierType.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_IDENTIFIER_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("unsupported OrderCare identifierType: " + normalizedType);
        }
        if (identifierValue == null || identifierValue.isBlank()) {
            throw new IllegalArgumentException("OrderCare identifierValue must not be blank");
        }
        return flowOrderClient.inspectCase(normalizedType, identifierValue.trim(), traceId);
    }
}
