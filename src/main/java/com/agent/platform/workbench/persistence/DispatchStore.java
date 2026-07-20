package com.agent.platform.workbench.persistence;

import com.agent.platform.workbench.dispatch.DispatchClaim;
import com.agent.platform.workbench.dispatch.DispatchResult;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.DispatchAttempt;
import com.agent.platform.workbench.model.DispatchRecoveryCandidate;
import com.agent.platform.workbench.model.RoutePreview;
import com.agent.platform.workbench.model.RoutingDecisionRecord;
import com.agent.platform.workbench.model.WorkLink;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DispatchStore {
    RoutePreview ensurePreview(AuthenticatedPrincipal principal,
                               AgentWorkItem workItem,
                               RoutingDecisionRecord routingDecision,
                               long ttlSeconds);
    Optional<RoutePreview> findPreview(AuthenticatedPrincipal principal, String workItemId);
    AgentWorkItem confirmPreview(AuthenticatedPrincipal principal,
                                 String workItemId,
                                 String previewId,
                                 int previewVersion,
                                 String validatedInputDigest,
                                 String scopeDigest);
    AgentWorkItem rejectPreview(AuthenticatedPrincipal principal, String workItemId, String previewId);
    Optional<DispatchClaim> claimDispatch(AuthenticatedPrincipal principal,
                                          String workItemId,
                                          Instant staleBefore,
                                          int maxAttempts);
    WorkLink completeDispatch(AuthenticatedPrincipal principal, DispatchClaim claim, DispatchResult result);
    DispatchAttempt failDispatch(AuthenticatedPrincipal principal,
                                 DispatchClaim claim,
                                 String failureCode,
                                 String failureReason,
                                 long retryBackoffMillis,
                                 int maxAttempts);
    List<DispatchRecoveryCandidate> findStaleDispatch(Instant staleBefore, int limit);
    List<DispatchAttempt> listAttempts(AuthenticatedPrincipal principal, String workItemId);
}
