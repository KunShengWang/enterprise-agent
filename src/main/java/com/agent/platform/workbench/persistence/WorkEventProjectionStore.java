package com.agent.platform.workbench.persistence;

import com.agent.platform.workbench.model.ProjectedWorkEventDraft;
import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.model.WorkProjectionSource;

import java.util.List;

public interface WorkEventProjectionStore {
    List<WorkProjectionSource> listProjectionSources(int limit);
    long projectionCursor(String workItemId, String sourceType, String sourceId);
    WorkEvent appendProjectedEvent(String workItemId, ProjectedWorkEventDraft event);
    void advanceProjectionCursor(String workItemId, String sourceType, String sourceId, long sourceSequence);
}
