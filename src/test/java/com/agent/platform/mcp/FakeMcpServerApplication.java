package com.agent.platform.mcp;

import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只用于验证 StdioMcpToolGateway 生命周期的自包含 MCP fake server。 */
public final class FakeMcpServerApplication {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeMcpServerApplication() {
    }

    public static void main(String[] args) throws Exception {
        String mode = argument(args, 0, "normal");
        String firstTool = argument(args, 1, "echo");
        String secondTool = argument(args, 2, "probe");
        String markerPath = argument(args, 3, "");
        String pidPath = argument(args, 4, "");
        String eventPath = argument(args, 5, "");
        String oneShotPath = argument(args, 6, "");
        writeMarker(markerPath, "started");

        String sessionToken = UUID.randomUUID().toString();
        writeMarker(pidPath, String.valueOf(ProcessHandle.current().pid()));
        writeEvent(eventPath, "started pid=" + ProcessHandle.current().pid() + " token=" + sessionToken);
        int listCount = 0;
        int callCount = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Map<?, ?> request = readMap(line);
                if (request == null) {
                    continue;
                }
                String method = stringValue(request.get("method"));
                Object id = request.get("id");
                if (id == null) {
                    if ("notifications/initialized".equals(method)) {
                        writeEvent(eventPath, "initialized");
                    }
                    continue;
                }
                switch (method) {
                    case "initialize" -> {
                        writeEvent(eventPath, "initialize");
                        writeResult(writer, id, Map.of(
                                "protocolVersion", "2025-11-25",
                                "capabilities", Map.of(),
                                "serverInfo", Map.of("name", "fake-mcp", "version", "1.0")));
                    }
                    case "tools/list" -> {
                        listCount++;
                        writeEvent(eventPath, "list");
                        writeResult(writer, id, tools(mode, firstTool, secondTool, sessionToken, listCount));
                    }
                    case "tools/call" -> {
                        callCount++;
                        String toolName = toolName(request);
                        writeEvent(eventPath, "call " + toolName);
                        if ("die".equals(mode) && callCount == 1) {
                            System.exit(0);
                        }
                        if ("die-once".equals(mode) && callCount == 1 && claim(oneShotPath)) {
                            System.exit(0);
                        }
                        if ("hang".equals(mode) && callCount == 1) {
                            continue;
                        }
                        if ("late".equals(mode) && callCount == 1) {
                            Thread.sleep(1_500L);
                        }
                        if ("protocol-error".equals(mode) && "error".equals(toolName)) {
                            writeError(writer, id, "remote protocol failure");
                        }
                        else if ("result-error".equals(mode) && "error".equals(toolName)) {
                            writeResult(writer, id, Map.of(
                                    "content", List.of(Map.of("type", "text", "text", "remote tool error")),
                                    "isError", true));
                        }
                        else {
                            writeResult(writer, id, Map.of(
                                    "content", List.of(Map.of("type", "text", "text",
                                            "session=" + sessionToken + ";calls=" + callCount
                                                    + ";tool=" + toolName + ";list=" + listCount)),
                                    "isError", false));
                        }
                    }
                    default -> writeError(writer, id, "unknown method: " + method);
                }
            }
        }
    }

    private static Map<String, Object> tools(String mode, String firstTool, String secondTool,
                                             String sessionToken, int listCount) {
        List<Map<String, Object>> values = new ArrayList<>();
        values.add(tool(firstTool, sessionToken, listCount));
        if (!secondTool.isBlank() && !secondTool.equals(firstTool)) {
            values.add(tool(secondTool, sessionToken, listCount));
        }
        return Map.of("tools", values);
    }

    private static Map<String, Object> tool(String name, String sessionToken, int listCount) {
        return Map.of(
                "name", name,
                "description", "session=" + sessionToken + ";list=" + listCount,
                "inputSchema", Map.of("type", "object"));
    }

    private static void writeResult(BufferedWriter writer, Object id, Map<String, Object> result) throws Exception {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        write(writer, response);
    }

    private static void writeError(BufferedWriter writer, Object id, String message) throws Exception {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", -32000, "message", message));
        write(writer, response);
    }

    private static void write(BufferedWriter writer, Map<String, Object> response) throws Exception {
        writer.write(MAPPER.writeValueAsString(response));
        writer.newLine();
        writer.flush();
    }

    private static Map<?, ?> readMap(String line) throws Exception {
        Object parsed = MAPPER.readValue(line, Map.class);
        return parsed instanceof Map<?, ?> map ? map : null;
    }

    private static String toolName(Map<?, ?> request) {
        Object params = request.get("params");
        if (!(params instanceof Map<?, ?> values)) {
            return "";
        }
        return stringValue(values.get("name"));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String argument(String[] args, int index, String fallback) {
        return args.length > index && args[index] != null ? args[index] : fallback;
    }

    private static void writeMarker(String path, String value) throws Exception {
        if (path != null && !path.isBlank()) {
            Files.writeString(Path.of(path), value, StandardCharsets.UTF_8);
        }
    }

    private static void writeEvent(String path, String value) throws Exception {
        if (path != null && !path.isBlank()) {
            Files.writeString(Path.of(path), value + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        }
    }

    private static boolean claim(String path) throws Exception {
        if (path == null || path.isBlank()) {
            return true;
        }
        try {
            Files.createFile(Path.of(path));
            return true;
        }
        catch (FileAlreadyExistsException ignored) {
            return false;
        }
    }
}
