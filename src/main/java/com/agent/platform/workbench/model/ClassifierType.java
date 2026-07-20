package com.agent.platform.workbench.model;

public enum ClassifierType {

    /**
     * 如：用户点了前端的按钮（"继续""取消""新建任务"）
     */
    DETERMINISTIC_BUTTON,// 确定性按钮

    /**
     * 如：外部系统通过 API / MCP 协议调过来的明确指令
     */
    DETERMINISTIC_PROTOCOL,// 确定性协议

    /**
     * 不确定的意图
     * 如：用户在聊天框里打字的内容，不知道啥意图
     */
    MODEL// 模型
}

