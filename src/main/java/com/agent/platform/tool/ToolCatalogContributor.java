package com.agent.platform.tool;

import java.util.List;

/**
 * 业务模块向统一 Tool Registry 贡献窄工具定义的扩展点。
 */
public interface ToolCatalogContributor {

    List<ToolDefinition> definitions();
}
