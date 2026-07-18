package com.agent.platform.ordercare.incident.tool;

import com.agent.platform.ordercare.client.FlowOrderApiException;
import com.agent.platform.ordercare.incident.application.IncidentMqFactsReader;
import com.agent.platform.ordercare.incident.client.FlowOrderIncidentClient;
import com.agent.platform.ordercare.incident.model.IncidentFactQuery;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class IncidentFactToolHandler implements ToolHandler {

    private final IncidentStore incidentStore;
    private final FlowOrderIncidentClient flowOrderClient;
    private final IncidentMqFactsReader mqFactsReader;
    private final ObjectMapper objectMapper;

    public IncidentFactToolHandler(IncidentStore incidentStore,
                                   FlowOrderIncidentClient flowOrderClient,
                                   IncidentMqFactsReader mqFactsReader,
                                   ObjectMapper objectMapper) {
        this.incidentStore = incidentStore;
        this.flowOrderClient = flowOrderClient;
        this.mqFactsReader = mqFactsReader;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String toolName) {
        return IncidentToolCatalog.ORDER_FACTS.equals(toolName)
                || IncidentToolCatalog.INVENTORY_FACTS.equals(toolName)
                || IncidentToolCatalog.MQ_FACTS.equals(toolName);
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request) {
        String snapshotId = stringArgument(request, "snapshotId");
        if (snapshotId.isBlank()) {
            return failure(request, "snapshotId must not be blank", false, "INVALID_ARGUMENT");
        }
        IncidentSnapshot snapshot = incidentStore.findSnapshot(snapshotId).orElse(null);
        if (snapshot == null) {
            return failure(request, "incident snapshot not found", false, "SNAPSHOT_NOT_FOUND");
        }
        IncidentFactQuery query = new IncidentFactQuery(
                snapshot.incidentId(),
                snapshot.snapshotId(),
                snapshot.scopeHash(),
                snapshot.orderScope().requestIds(),
                snapshot.businessScope().queueNames(),
                500
        );
        try {
            Object result = switch (request.toolName()) {
                case IncidentToolCatalog.ORDER_FACTS -> flowOrderClient.queryOrderFacts(query, request.requestId());
                case IncidentToolCatalog.INVENTORY_FACTS ->
                        flowOrderClient.queryInventoryFacts(query, request.requestId());
                case IncidentToolCatalog.MQ_FACTS -> mqFactsReader.read(query, request.requestId());
                default -> throw new IllegalArgumentException("unsupported incident tool: " + request.toolName());
            };
            LinkedHashMap<String, Object> metadata = baseMetadata(snapshot);
            metadata.put("contractVersion", "incident-facts-v1");
            metadata.put("sourceReference", request.toolName() + ":" + snapshot.snapshotId());
            return new ToolCallResult(
                    request.toolName(), true, objectMapper.writeValueAsString(result), "", Map.copyOf(metadata));
        }
        catch (FlowOrderApiException exception) {
            return failure(request, "FlowOrder incident fact query failed", exception.retryable(), "FLOWORDER_ERROR");
        }
        catch (RuntimeException exception) {
            return failure(request, "incident fact query failed: " + exception.getClass().getSimpleName(),
                    false, "INCIDENT_FACT_ERROR");
        }
    }

    private LinkedHashMap<String, Object> baseMetadata(IncidentSnapshot snapshot) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "floworder-incident");
        metadata.put("domain", "ordercare-incident");
        metadata.put("readOnly", true);
        metadata.put("incidentId", snapshot.incidentId());
        metadata.put("snapshotId", snapshot.snapshotId());
        metadata.put("scopeHash", snapshot.scopeHash());
        return metadata;
    }

    private ToolCallResult failure(ToolCallRequest request,
                                   String message,
                                   boolean retryable,
                                   String errorCode) {
        return new ToolCallResult(
                request == null ? "" : request.toolName(), false, "", message,
                Map.of(
                        "provider", "floworder-incident",
                        "domain", "ordercare-incident",
                        "readOnly", true,
                        "retryable", retryable,
                        "errorCode", errorCode
                )
        );
    }

    private String stringArgument(ToolCallRequest request, String name) {
        if (request == null || request.arguments() == null) {
            return "";
        }
        Object value = request.arguments().get(name);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
