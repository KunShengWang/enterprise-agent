import com.agent.platform.EnterpriseAgentApplication;
import com.agent.platform.config.*;
import com.agent.platform.mcp.*;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.erp.ProcurementMcpServer;
import com.agent.platform.tool.ToolRuntimeContract;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** Readiness only: real connection declarations, no model call or ERP write. */
class ReliabilityPreflight {
    public static void main(String[] args) throws Exception {
        try (var context = new SpringApplicationBuilder(EnterpriseAgentApplication.class)
                .profiles("erpnext").web(WebApplicationType.NONE)
                .properties("spring.main.lazy-initialization=true", "enterprise-agent.mock-mode=false",
                        "enterprise-agent.model-tool-calling-mode=native", "enterprise-agent.workbench.web.enabled=false").run()) {
            if (!"erpnext".equals(context.getEnvironment().getProperty("enterprise-agent.procurement.backend")))
                throw new IllegalStateException("ERP backend required");
            var json = context.getBean(ObjectMapper.class);
            var configured = context.getBean(McpProperties.class).getServers().stream()
                    .filter(s -> s.isEnabled() && "erpnext".equals(s.getServerName())
                            && "mcp.erpnext.".equals(s.getToolNamePrefix())).findFirst().orElseThrow();
            if (!configured.isTrustedProcurement()) throw new IllegalStateException("operator trust required");
            var server = new McpProperties.Server();
            server.setServerName("erpnext"); server.setToolNamePrefix("mcp.erpnext.");
            server.setTrustedProcurement(configured.isTrustedProcurement());
            server.setCommand(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
            server.setArgs(List.of("-cp", System.getProperty("java.class.path"), ProcurementMcpServer.class.getName()));
            var properties = new McpProperties(); properties.setServers(List.of(server));
            var gateway = new StdioMcpToolGateway(properties, json);
            try {
                var definitions = gateway.discoverTools();
                if (definitions.size() != 14) throw new IllegalStateException("unexpected MCP tool count");
                for (var d : definitions) {
                    boolean write = d.name().equals("mcp.erpnext.procurement_create_rfq");
                    if (d.runtimeContract().source() != ToolRuntimeContract.Source.MCP
                            || d.runtimeContract().effect() != (write ? ToolRuntimeContract.Effect.EXTERNAL_SIDE_EFFECT : ToolRuntimeContract.Effect.READ_ONLY))
                        throw new IllegalStateException("unexpected resolved effect: " + d.name());
                }
                var profile = context.getBean(ProcurementSourcingExecutionProfileFactory.class).createProfile();
                Files.writeString(Path.of(args[0]), json.writeValueAsString(Map.of(
                        "backend", "erpnext", "profile", profile, "definitions", definitions,
                        "maxToolExecutionAttempts", context.getBean(AgentProperties.class).getMaxToolExecutionAttempts(),
                        "providerCalls", 0)));
            } finally { gateway.shutdown(); }
        }
    }
}
