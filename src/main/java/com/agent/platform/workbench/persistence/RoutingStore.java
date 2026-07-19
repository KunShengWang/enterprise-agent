package com.agent.platform.workbench.persistence;

import com.agent.platform.workbench.application.CommandClassifierResult;
import com.agent.platform.workbench.application.RouterModelResult;
import com.agent.platform.workbench.application.RouterFailureObservation;
import com.agent.platform.workbench.model.AgentConversationTurn;
import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.RouteValidationResult;
import com.agent.platform.workbench.model.RoutingAttempt;
import com.agent.platform.workbench.model.RoutingDecisionRecord;
import com.agent.platform.workbench.model.RoutingRecoveryCandidate;
import com.agent.platform.workbench.model.WorkCommandDecision;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RoutingStore {

    AgentConversationTurn persistUnclassifiedInput(AuthenticatedPrincipal principal,
                                                    String inputId,
                                                    String clientInputId,
                                                    String conversationId,
                                                    String content);

    WorkCommandDecision beginCommandAttempt(AuthenticatedPrincipal principal,
                                            String inputId,
                                            ClassifierType classifierType,
                                            String traceId);

    WorkCommandDecision completeCommandAttempt(AuthenticatedPrincipal principal,
                                               String commandDecisionId,
                                               CommandClassifierResult result);

    WorkCommandDecision failCommandAttempt(AuthenticatedPrincipal principal,
                                           String commandDecisionId,
                                           String failureCode,
                                           String failureReason);

    Optional<WorkCommandDecision> findEffectiveCommand(AuthenticatedPrincipal principal, String inputId);

    List<WorkCommandDecision> listCommandDecisions(AuthenticatedPrincipal principal, String inputId);

    Optional<RoutingAttempt> claimRouting(AuthenticatedPrincipal principal,
                                          String workItemId,
                                          String routingRequestId,
                                          Instant staleBefore,
                                          int maxAttempts,
                                          long unknownResultTokenReserve,
                                          String catalogVersion);

    RoutingDecisionRecord completeRouting(AuthenticatedPrincipal principal,
                                          RoutingAttempt attempt,
                                          RouterModelResult modelResult,
                                          RouteValidationResult validation);

    RoutingDecisionRecord failRouting(AuthenticatedPrincipal principal,
                                      RoutingAttempt attempt,
                                      String failureCode,
                                      String failureReason,
                                      RouterFailureObservation observation,
                                      long retryBackoffMillis,
                                      int maxAttempts);

    List<RoutingRecoveryCandidate> findStaleRouting(Instant staleBefore, int limit);

    Optional<RoutingDecisionRecord> findEffectiveRouting(AuthenticatedPrincipal principal, String workItemId);

    List<RoutingDecisionRecord> listRoutingDecisions(AuthenticatedPrincipal principal, String workItemId);

    long totalRoutingTokens(AuthenticatedPrincipal principal, String workItemId);
}
