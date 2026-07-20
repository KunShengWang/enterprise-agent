# Unified Agent Workbench M2-D 历史回放与前端回归缺口矩阵

> 基线：M2-C `77f7a50`；本阶段只固化刷新、重启、历史分页和现有页面兼容性。

| 冻结要求 | 当前事实 | M2-D 补齐方式 | 门禁 |
|---|---|---|---|
| 历史 WorkItem 回放 | Detail 返回前 500 WorkEvent，SSE 可从 cursor 继续 | 证明 500+ 事件分页补齐、复合 cursor 回放和 eventId 去重 | 0..N 连续且无重复 |
| 主回答刷新恢复 | M2-B 从 PRIMARY RUN timeline 的 `-1` cursor 回放 delta | 新服务实例重建正文并与首次回放一致 | 正文无缺失、无重复 |
| 执行树刷新恢复 | M2-C 从权威 Store 即时投影 | 新 Store/Projector 实例重建相同树 | 不依赖旧 JVM 内存 |
| Conversation 切换 | selectedId/detail/SSE 可能残留旧会话 | 原子清空旧状态、关闭流、重新选择新会话 Focus | 不跨会话显示旧 WorkItem |
| 并发状态刷新 | 5 秒轮询可能重叠 | 单次 refresh 门禁并校验 conversation/selection | 旧响应不覆盖新会话 |
| 空会话 | 可能保留旧详情 | 明确清空 detail/tree/timeline/answer/cursor | 页面显示真实空状态 |
| 现有专项页面 | Router 仍保留 Runtime/Run/Approval/Incident 等路由 | production build + 本地 preview 路由 HTTP smoke | 不删除、不重定向旧路由 |
| 服务重启 | M2-B/M2-C 各自有局部门禁 | 组合新 JDBC Store、Timeline、SSE、Tree Service 重建 | 无第二 Run/WorkItem/副作用 |

## 允许修改

- Unified Workbench 会话切换、状态重置和刷新竞态；
- M2-D 历史/重启 PostgreSQL 测试、路由 smoke、证据和进度文档；
- 为测试历史分页所需的只读辅助代码。

## 禁止修改

- Runtime、Incident、Recovery Plan 执行与状态机；
- M2-A WorkEvent Schema/sequence 和 M2-B SSE cursor；
- 重新 dispatch/resume/create Run 来恢复页面；
- 将前端内存缓存作为历史事实源；
- M3 WorkCommand、预算、claim/lease 或 UNKNOWN 恢复能力。
