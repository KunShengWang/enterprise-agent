package com.agent.platform.ordercare.incident.scope.client;

import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCriteria;

import java.util.List;

public interface FlowOrderScopeDiscoveryClient {
    FlowOrderOrderCandidates discoverOrders(String discoveryRequestId,
                                             IncidentScopeCriteria criteria,
                                             int limit,
                                             String cursor,
                                             String traceId);
    FlowOrderResourceEnrichment enrichResources(String discoveryRequestId,
                                                List<String> requestIds,
                                                List<String> deductNos,
                                                IncidentScopeCriteria criteria,
                                                String traceId);
}
