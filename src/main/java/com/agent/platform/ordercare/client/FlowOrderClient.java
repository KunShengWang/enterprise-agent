package com.agent.platform.ordercare.client;

import com.agent.platform.ordercare.model.OrderCareCaseSnapshot;
import com.agent.platform.ordercare.model.OrderCareProposalCreateCommand;
import com.agent.platform.ordercare.model.OrderCareProposalExecuteCommand;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;

public interface FlowOrderClient {

    OrderCareCaseSnapshot inspectCase(String identifierType,
                                      String identifierValue,
                                      String traceId);

    OrderCareRecoveryProposal createProposal(OrderCareProposalCreateCommand command, String traceId);

    OrderCareRecoveryProposal getProposal(String proposalId, String traceId);

    /** 写调用只发送一次；可能已发出的异常由调用方按 UNKNOWN 处理。 */
    OrderCareRecoveryProposal executeProposal(OrderCareProposalExecuteCommand command, String traceId);
}
