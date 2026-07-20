package com.agent.platform.workbench.dispatch;

import com.agent.platform.workbench.target.ExecutionTargetId;

import java.util.Optional;

public interface ExecutionAdapter {
    ExecutionTargetId targetId();
    DispatchResult dispatch(DispatchRequest request);
    Optional<DispatchResult> reconcile(DispatchRequest request);
}
