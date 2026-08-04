package com.agent.platform.workbench.persistence;

import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.WorkCommandExecution;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;

import java.time.Duration;
import java.util.Optional;

public interface WorkCommandExecutionStore {

    /**
     * 对同一条命令输入，只有一个人能真正执行，并给执行者一个有有效期的租约，防止并发冲突和重复执行
     */
    WorkCommandClaim claim(AuthenticatedPrincipal principal,
                           String inputId,
                           String workItemId,
                           WorkCommandType commandType,
                           long expectedWorkVersion,
                           String leaseOwner,
                           Duration leaseDuration);

    /**
     * 当一个命令（暂停/继续/取消等）被真正执行完之后，把执行结果持久化到数据库，并释放租约，同时更新 WorkItem 的状态
     */
    WorkCommandExecution complete(AuthenticatedPrincipal principal,
                                  String commandRequestId,
                                  String leaseOwner,
                                  long claimToken,
                                  WorkCommandCompletion completion);

    Optional<WorkCommandExecution> findByInput(AuthenticatedPrincipal principal, String inputId);

    WorkCommandExecution recordUnboundRejection(AuthenticatedPrincipal principal,
                                                 String inputId,
                                                 WorkCommandType commandType,
                                                 String resultCode,
                                                 String message);

    /**
     * 重新从数据库加载这个 WorkItem 的最新状态，并保证它必须存在（否则抛异常）。
     */
    AgentWorkItem requireWorkItem(AuthenticatedPrincipal principal, String workItemId);
}
