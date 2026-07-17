package com.agent.platform.ordercare.client;

import com.agent.platform.ordercare.model.OrderCareCaseSnapshot;

public interface FlowOrderClient {

    OrderCareCaseSnapshot inspectCase(String identifierType,
                                      String identifierValue,
                                      String traceId);
}
