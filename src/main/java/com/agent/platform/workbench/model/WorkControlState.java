package com.agent.platform.workbench.model;

public enum WorkControlState {
    /** 正在分析请求并选择执行目标。 */
    ROUTING,

    /** 缺少必要信息，等待用户补充输入。 */
    WAITING_INPUT,

    /** 路由预览已生成，等待用户确认后再派发。 */
    WAITING_CONFIRMATION,

    /** 自动处理无法安全继续，等待人工核查。 */
    MANUAL_REVIEW,

    /** 路由和必要确认已完成，可以开始派发。 */
    READY_TO_DISPATCH,

    /** 正在抢占并执行派发操作。 */
    DISPATCHING,

    /** 已派发到目标执行器，但不代表底层任务已经完成。 */
    DISPATCHED,

    /** 已请求暂停，等待底层执行到达安全暂停点。 */
    PAUSE_REQUESTED,

    /** 底层执行已经暂停，可以从检查点恢复。 */
    PAUSED,

    /** 已请求取消，等待底层执行确认并收敛到终态。 */
    CANCEL_REQUESTED,

    /** 当前工作项已被放弃，不再由 Workbench 控制；底层执行不一定停止。 */
    ABANDONED,

    /** 工作项已经关闭，不再接受后续控制操作。 */
    CLOSED
}
