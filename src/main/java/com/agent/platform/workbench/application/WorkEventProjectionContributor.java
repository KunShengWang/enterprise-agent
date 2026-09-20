package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.WorkExecutionProjection;
import com.agent.platform.workbench.model.WorkProjectionClaim;
import com.agent.platform.workbench.model.WorkProjectionSource;
import com.agent.platform.workbench.persistence.WorkEventProjectionStore;
import java.util.Set;

/** Limited historical mappings; the core owns claims, cursors, fencing and reconciliation. */
public interface WorkEventProjectionContributor {
    Set<String> sourceTypes();
    int projectEvents(WorkProjectionClaim claim, long cursor, int eventBatchSize, WorkEventProjectionStore store);
    WorkExecutionProjection executionProjection(WorkProjectionSource source);
}
