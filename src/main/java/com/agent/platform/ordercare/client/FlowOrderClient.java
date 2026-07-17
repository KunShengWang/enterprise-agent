package com.agent.platform.ordercare.client;

import com.agent.platform.ordercare.model.OrderCareCaseSnapshot;
import com.agent.platform.ordercare.model.OrderCareProposalCreateCommand;
import com.agent.platform.ordercare.model.OrderCareProposalExecuteCommand;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import com.agent.platform.ordercare.model.OrderCareRecoveryAction;
import com.agent.platform.ordercare.model.OrderCareActionReconcileCommand;

public interface FlowOrderClient {

    OrderCareCaseSnapshot inspectCase(String identifierType,
                                      String identifierValue,
                                      String traceId);

    OrderCareRecoveryProposal createProposal(OrderCareProposalCreateCommand command, String traceId);

    OrderCareRecoveryProposal getProposal(String proposalId, String traceId);

    /** 写调用只发送一次；可能已发出的异常由调用方按 UNKNOWN 处理。 */
    OrderCareRecoveryProposal executeProposal(OrderCareProposalExecuteCommand command, String traceId);

    OrderCareRecoveryAction getAction(String actionRequestId, String traceId);

    /** 可能接管过期租约，只发送一次；网络异常仍按 UNKNOWN 处理。 */
    OrderCareRecoveryAction reconcileAction(
            String actionRequestId,
            OrderCareActionReconcileCommand command,
            String traceId
    );
}
