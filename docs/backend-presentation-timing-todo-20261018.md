# 后端待办：Presentation 投影时序导致工具调用滞后于回答

日期：2026-10-18。状态：**待实施**（前端侧已确认无解，见下文「为什么不在前端」）。

## 现象

一次真实采购任务（Run 在 16:11 前后）：

- 回答文本（`model-delta`）在 **16:11:35** 到达前端，打字机开始播放
- 工具调用的 presentation 直到 **16:11:40.042** 才开始到达，**16:11:40.675** 全部推完
- 用户看到的是「最终结果先出现，随后上方陆续冒出一堆工具行」

而同一任务的 work-event 在 **16:11:31** 就已经通过 `runs/events` 流到达前端——**隔了 9 秒才被投影成 presentation**。

## 证据

| 事实 | 位置 |
|---|---|
| presentation 走轮询推送，间隔 500ms | `PublicPresentationStreamService.java:31-33`；`WorkbenchStreamProperties.java:7`（默认 500） |
| 轮询每次调用 `poll` → `publicTimeline` → 全量重投影 | `PublicPresentationStreamService.java:40-44` |
| presentation 序号与事件序号是 10:1 压缩 | `PublicPresentationService.java:68`：`sourceAfter = afterSequence / 10 - 1` |
| 每次 poll 从 `sourceAfter` 重新加载并 `project(...)` | `PublicPresentationService.java:69-71` |
| 工具 presentation 在 `TOOL_REQUESTED` 时就该生成（不是完成才生成） | `PublicPresentationService.java:270-271` |

DevTools 实测两条流的到达时间（用户提供）：
- 第一条流 `runs/events`：`heartbeat` 至 16:11:05 → 首个 `work-event` 16:11:31 → `model-delta` 16:11:35 → 密集 `work-event` 至 16:11:54
- 第二条流 `presentations/stream?afterSequence=40`：`p:1050`…`p:1450` 全部落在 16:11:40.042–16:11:40.675

## 根因假设（需在后端侧复现验证）

`publicTimeline` 每次 poll 都从 `afterSequence / 10 - 1` 处重新加载事件并**重新投影整段**。这类「从游标重新计算全量投影」的实现，只有在**投影结果的序号跨过某个阈值**时才对外可见，于是表现为：事件早已到达，presentation 却成批延迟出现。

这与观测吻合：20 条 presentation 在 0.63 秒内批量涌出，不是增量推送的形态。

**待验证**：把 `publicTimeline` 的 `sourceAfter` 与 `project()` 的序号分配打印出来，比较「事件到达时间」与「presentation 首次可见时间」的差值。

## 建议方案（按代价从低到高）

1. **修正 `sourceAfter` 的跳段**：不要用 `afterSequence / 10 - 1` 反推事件游标，改为记录「presentation 序号 → 对应事件序号」的映射，或直接以事件游标驱动。
2. **投影增量而非全量**：只在新增事件上做投影，缓存已投影结果（当前每次 poll 都是 O(events)）。
3. **改为事件驱动推送**：在 `TOOL_REQUESTED` / `TOOL_COMPLETED` 落库时直接发布 presentation，用 `Flux` 的 sink 推送，取代定时轮询。代价最高，但能彻底消除 500ms 轮询延迟。

## 验收判据

同一任务中：

- 工具 presentation 的到达时间与对应 `work-event` 的间隔 **< 1 秒**
- `presentations/stream` 的帧在任务执行期间**持续陆续到达**，而不是结束时批量涌出
- 前端不改一行代码，工具行即随执行实时出现

## 为什么不在前端修

| 环节 | 状态 |
|---|---|
| 接收 | `usePresentationStream.ts:101-107` 收到即 `merge`，无批处理、无节流 |
| 排序 | `conversationItems.ts` 固定顺序：用户消息 → 执行记录 → 工具 → 最终回答 |
| 打字机 | `textReveal.ts` 与工具流完全独立 |

前端只能在「数据到达后」渲染。若要让工具更早出现，唯一的前端做法是绕过 presentation 直接用 `work-event` 渲染——但 presentation 承担了 `visibility` 白名单与公开字段裁剪（`PublicPresentationService`），绕过去会泄露内部数据，不可取。
