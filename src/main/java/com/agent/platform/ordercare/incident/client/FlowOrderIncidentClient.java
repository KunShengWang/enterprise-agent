package com.agent.platform.ordercare.incident.client;

import com.agent.platform.ordercare.incident.model.IncidentDeadLetterFacts;
import com.agent.platform.ordercare.incident.model.IncidentFactEnvelope;
import com.agent.platform.ordercare.incident.model.IncidentFactQuery;
import com.agent.platform.ordercare.incident.model.IncidentInventoryFacts;
import com.agent.platform.ordercare.incident.model.IncidentOrderFacts;

public interface FlowOrderIncidentClient {

    IncidentFactEnvelope<IncidentOrderFacts> queryOrderFacts(IncidentFactQuery query, String traceId);

    IncidentFactEnvelope<IncidentInventoryFacts> queryInventoryFacts(IncidentFactQuery query, String traceId);

    IncidentFactEnvelope<IncidentDeadLetterFacts> queryDeadLetterFacts(IncidentFactQuery query, String traceId);
}
