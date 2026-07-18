package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.IncidentFactQuery;
import com.agent.platform.ordercare.incident.model.IncidentMqFactsResult;

public interface IncidentMqFactsReader {

    IncidentMqFactsResult read(IncidentFactQuery query, String traceId);
}
