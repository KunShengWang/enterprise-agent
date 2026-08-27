package com.agent.platform.workbench.application;

import com.agent.platform.config.WorkbenchRoutingProperties;
import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.DecisionStatus;
import com.agent.platform.workbench.model.RoutingAttempt;
import com.agent.platform.workbench.model.RoutingDecisionRecord;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.persistence.RoutingStore;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.ExecutionTargetId;
import com.agent.platform.workbench.target.ExecutionTargetRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingCoordinatorCandidatePolicyTests {

    @Test
    void completeSingleCaseBypassesModelAndPersistsOrderCareDecision() {
        String goal = "请处理一个唯一的 OrderCare 单案例。案例标识：requestId=ORDERCARE-M05-REQUEST。"
                + "请查询订单、库存扣减和死信事实并检索 SOP；满足条件时创建恢复预演，"
                + "申请审批，审批后执行恢复并验证最终收敛。";
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "tenant", "operator", Set.of("INCIDENT_OPERATOR"));
        AgentWorkItem work = work(goal);
        RoutingStore routingStore = mock(RoutingStore.class);
        WorkbenchStore workbenchStore = mock(WorkbenchStore.class);
        UnifiedTaskRouter modelRouter = mock(UnifiedTaskRouter.class);
        RouteContextResolver contextResolver = mock(RouteContextResolver.class);
        IncidentCommandProperties incident = new IncidentCommandProperties();
        incident.setEnabled(true);
        incident.setRecoveryPlannerEnabled(true);
        ExecutionTargetRegistry registry = new ExecutionTargetRegistry(incident);
        WorkbenchRoutingProperties properties = new WorkbenchRoutingProperties();
        properties.setEnabled(true);

        when(workbenchStore.findWorkItem(principal, work.workItemId())).thenReturn(Optional.of(work));
        when(contextResolver.resolve(principal, work)).thenReturn(new ResolvedRouteContext("", Map.of(), Map.of()));
        RoutingAttempt attempt = new RoutingAttempt(
                "decision-1", work.workItemId(), work.routingRequestId(), 1, "trace-1");
        when(routingStore.claimRouting(eq(principal), eq(work.workItemId()), eq(work.routingRequestId()),
                any(), anyInt(), anyLong(), anyString(), anyString(), any())).thenReturn(Optional.of(attempt));
        when(routingStore.completeRouting(eq(principal), eq(attempt), any(), any())).thenAnswer(invocation -> {
            RouterModelResult result = invocation.getArgument(2);
            var validation = (com.agent.platform.workbench.model.RouteValidationResult) invocation.getArgument(3);
            Instant now = Instant.now();
            return new RoutingDecisionRecord(
                    attempt.decisionId(), work.workItemId(), work.routingRequestId(), 1,
                    DecisionStatus.EFFECTIVE, result.modelName(), properties.getCatalogVersion(),
                    result.promptDigest(), result.rawOutputDigest(),
                    Map.of("targetId", result.decision().targetId(),
                            "extractedInputs", result.decision().extractedInputs(),
                            "missingInputs", result.decision().missingInputs()),
                    Map.of("disposition", validation.disposition().name()),
                    result.promptTokens(), result.completionTokens(), result.latencyMs(),
                    validation.failureCode(), "", "trace-1", now, now);
        });

        RoutingCoordinator coordinator = new RoutingCoordinator(
                routingStore, workbenchStore, modelRouter,
                new RoutePolicyValidator(registry, properties, new ObjectMapper()),
                contextResolver, registry, properties, (ignoredAttempt, ignoredResult) -> { });

        RoutingDecisionRecord completed = coordinator.route(
                principal, work.workItemId(), work.routingRequestId()).orElseThrow();

        verify(modelRouter, never()).route(any());
        ArgumentCaptor<RouterModelResult> resultCaptor = ArgumentCaptor.forClass(RouterModelResult.class);
        verify(routingStore).completeRouting(eq(principal), eq(attempt), resultCaptor.capture(), any());
        assertEquals(ExecutionTargetId.ORDERCARE_CASE.name(), resultCaptor.getValue().decision().targetId());
        assertEquals("ORDERCARE-M05-REQUEST",
                resultCaptor.getValue().decision().extractedInputs().get("requestId"));
        assertEquals(ExecutionTargetCandidateResolver.POLICY_VERSION, completed.modelName());
    }

    private AgentWorkItem work(String goal) {
        Instant now = Instant.now();
        return new AgentWorkItem(
                "work-1", "conversation-1", "tenant", "operator", goal, goal,
                WorkControlState.ROUTING, WorkExecutionState.NOT_STARTED, WorkOutcome.UNDETERMINED,
                "", "", "", "", "", "input-1", "", "route-1", 0,
                null, null, "", "", 0, 0, now, now, null);
    }
}
