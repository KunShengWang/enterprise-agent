package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.WorkEvent;
import com.agent.platform.workbench.model.WorkEventDraft;
import com.agent.platform.workbench.persistence.WorkbenchStore;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

@Service
public class LocalWorkEventAppender {

    private final WorkbenchStore store;

    public LocalWorkEventAppender(WorkbenchStore store) {
        this.store = store;
    }

    public WorkEvent append(AuthenticatedPrincipal principal,
                            String workItemId,
                            WorkEventDraft event) {
        return store.appendLocalEvent(principal, workItemId, event);
    }
}
