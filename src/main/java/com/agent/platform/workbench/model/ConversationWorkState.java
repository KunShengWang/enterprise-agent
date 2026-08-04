package com.agent.platform.workbench.model;

import java.time.Instant;

public record ConversationWorkState(
        String conversationId,
        String tenantId,
        String ownerPrincipalId,
        String focusedWorkItemId,// 当前 Conversation 的“工作焦点状态”，主要用来知道用户当前正在操作哪个 WorkItem
        long version,
        Instant updatedAt
) {
}
