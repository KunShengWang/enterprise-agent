package com.agent.platform.ordercare.tool;

import com.agent.platform.ordercare.application.OrderCareCaseInspector;
import com.agent.platform.ordercare.client.FlowOrderApiException;
import com.agent.platform.ordercare.model.OrderCareCaseSnapshot;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OrderCareToolHandler implements ToolHandler {

    private final OrderCareCaseInspector inspector;
    private final ObjectMapper objectMapper;

    public OrderCareToolHandler(OrderCareCaseInspector inspector, ObjectMapper objectMapper) {
        this.inspector = inspector;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String toolName) {
        return OrderCareToolCatalog.CASE_INSPECT.equals(toolName);
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request) {
        try {
            String identifierType = stringArgument(request, "identifierType");
            String identifierValue = stringArgument(request, "identifierValue");
            // 远程调用 floworder 服务的接口
            OrderCareCaseSnapshot snapshot = inspector.inspect(
                    identifierType,
                    identifierValue,
                    request.requestId()
            );
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("provider", "floworder");
            metadata.put("domain", "ordercare");
            metadata.put("readOnly", true);
            metadata.put("contractVersion", snapshot.schemaVersion());
            metadata.put("caseKey", safe(snapshot.caseKey()));
            metadata.put("diagnosisCode", safe(snapshot.diagnosisCode()));
            metadata.put("factsComplete", Boolean.TRUE.equals(snapshot.factsComplete()));
            metadata.put("recoveryEligible", Boolean.TRUE.equals(snapshot.recoveryEligible()));
            metadata.put("hardRiskCount", snapshot.hardRisks().size());
            return new ToolCallResult(
                    request.toolName(),
                    true,
                    objectMapper.writeValueAsString(snapshot),
                    "",
                    metadata
            );
        } catch (FlowOrderApiException exception) {
            return new ToolCallResult(
                    request.toolName(),
                    false,
                    "",
                    "FlowOrder case inspection failed: " + exception.getMessage(),
                    Map.of(
                            "provider", "floworder",
                            "domain", "ordercare",
                            "readOnly", true,
                            "statusCode", exception.statusCode(),
                            "retryable", exception.retryable()
                    )
            );
        } catch (RuntimeException exception) {
            return new ToolCallResult(
                    request.toolName(),
                    false,
                    "",
                    "OrderCare case inspection failed: " + exception.getClass().getSimpleName(),
                    Map.of("provider", "floworder", "domain", "ordercare", "readOnly", true,
                            "retryable", false)
            );
        }
    }

    private String stringArgument(ToolCallRequest request, String name) {
        Object value = request.arguments().get(name);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
