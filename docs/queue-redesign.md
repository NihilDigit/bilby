# 播放队列架构与续取规范

本文档记录 Bilby 播放队列的模型、来源标签、`X / N` 位置总计不变量、按需续取与失败锁存机制，Android（`AudioPlaybackService`）与桌面端（`DesktopPlaybackHost` + `DesktopQueue`）严格共用同一套规则。

---

## 一、 同侪队列（Peer）与归属回退（Affiliation）模型

队列的核心准则是**所见即所得**：只要用户是从一份同质列表点进某条视频，播放队列就是眼前这份列表本身（含当时的排序与筛选条件）；只有从异质聚合入口点入时，才退回视频自身的归属集合（`QueueContext.Affiliation`）。

### 1. `QueueContext` 全部分支与底层 `QueueFeed`

| 入口场景 | `QueueContext` | 底层 `QueueFeed` | 规范来源标签（`sourceLabel`，使用双空格 `MetaSeparator`） |
|---|---|---|---|
| 合集 / 系列目录页 | `Collection(mid, id, isSeason, name, page)` | `PagedFeed` | `合集  <标题>` / `系列  <标题>`（附 `QueueSource`，标题可点进目录页） |
| 空间投稿栏（含排序与空间内搜索） | `UpArchive(mid, order, keyword, page, upName)` | `PagedFeed` | `<UP 名> 的投稿` / `<UP 名> 的投稿  最多播放` / `<UP 名> 的投稿  搜索「<词>」` |
| 空间动态栏（UP 自发视频） | `UpDynamics(mid, pageOffset, upName)` | `DynamicVideoFeed` | `<UP 名> 的动态` |
| 夜间模式（「全部」或指定白名单 UP） | `NightMode(ups, selectedMid, anchorPage)` | `NightMergeFeed`（全部）/ `PagedFeed`（单人） | `夜间模式  全部` / `夜间模式  <UP 名>` |
| 收藏夹（含排序与夹内搜索） | `FavFolder(mediaId, title, page, order, keyword)` | `PagedFeed`（过滤失效与非 UGC 条目） | `收藏夹  <标题>` |
| 稍后再看（支持正倒序） | `ToView(asc)` | `StaticFeed` | `稍后再看` |
| 离线缓存列表（及离线自动回退） | `Offline` | `StaticFeed`（按 `bvid` 去重，不联网） | `离线缓存` |
| 用户手工拼装 / 编辑脱钩队列 | `Custom(items)` | `StaticFeed` | `自定义队列` |
| 订阅流、搜索、助理引用、历史、站内链接、找相关 | `Affiliation` | 按归属优先级解析（见下） | 命中的合集 / 系列 / 动态 / `<UP 名> 的投稿` |

### 2. `QueueContext.Custom` 的严格边界

`QueueContext.Custom` **仅限两处产生**：
1. 用户通过菜单动作（「下一条播放」「加入播放队列」）在 `CustomQueueDraftStore` 中手工攒出的草稿队列；
2. 播放过程中用户在完整队列面板对现有队列做了增删、移动或「清空其余」，由播放引擎调用 `detachToCustom` 与原来源脱钩（此时 `QueueState.detached == true`，可通过 `PlaybackCommand.ResetQueue` 重新按原 `QueueContext` 还原）。

任何同侪列表（尤其是夜间模式「全部」视图）一律走各自的 `QueueFeed` 分页，**严禁截取前 N 条塞进 `QueueContext.Custom` 冒充静态自定义队列**。

### 3. `NightMergeFeed`（夜间模式多 UP 归并队列）

夜间模式选「全部」时，`NightMergeFeed`（`data/QueueFeed.kt`）为每位白名单 UP 维护独立页游标，按 `publishedAtEpochSeconds` 全局倒序归并：
- `open(bvid)` 从第 1 页起并发拉取归并，定位到目标 `bvid` 后向后补足至少 `QUEUE_WINDOW`（10）条；`offsetOfFirst` 恒为 `0`，`total` 为各白名单 UP 投稿总数之和（全部读尽后收敛为实际加载条数）。
- 播放或面板触底时通过 `loadAfter()` 继续按批（30 条）归并更早的投稿，无人工 100 条截断。

### 4. `Affiliation` 归属解析链

异质列表自身不能当队列，由 `QueueSourceRepository.affiliation(bvid)` 依次探测：
1. **所属合集（`ugc_season`）**：视频详情直接给出完整分集，构造 `StaticFeed` 并附可点击 `QueueSource`。
2. **所属系列（`series`）**：在请求预算内（前 4 个系列、最多 8 次分页请求）扫描该 UP 的系列目录；直播回放等不进常规投稿列表的稿件由此命中。
3. **动态视频（`detail.isStory`）**：扫描该 UP 最近数页动态中的视频条目（`DynamicVideoFeed`）。
4. **UP 投稿邻居（`ArchiveCursorFeed`）**：调用 App 端 `aid` 双向游标接口一次定位当前稿件前后的投稿（避免大投稿量 UP 在 Web 页码接口上被深页夹断），同时读取上游总投稿数 `count`。
5. **单条保底**：以上均未命中（或离线且本地无缓存副本）时诚实保留单条队列，绝不拼凑「最新 24 条加当前条」。

---

## 二、 导航帧栈与幂等会话命令

`QueueContext` 随视频页 `Video` `NavKey` 序列化保存。每个入栈视频页在压栈时分配唯一 `frameId`（`withNewFrame`），播放服务按 `frameId` 维护帧栈（活帧驱动当前播放器队列，挂起帧保存快照并在导航栈变化时由 `retainFrames` 清理出栈帧）：

| 命令 | 职责 |
|---|---|
| `ActivateFrame(frameId, context, bvid)` | 页面进入前台时幂等发送：新帧按上下文建队起播；挂起帧恢复离开时的条目与进度快照；已是活帧则仅核对云端进度 |
| `RetryQueue` | 队列补全失败（`incomplete == true`）时手动重试建队 |
| `ExtendQueue(before)` | 队列面板滚到边界或用户点击边界重试行时，向指定方向续取一页并清除该方向的错误锁存 |
| `ResetQueue` | 将手工编辑脱钩（`detached == true`）的自定义队列重置回初始 `QueueContext` 来源 |

---

## 三、 `X / N` 位置与总数不变量（`QueuePagination`）

队列往往只加载整份来源的一段窗口（`items.size` 只是已加载窗口长度，不是来源总长）。Android 与桌面端共用 `player/PlaybackState.kt` 中的 `QueuePagination` 维护精确的 `sourcePosition`（1 起）与 `sourceTotal`：

1. **各 `QueueFeed` 的起点偏移与总数**：
   - `StaticFeed`：`offsetOfFirst = 0`，`total = items.size`。
   - `PagedFeed`：打开时若落第 1 页则 `offsetOfFirst = 0`，深页以 `(firstPage - 1) * pageSize` 起算；向前 `loadBefore()` 时按**实际新增去重后的条数**递减偏移（抵消收藏夹过滤失效稿件带来的页长缩水），一旦抵达第 1 页立即归零。
   - `ArchiveCursorFeed`：已触达最新投稿端（`!hasNewer`）时 `offsetOfFirst = 0`（覆盖绝大多数新近投稿点击）；已触达最早投稿端（`!hasOlder`）且已知 `total` 时 `offsetOfFirst = total - loadedSize`；仅当停在超长投稿列表中间段且两端均未触底时暂置 `offsetOfFirst = null`（不谎报位置，只展示窗口与总数）。
   - `DynamicVideoFeed` / `NightMergeFeed`：从顶部扫入命中时 `offsetOfFirst = 0`。
2. **闭窗自洽收敛（`reconcileClosedWindow`）**：只要 `!hasBefore && !hasAfter && offsetOfFirst == 0`，`sourceTotal` 自动收敛为当前真实 `playlistSize`，保证过滤掉失效视频后末尾永远对齐 `N / N`。
3. **四处队列界面统一读数（`QueuePositionText(queue)`，全部套用 `.tabular`）**：
   - `sourcePosition` 与 `sourceTotal` 均已知：显示精确 **`X / N`**。
   - 仅知 `sourceTotal` 而处于大列表中间窗口：显示 **`i/k  共 N`**（窗口内位置 / 已加载条数 + 来源总数）。
   - 其余情况退回已加载窗口的 `displayPosition / displayTotal`。
   - 播放页内联头栏（`QueueHeader`）与「查看全部」底封瓦片（`ShowAllRow`）、完整队列面板（`FullQueueSheet`）、听视频唱盘区与底部抽屉（`ListenScreen`）、全屏侧滑选集面板（`EpisodePanel` 的 `PlayerSidePanelHeader`）全部共用此读数。

---

## 四、 边界按需续取与 `-412` 错误锁存

1. **禁止建队后静默预拼接**：`QueueSourceRepository.open()` 已经在目标视频前后备妥 `QUEUE_WINDOW`（各 10 条）初始窗口；建队落定当下**不再**立即后台连发 `extendNearEdges()`，避免进页半秒后列表条数与序号突然跳变。
2. **播放期仅在临近边界时按方向续取**：自动续取仅在连播推进到距当前播放方向末端不足 2 条（`Forward` 下 `index >= size - 2 && hasAfter && !errorAfter`；`Reverse` 下 `index <= 1 && hasBefore && !errorBefore`）时触发；随机模式（`Shuffle`）与脱钩自定义队列不自动续取。
3. **续取失败锁存（`errorBefore` / `errorAfter`）防死循环**：
   - 任一方向 `loadBefore()` / `loadAfter()` 返回 `null`（网络异常或触发 `-412` 风控）时，`QueuePagination.failExtend(before)` 立即置上 `errorBefore` 或 `errorAfter`。
   - `EpisodeList` 的触顶/触底自动加载观察器与播放边界续取同时受 `!errorBefore` / `!errorAfter` 门控，失败后立即停手，绝不因 `loading` 复位而陷入无限重试风暴。
   - 只有用户显式点击边界错误行发起 `ExtendQueue(before)` 时，才清除对应方向的错误锁存并重试一次。
4. **透明化边界状态行（`EpisodeList` 的 `QueueEdgeRow`）**：
   - 加载中：展示 18dp M3E `InlineProgress` 与「正在加载前面/后续条目…」。
   - 失败锁存态：展示可点击的「加载失败  重试」行。
   - 尚有未读余量态：展示带方向箭头的计数提示行（如「前面还有 12 条，点击加载」或「后面还有更多，点击加载」），支持手动点击提前展开。

---

## 五、 分 P 优先与顺序/完播控制

- **分 P 与队列合一渲染**：队列与当前视频的分 P 在 `VideoScreen` 经 `buildEpisodeRows` 拼成同一份 `EpisodeList`（仅对身份匹配的当前视频展开分 P）。详情页额外保留横向分 P chip 带以便整屏快速扫读。
- **切集优先走分 P**：`QueuePlayer`（Android `ForwardingSimpleBasePlayer`）与 `DesktopPlaybackHost` 统一覆写 `seekToNext` / `seekToPrevious`：有下一 P 先切下一 P，最后一 P 才进队列下一条；回退在播放超过阈值时回本 P 开头，否则切上一 P / 上一条。队列面板直接点选某条视频时走媒体项直跳。
- **顺序与完播行为解耦**：`QueueOrder`（正序 `Forward` / 倒序 `Reverse` / 随机 `Shuffle`）只改变播放游走顺序，列表始终保持自然顺序且高亮跟随滚动；`PlayEndMode`（播完暂停 `Pause` / 连播 `Continue` / 洗脑循环 `RepeatOne`）与睡眠定时器「播完当前」均以单 P 为粒度精确结算。
