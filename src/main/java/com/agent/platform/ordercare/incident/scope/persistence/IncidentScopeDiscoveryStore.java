package com.agent.platform.ordercare.incident.scope.persistence;

import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCandidate;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeSnapshot;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface IncidentScopeDiscoveryStore {
    IncidentScopeSnapshot createOrLoad(IncidentScopeSnapshot requested);
    Optional<IncidentScopeSnapshot> find(AuthenticatedPrincipal principal, String snapshotId);
    Optional<IncidentScopeSnapshot> findByDiscoveryRequestId(
            AuthenticatedPrincipal principal, String discoveryRequestId);
    IncidentScopeClaim claim(AuthenticatedPrincipal principal, String discoveryRequestId,
                             String leaseOwner, Duration leaseDuration);
    IncidentScopeSnapshot complete(AuthenticatedPrincipal principal, String snapshotId,
                                   String leaseOwner, long fencingToken,
                                   List<IncidentScopeCandidate> candidates,
                                   Map<String, String> sourceHealth,
                                   String candidateFingerprint, boolean truncated);
    IncidentScopeSnapshot markWaitingConfirmation(AuthenticatedPrincipal principal,
                                                  String snapshotId, long expectedVersion);
    IncidentScopeSnapshot confirm(AuthenticatedPrincipal principal, String snapshotId,
                                  long expectedVersion, String candidateFingerprint);
    IncidentScopeSnapshot fail(AuthenticatedPrincipal principal, String snapshotId,
                               String leaseOwner, long fencingToken, String failureCode);
}
