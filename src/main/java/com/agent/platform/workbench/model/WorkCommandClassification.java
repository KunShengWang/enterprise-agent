package com.agent.platform.workbench.model;

public record WorkCommandClassification(
        WorkCommandType commandType,       // 分类命令类型（核心结论）
        double modelConfidence,            // 模型置信度
        String reason,                     // 分类理由
        String targetWorkItemId,           // 目标工作项 ID
        String derivedGoalText             // 派生目标文本，仅当 commandType == START_NEW_WORK（明确要开新任务）时才填，记录新任务的目标内容
) {
    public WorkCommandClassification {
        if (commandType == null) {
            throw new IllegalArgumentException("commandType must not be null");
        }
        modelConfidence = Math.max(0, Math.min(1, modelConfidence));
        reason = reason == null ? "" : reason.trim();
        targetWorkItemId = targetWorkItemId == null ? "" : targetWorkItemId.trim();
        derivedGoalText = derivedGoalText == null ? "" : derivedGoalText.trim();
        if (commandType == WorkCommandType.START_NEW_WORK && derivedGoalText.isBlank()) {
            throw new IllegalArgumentException("START_NEW_WORK requires derivedGoalText");
        }
    }
}
