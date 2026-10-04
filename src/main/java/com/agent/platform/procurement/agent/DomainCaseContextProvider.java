package com.agent.platform.procurement.agent;

import com.agent.platform.procurement.application.ProcurementCaseContextRenderer;
import com.agent.platform.runtime.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Uses the authoritative shared Case while reading each agent's conversation from its own session. */
@Component
public final class DomainCaseContextProvider implements AgentCanonicalContextProvider {
    private final ProcurementCaseContextRenderer renderer;
    private final AgentRunLinks links;
    public DomainCaseContextProvider(ProcurementCaseContextRenderer renderer,AgentRunLinks links){this.renderer=renderer;this.links=links;}
    @Override public Optional<CanonicalContext> provide(String tenant,String user,String session,AgentExecutionProfile profile){
        if(profile==null || !Set.of(AgentRegistry.SUPERVISOR,AgentRegistry.REQUISITION,AgentRegistry.SOURCING,AgentRegistry.RFQ).contains(profile.name()))return Optional.empty();
        String business=session;
        if(!AgentRegistry.SUPERVISOR.equals(profile.name())){
            int start=session.lastIndexOf(":domain:");if(start<0)throw new IllegalArgumentException("isolated domain session required");
            business=links.businessSession(session.substring(start+8),session);
        }
        return renderer.render(tenant,user,business).map(r->new CanonicalContext("domain-case-"+session,r.content(),r.metadata()));
    }
}
