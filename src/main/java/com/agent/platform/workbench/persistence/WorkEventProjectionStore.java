package com.agent.platform.workbench.persistence;

import com.agent.platform.workbench.model.ProjectedWorkEventDraft;
import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.model.WorkProjectionSource;
import com.agent.platform.workbench.model.WorkProjectionClaim;

import java.time.Instant;
import java.util.List;

public interface WorkEventProjectionStore {
    List<WorkProjectionSource> listProjectionSources(int limit);
    default List<WorkProjectionClaim> claimProjectionSources(String leaseOwner, Instant leaseUntil, int limit) {
        return listProjectionSources(limit).stream()
                .map(source -> new WorkProjectionClaim(source, leaseOwner, 1, leaseUntil))
                .toList();
    }
    long projectionCursor(String workItemId, String sourceType, String sourceId);
    WorkEvent appendProjectedEvent(String workItemId, ProjectedWorkEventDraft event);
    default WorkEvent appendProjectedEvent(WorkProjectionClaim claim, ProjectedWorkEventDraft event) {
        return appendProjectedEvent(claim.source().workItemId(), event);
    }
    void advanceProjectionCursor(String workItemId, String sourceType, String sourceId, long sourceSequence);
    default void advanceProjectionCursor(WorkProjectionClaim claim, long sourceSequence) {
        WorkProjectionSource source = claim.source();
        advanceProjectionCursor(source.workItemId(), source.sourceType(), source.sourceId(), sourceSequence);
    }
    default void releaseProjectionClaim(WorkProjectionClaim claim) { }
}
