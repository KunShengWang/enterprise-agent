package com.agent.platform.tool;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Service
public class JsonSchemaToolParameterValidator implements ToolParameterValidator {

    private final ObjectMapper objectMapper;

    public JsonSchemaToolParameterValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolValidationResult validate(ToolDefinition definition, ToolCallRequest request) {
        if (definition == null) {
            return ToolValidationResult.invalid("tool definition is missing");
        }
        if (request == null) {
            return ToolValidationResult.invalid("tool call request is missing");
        }
        try {
            JsonNode schema = objectMapper.readTree(definition.inputSchema());
            ToolValidationResult requiredResult = validateRequired(schema, request.arguments());
            if (!requiredResult.valid()) {
                return requiredResult;
            }
            return validateProperties(schema, request.arguments());
        }
        catch (Exception exception) {
            return ToolValidationResult.invalid("invalid tool schema: " + exception.getMessage());
        }
    }

    private ToolValidationResult validateRequired(JsonNode schema, Map<String, Object> arguments) {
        JsonNode required = schema.path("required");
        if (!required.isArray()) {
            return ToolValidationResult.ok();
        }
        for (JsonNode field : required) {
            String fieldName = field.asText();
            Object value = arguments.get(fieldName);
            if (value == null || (value instanceof String text && text.isBlank())) {
                return ToolValidationResult.invalid("missing required argument: " + fieldName);
            }
        }
        return ToolValidationResult.ok();
    }

    private ToolValidationResult validateProperties(JsonNode schema, Map<String, Object> arguments) {
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            return ToolValidationResult.ok();
        }
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            JsonNode property = properties.path(entry.getKey());
            if (property.isMissingNode()) {
                continue;
            }
            ToolValidationResult typeResult = validateType(entry.getKey(), property.path("type").asText(""), entry.getValue());
            if (!typeResult.valid()) {
                return typeResult;
            }
            ToolValidationResult enumResult = validateEnum(entry.getKey(), property.path("enum"), entry.getValue());
            if (!enumResult.valid()) {
                return enumResult;
            }
        }
        return ToolValidationResult.ok();
    }

    private ToolValidationResult validateType(String fieldName, String type, Object value) {
        if (type == null || type.isBlank() || value == null) {
            return ToolValidationResult.ok();
        }
        boolean valid = switch (type) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };
        return valid
                ? ToolValidationResult.ok()
                : ToolValidationResult.invalid("argument " + fieldName + " should be " + type);
    }

    private ToolValidationResult validateEnum(String fieldName, JsonNode enumNode, Object value) {
        if (!enumNode.isArray() || value == null) {
            return ToolValidationResult.ok();
        }
        String actual = String.valueOf(value);
        for (JsonNode allowed : enumNode) {
            if (actual.equals(allowed.asText())) {
                return ToolValidationResult.ok();
            }
        }
        return ToolValidationResult.invalid("argument " + fieldName + " is not in enum " + enumNode);
    }
}
