package com.agent.platform.procurement.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;
import java.util.*;

/** Resolve the explicit backend before Spring evaluates provider/gateway conditions. */
public final class ProcurementBackendEnvironment implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String shortName=env.getProperty("procurement.backend");
        String fullName=env.getProperty("enterprise-agent.procurement.backend");
        if(shortName!=null && fullName!=null && !shortName.equals(fullName))
            throw new IllegalStateException("Conflicting procurement backend configuration");
        String backend=shortName==null?fullName:shortName;
        if(backend==null) {
            if(env.getProperty("enterprise-agent.procurement.erpnext.enabled",Boolean.class,false)) backend="erpnext";
            else return; // Preserve the existing explicit synthetic / generic MCP configuration.
        }
        if(!Set.of("synthetic","erpnext").contains(backend)) throw new IllegalStateException("Unsupported procurement backend");
        boolean erp=backend.equals("erpnext");
        Map<String,Object> resolved=new LinkedHashMap<>();
        resolved.put("enterprise-agent.procurement.backend",backend);
        resolved.put("enterprise-agent.procurement.provider",erp?"mcp":"synthetic");
        resolved.put("enterprise-agent.procurement.erpnext.enabled",erp);
        if(erp) {
            resolved.put("enterprise-agent.procurement.mcp-tool-prefix","mcp.erpnext.");
            resolved.put("enterprise-agent.procurement.scenario-dir","__legacy_scenario_unavailable_in_v2__");
            resolved.put("enterprise-agent.procurement.scenario-file","");
        }
        env.getPropertySources().addFirst(new MapPropertySource("procurementBackend",resolved));
    }
}
