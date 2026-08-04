package com.agent.platform.workbench.target;

public enum ExecutionCommandSupport {
    SUPPORTED_EXISTING_RUNTIME,   // 支持，通过现有的 Runtime 执行
    PRODUCT_ONLY,                 // 仅产品层（只更新产品状态，不触及底层执行器）
    UNSUPPORTED                   // 不支持
}
