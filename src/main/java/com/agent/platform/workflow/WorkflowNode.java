package com.agent.platform.workflow;

public enum WorkflowNode {
    START,
    LOAD_MEMORY,// 加载记忆
    INPUT_GUARDRAIL,// 输入护栏
    SELECT_SKILL,// 选择 skill
    ROUTE_INTENT,// 路由意图
    CLARIFY,// 阐明
    QUERY_REWRITE,// 查询重写
    RAG_RETRIEVE,
    TOOL_REGISTRY,// 工具注册表
    TOOL_PLAN,// 工具计划
    TOOL_GUARDRAIL,// 工具护栏
    TOOL_APPROVAL,// 工具审批
    TOOL_EXECUTE,// 工具执行
    CHAT_FALLBACK,
    PROMPT_ASSEMBLE,// 提示组装
    LLM_CALL,
    OUTPUT_GUARDRAIL,// 输出护栏
    SAVE_MEMORY,
    EVAL_RECORD,// 评估记录
    FINISH,
    BLOCKED,
    FAILED
}
