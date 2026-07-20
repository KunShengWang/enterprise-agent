package com.agent.platform.workbench.model;

public enum WorkCommandType {
    /** 继续当前工作任务 */
    RESUME_ACTIVE_WORK,
    /** 放弃当前工作任务（不关心了） */
    ABANDON_ACTIVE_WORK,
    /** 暂停当前工作任务 */
    PAUSE_ACTIVE_WORK,
    /** 取消底层执行（杀掉正在跑的 Agent run） */
    CANCEL_ACTIVE_WORK,
    /** 向当前工作任务追加输入 */
    ADD_INPUT_TO_ACTIVE_WORK,
    /** 创建新工作任务（需带新目标文本） */
    START_NEW_WORK,
    /** 独立目标（与当前任务无关的自然语言问答） */
    NORMAL_GOAL,
    /** 意图模糊，无法唯一解析 */
    AMBIGUOUS
}

