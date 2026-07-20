package com.agent.platform.workbench.security;

import com.agent.platform.config.WorkbenchWebProperties;
import org.springframework.stereotype.Component;

@Component
public class LocalWorkbenchPrincipalProvider implements WorkbenchPrincipalProvider {

    private final WorkbenchWebProperties properties;

    public LocalWorkbenchPrincipalProvider(WorkbenchWebProperties properties) {
        this.properties = properties;
    }

    @Override
    public AuthenticatedPrincipal current() {
        return new AuthenticatedPrincipal(
                properties.getLocalTenantId(),
                properties.getLocalPrincipalId(),
                properties.getLocalRoles()
        );
    }
}
