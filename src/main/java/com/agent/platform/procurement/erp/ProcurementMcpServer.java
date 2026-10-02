package com.agent.platform.procurement.erp;

import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Standalone stdio MCP server. stdout is exclusively JSON-RPC. */
public final class ProcurementMcpServer {
    public static void main(String[] args) throws Exception {
        serve(new ProcurementMcpService(ErpNextProcurementAdapter.fromEnvironment(),
                RfqAuthorization.fromEnvironment(),
                System.getenv().getOrDefault("ERPNEXT_DEPARTMENT","V2-ALG")),System.in,System.out);
    }
    public static void serve(ProcurementMcpService service,InputStream in,OutputStream out) throws IOException {
        var json=new ObjectMapper();
        var reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));
        var writer=new BufferedWriter(new OutputStreamWriter(out,StandardCharsets.UTF_8));
        String line;
        while((line=reader.readLine())!=null) {
            Object id=null; Object response;
            try {
                var request=json.readTree(line); if(!request.hasNonNull("id")) continue;
                id=json.convertValue(request.get("id"),Object.class);
                Object result=switch(request.path("method").asText()) {
                    case "initialize" -> Map.of("protocolVersion","2025-11-25","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","procurement-erpnext","version","1.0"));
                    case "ping" -> Map.of();
                    case "tools/list" -> Map.of("tools",service.tools());
                    case "tools/call" -> {
                        try {
                            Object data=service.call(request.path("params").path("name").asText(),request.path("params").path("arguments"));
                            yield Map.of("isError",false,"structuredContent",data,"content",java.util.List.of(Map.of("type","text","text",json.writeValueAsString(data))));
                        } catch(ProcurementErpException ex) {
                            yield Map.of("isError",true,"content",java.util.List.of(Map.of("type","text","text",json.writeValueAsString(Map.of("error",Map.of("code",ex.code().name()))))));
                        }
                    }
                    default -> throw new IllegalArgumentException("unknown method");
                };
                response=Map.of("jsonrpc","2.0","id",id,"result",result);
            } catch(Exception ex) {
                var error=new java.util.LinkedHashMap<String,Object>(); error.put("jsonrpc","2.0");error.put("id",id);
                error.put("error",Map.of("code",-32600,"message","Invalid MCP request")); response=error;
            }
            writer.write(json.writeValueAsString(response));writer.newLine();writer.flush();
        }
    }
}
