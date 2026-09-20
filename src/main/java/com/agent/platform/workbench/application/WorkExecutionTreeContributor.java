package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.UnifiedWorkExecutionTree;
import com.agent.platform.workbench.model.WorkLink;
import com.agent.platform.workbench.model.WorkLinkType;

/** Domain projection invoked only after the work item's ownership and primary link are checked. */
public interface WorkExecutionTreeContributor {
    boolean supports(WorkLinkType linkType);
    UnifiedWorkExecutionTree project(AgentWorkItem workItem, WorkLink link);
}
