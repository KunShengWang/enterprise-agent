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
            // 解析工具的 JSON Schema
            JsonNode schema = objectMapper.readTree(definition.inputSchema());
            // 必填参数校验
            ToolValidationResult requiredResult = validateRequired(schema, request.arguments());
            if (!requiredResult.valid()) {
                return requiredResult;
            }
            // 工具参数校验
            return validateProperties(schema, request.arguments());
        }
        catch (Exception exception) {
            return ToolValidationResult.invalid("invalid tool schema: " + exception.getMessage());
        }
    }

    /**
     * 工具必填参数校验
     */
    private ToolValidationResult validateRequired(JsonNode schema, Map<String, Object> arguments) {
        // Schema 声明的必填字段
        JsonNode required = schema.path("required");
        if (!required.isArray()) {
            return ToolValidationResult.ok();
        }
        for (JsonNode field : required) {
            String fieldName = field.stringValue("");
            Object value = arguments.get(fieldName);
            if (value == null || (value instanceof String text && text.isBlank())) {
                return ToolValidationResult.invalid("missing required argument: " + fieldName);
            }
        }
        return ToolValidationResult.ok();
    }

    /**
     * 工具参数校验
     */
    private ToolValidationResult validateProperties(JsonNode schema, Map<String, Object> arguments) {
        JsonNode properties = schema.path("properties");
        // Schema 没声明 properties → 跳过
        if (!properties.isObject()) {
            return ToolValidationResult.ok();
        }
        // 遍历模型实际传的参数
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            // 在 Schema 里找该字段定义
            JsonNode property = properties.path(entry.getKey());
            if (property.isMissingNode()) {
                continue;// Schema 没声明 → 不校验（放行）
            }
            // 类型校验
            ToolValidationResult typeResult = validateType(
                    entry.getKey(), property.path("type").stringValue(""), entry.getValue()
            );
            if (!typeResult.valid()) {
                return typeResult;// 类型不对 → 失败
            }
            // 枚举校验
            ToolValidationResult enumResult = validateEnum(entry.getKey(), property.path("enum"), entry.getValue());
            if (!enumResult.valid()) {
                return enumResult;// 不在枚举里 → 失败
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
            if (actual.equals(allowed.stringValue(""))) {
                return ToolValidationResult.ok();
            }
        }
        return ToolValidationResult.invalid("argument " + fieldName + " is not in enum " + enumNode);
    }
}
