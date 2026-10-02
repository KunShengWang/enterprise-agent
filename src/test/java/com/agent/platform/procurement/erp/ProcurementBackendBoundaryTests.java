package com.agent.platform.procurement.erp;

import com.agent.platform.procurement.config.*;
import com.agent.platform.procurement.provider.*;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.mcp.McpToolGateway;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.test.context.support.TestPropertySourceUtils;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcurementBackendBoundaryTests {
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(ProcurementDataProperties.class)
    @Import({McpProcurementDataProvider.class,AwsSyntheticProcurementProvider.class})
    static class Config {
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean McpToolGateway gateway() { return mock(McpToolGateway.class); }
    }
    @Test void explicitBackendSelectsMcpAndUnavailableAuthTimeoutCannotUseSynthetic() {
        try(var context=new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE)
                .run("--procurement.backend=erpnext","--spring.main.banner-mode=off")) {
            assertTrue(context.getBeansOfType(AwsSyntheticProcurementProvider.class).isEmpty());
            var provider=context.getBean(ProcurementDataProvider.class);
            assertInstanceOf(McpProcurementDataProvider.class,provider);
            var props=context.getBean(ProcurementDataProperties.class);
            assertEquals("mcp.erpnext.",props.getMcpToolPrefix());
            var gateway=context.getBean(McpToolGateway.class);
            var definition=new ToolDefinition("mcp.erpnext.search_suppliers","internal","{}",ToolRiskLevel.LOW,Map.of());
            when(gateway.discoverTools()).thenReturn(List.of(definition));
            var state=new ProcurementCaseState("AI Workstation","V2-WS-001",20,new BigDecimal("300000"),"CNY",21,Map.of(),Map.of(),Set.of(),List.of(),"SOURCING");
            for(String code:List.of("ERP_UNAVAILABLE","ERP_AUTH_ERROR","ERP_TIMEOUT")) {
                when(gateway.callTool(any(ToolDefinition.class),any(ToolCallRequest.class))).thenReturn(
                        new ToolCallResult(definition.name(),false,"{\"error\":{\"code\":\""+code+"\"}}",code,Map.of()));
                assertThrows(IllegalStateException.class,()->provider.searchSuppliers(state),code);
            }
            when(gateway.discoverTools()).thenThrow(new IllegalStateException("MCP unavailable"));
            assertThrows(IllegalStateException.class,()->provider.searchSuppliers(state));
        }
    }
    @Test void explicitSyntheticAndUnknownBackendAreNotAmbiguous() {
        try(var context=new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE)
                .run("--procurement.backend=synthetic","--spring.main.banner-mode=off")) {
            assertInstanceOf(AwsSyntheticProcurementProvider.class,context.getBean(ProcurementDataProvider.class));
            assertTrue(context.getBeansOfType(McpProcurementDataProvider.class).isEmpty());
        }
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("procurement.backend","typo");
        assertThrows(IllegalStateException.class,()->new ProcurementBackendEnvironment().postProcessEnvironment(env,null));
        env.setProperty("procurement.backend","erpnext");env.setProperty("enterprise-agent.procurement.backend","synthetic");
        assertThrows(IllegalStateException.class,()->new ProcurementBackendEnvironment().postProcessEnvironment(env,null));
    }
}
