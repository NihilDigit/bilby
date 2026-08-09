# Bilby 性能优化计划

本文档定义 Bilby Android 客户端的性能优化范围、实施顺序、验收指标和回归策略。计划覆盖播放启动、连续播放生命周期、弹幕处理、搜索与分页、Agent 上下文、图片加载和构建配置。

这轮优化的核心目标是缩短用户可感知的等待时间，消除连续使用过程中的内存增长，并把批量解析、排序和排版从主线程移出。功能行为、播放能力和数据正确性必须保持不变。

## 1. 目标与边界

### 1.1 优化目标

1. 点击视频后尽快进入媒体准备流程，播放不再等待队列补全。
2. 连续播放时只保留当前视频所需的页面状态和 Flow 订阅。
3. 弹幕分段到达、全屏切换和样式变化不产生明显主线程尖峰。
4. 搜索、刷新、筛选和分页请求支持并行、取消和过期结果隔离。
5. 长列表翻页和长 Agent 会话的 CPU、内存与请求体增长受到控制。
6. 建立可重复执行的性能基线，避免后续改动重新引入问题。

### 1.2 不在本轮范围内

- 不改变播放器内核，不替换 Media3。
- 不改变 Bilibili API、鉴权、WBI 签名和设备指纹协议。
- 不以降低视频、音频或弹幕质量作为主要优化手段。
- 不在缺少测量数据时重写整个 Compose UI 或数据层。
- 不把 Release 包体积作为首要目标；无效依赖清理主要服务于构建速度和维护成本。

## 2. 优先级与交付顺序

| 阶段 | 优先级 | 工作项 | 预期收益 | 主要风险 |
| --- | --- | --- | --- | --- |
| 0 | 基础 | 性能基线与可观测性 | 后续优化可量化、可回归 | 测试环境不稳定导致数据噪声 |
| 1 | P0 | 播放启动与队列异步补全 | 显著缩短起播等待 | 队列替换时当前项错位 |
| 2 | P0 | 视频页 ViewModel 生命周期 | 消除连续播放内存增长 | 切集时旧任务未完全取消 |
| 3 | P1 | 弹幕解析、存储和排版 | 降低主线程卡顿和 GC | 跨线程文本测量、顺序一致性 |
| 4 | P1 | 搜索并行化与请求取消 | 缩短搜索等待，减少无效请求 | 旧响应覆盖新状态 |
| 5 | P2 | 分页集合与 Agent 上下文 | 控制长时间使用成本 | 列表状态兼容、上下文摘要丢信息 |
| 6 | P3 | 图片请求和构建依赖 | 降低重组分配和构建负担 | 误删运行时需要的传递依赖 |

阶段 1 和阶段 2 完成前，不建议同时大改播放器状态模型。两者都涉及当前视频身份、队列和页面生命周期，拆开提交更容易定位回归。

## 3. 阶段 0：建立性能基线

性能代码修改前先保存基线。网络性能受地区、账号和 CDN 状态影响，测试分为可控环境与真实网络两类：可控环境用于回归，真实网络用于确认用户体验。

### 3.1 新增测量点

播放链路记录以下单调时钟时间点：

- 用户触发打开视频。
- `AudioPlaybackService` 收到打开命令。
- 临时队列安装完成。
- playurl 请求开始与结束。
- `Player.prepare()` 调用。
- 播放器进入 `STATE_READY`。
- 首帧渲染。
- 完整播放队列补全。

弹幕链路记录：

- 网络响应字节数和弹幕条数。
- protobuf 解析耗时。
- 弹幕池合并耗时与合并前后条数。
- Timeline 编译、排序和排版耗时。
- 每帧可见弹幕数量、帧时间和 jank 比例。

生命周期和列表链路记录：

- 当前 `VideoViewModel`、`CommentViewModel` 实例数。
- 连续切换 1、10、30 个视频后的 Java heap 和 native heap。
- 每次分页追加的旧列表长度、新增条数、去重和复制耗时。
- Agent 每轮消息数、估算 token 数、序列化字节数和工具结果占比。

调试日志必须受 BuildConfig 或专用开关控制，Release 默认不输出高频日志。

### 3.2 自动化基准

新增 Macrobenchmark 或等价的 instrumentation benchmark，至少覆盖：

1. 冷启动进入首页。
2. 从视频卡片点击到播放器首帧。
3. 连续自动播放 30 个短视频。
4. 加载高密度弹幕分段并播放 60 秒。
5. Feed 连续翻页 30 页。
6. 搜索后立即切换排序或查询词。

测试设备固定刷新率、电源模式和动画设置。每个场景至少预热 3 次、正式运行 10 次，报告 P50、P90、P95，而不是只记录平均值。

### 3.3 初始验收指标

绝对时间受网络影响，首轮以相对基线和结构性约束为主：

| 指标 | 初始目标 |
| --- | --- |
| 打开命令到 `Player.prepare()` | 不等待完整队列构建；受控环境 P50 至少降低 40% |
| 起播前空间投稿分页请求数 | 0；队列补全请求只能发生在媒体准备开始后 |
| 连续切换 30 个视频后的活动视频 VM | 1 个 `VideoViewModel`，最多 1 个当前 `CommentViewModel` |
| 旧视频长生命周期 Flow 收集器 | 0 |
| 弹幕响应后的主线程连续工作 | 单次任务不超过 4 ms；重计算需拆分或移出主线程 |
| 播放 60 秒的 jank 比例 | 相比基线下降至少 30%，并以低于 5% 为后续目标 |
| 快速搜索视频结果发布时间 | 视频请求完成后的下一个 UI 帧内提交状态 |
| 过期响应写入新状态 | 0 次 |
| Feed 30 页追加总分配量 | 相比基线下降至少 50% |
| Agent 请求上下文 | 不超过配置的 token 预算，旧工具原始响应不无限保留 |

## 4. 阶段 1：播放启动与队列异步补全

### 4.1 现状

当前页面先由 `VideoViewModel` 请求视频详情，随后 `AudioPlaybackService.openVideo()` 等待 `QueueSourceRepository.forVideo()` 完成，再调用 `playCurrent()`。

普通 UP 主视频的队列构建还会重新请求视频详情，并通过空间投稿分页定位当前视频。定位采用串行分页探测，完成后可能继续请求相邻窗口。结果是播放准备被队列元数据阻塞。

相关代码：

- `app/src/main/kotlin/dev/bilby/ui/video/VideoViewModel.kt`
- `app/src/main/kotlin/dev/bilby/player/AudioPlaybackService.kt`
- `app/src/main/kotlin/dev/bilby/data/QueueSourceRepository.kt`
- `app/src/main/kotlin/dev/bilby/data/VideoRepository.kt`

### 4.2 目标设计

打开视频后立即建立只包含当前视频的临时队列，并开始播放；完整队列在独立 Job 中补全。

```text
ACTION_OPEN_VIDEO
  ├─ 安装 current-only 临时队列
  ├─ 请求 playurl 并 prepare
  └─ 异步构建完整队列
       └─ 按 bvid 定位当前项并原子替换队列
```

队列替换必须以 `bvid` 或稳定的 episode identity 定位当前项，不能依赖旧索引。用户在补全期间手动切换视频时，旧的补全结果必须失效。

### 4.3 实施步骤

1. 在 `openVideo()` 中生成包含当前视频和已知分 P 信息的 fallback item。
2. 立即更新播放状态并调用 `playCurrent()`。
3. 新增独立的 `queueEnrichmentJob`，每次打开新视频前取消旧 Job。
4. 队列结果返回后校验 request generation 和当前 `bvid`。
5. 原子替换队列，重新计算当前索引，保持当前媒体项和播放位置不变。
6. 页面已经持有 `VideoDetail` 时直接传递必要元数据，避免服务重复请求。
7. 给 `VideoRepository` 增加按 `bvid` 的短期内存缓存或 single-flight，合并并发详情请求。
8. 给空间投稿定位增加有限容量、带过期时间的 `bvid → page` 缓存。

### 4.4 并发与失败处理

- 队列补全失败不影响当前视频播放，只保留临时队列并暴露可重试状态。
- 新视频打开后，旧视频的 playurl 和队列 Job 都要取消或通过 generation 隔离。
- 队列替换时，如果当前视频不在返回结果中，应保留当前项并合并队列，而不是跳到第一项。
- Service 销毁时取消补全 Job，避免持有 Repository 和回调。

### 4.5 测试与验收

- 单元测试：队列补全前已经调用媒体准备。
- 单元测试：补全结果能保持当前 bvid、分 P 和播放位置。
- 单元测试：快速打开 A、B 时，A 的迟到结果不能覆盖 B。
- 集成测试：空间投稿接口失败时当前视频仍可播放。
- 基准测试：起播前不再出现空间投稿分页请求。
- 真机验证：大投稿量 UP 主视频、合集、番剧、多分 P、单视频均可正确连续播放。

## 5. 阶段 2：收敛视频页 ViewModel 生命周期

### 5.1 现状

视频导航条目保持不变时，连续播放通过变化的 `video-$bvid` 和 `comment-$aid` key 创建新 ViewModel。旧 key 仍留在同一个 `ViewModelStore` 中，直到整个视频页出栈。

每个 `VideoViewModel` 会订阅播放服务、弹幕 CID、字幕和偏好设置。连续播放会同时增加对象保留和无效 Flow 扇出。

相关代码：

- `app/src/main/kotlin/dev/bilby/ui/MainActivity.kt`
- `app/src/main/kotlin/dev/bilby/ui/video/VideoViewModel.kt`
- `app/src/main/kotlin/dev/bilby/ui/comment/CommentViewModel.kt`

### 5.2 首选方案

每个视频导航条目只创建一个 `VideoViewModel`。新增 `switchTo(target)`：

1. 增加 generation。
2. 取消所有与旧视频关联的 Job。
3. 清空详情、字幕、弹幕池、错误状态和瞬时 UI 状态。
4. 保留真正属于会话级的配置 Flow。
5. 加载新视频，并拒绝旧 generation 的迟到结果。

评论状态可以采用同样的 `switchTo(aid)`，或者只在评论区域实际展开时创建，并在 aid 变化后重置。

不建议继续依赖不断变化的 `viewModel(key = ...)`。Compose 的 key 决定实例选择，不负责删除旧 key 对应的实例。

### 5.3 Job 所有权

把 Job 按生命周期分组：

- ViewModel 级：偏好设置、全局播放服务连接。
- 当前视频级：详情、分 P、字幕、弹幕分段、评论。
- 单次操作级：刷新、重试、加载下一页。

当前视频变化时，只取消后两组。可使用父 `SupervisorJob` 或明确保存 Job 引用，避免遗漏。

### 5.4 测试与验收

- 连续切换 30 个视频后只有一个视频 VM。
- 旧视频的详情、弹幕和字幕响应不能更新当前页面。
- 切换期间不会短暂显示上一视频的标题、评论或字幕。
- 自动下一集、手动选集、通知栏切换和返回页面行为一致。
- LeakCanary 或 heap dump 中不存在由旧 ViewModel 持有的 Activity、播放器状态或大列表。

## 6. 阶段 3：弹幕解析、存储与排版

### 6.1 拆分线程边界

弹幕处理分成三类工作：

| 工作 | 建议线程 |
| --- | --- |
| 网络读取 | Ktor engine |
| protobuf 解析、过滤、排序、纯数学排布 | `Dispatchers.Default` |
| Compose `TextMeasurer`、状态发布、Canvas 绘制 | Main |

Repository 应返回已经解析的不可变结果。ViewModel 只在主线程提交最终状态，不在主线程遍历原始响应。

### 6.2 减少解析复制

`DanmakuProtoReader.readLengthDelimited()` 当前会为嵌套消息复制 `ByteArray`。改造方向：

- Reader 持有原始数组、当前位置和结束位置。
- 子消息创建共享底层数组的 bounded reader。
- 只在生成最终字符串时解码对应区间。
- 对异常长度、越界和恶意响应继续执行严格校验。

改造前后使用同一组 protobuf fixture 做字节级结果对比，确保字段跳过和未知 wire type 行为不变。

### 6.3 改造弹幕池

`pool + segment` 会复制全部旧元素。优先选择以下方案之一：

1. 按 segment 保存不可变 chunk，Timeline 编译时按需合并。
2. 使用持久化集合减少追加复制。
3. 在单一后台所有者中使用可变 buffer，发布时只暴露版本号和不可变快照。

如果弹幕天然按 segment 时间有序，不应在每次到达时重新排序全池。记录已编译区间，只对新增 chunk 排序并执行有序合并。

### 6.4 降低排版分配

- 缓存文本宽度，key 至少包括文本、字号、字重和字体。
- 候选轨道选择改用下标循环和复用 buffer，避免 `filter`、中间 `List` 和临时 `FloatArray`。
- 相同样式下不重复测量同一条弹幕。
- 宽度仍为 0 时不编译 Timeline，等待有效布局尺寸。
- 全屏或尺寸变化后允许重建，但重建纯计算放到后台；仅文本测量留在主线程并分批执行。

### 6.5 正确性约束

- 相同输入、尺寸和随机种子应产生稳定轨道结果。
- 顶部、底部和滚动弹幕的优先级保持不变。
- seek、倍速、暂停恢复和视频分 P 切换不丢弹幕、不重复显示。
- 后台生成的 Timeline 必须带 generation；尺寸或样式已变化时丢弃旧结果。

### 6.6 测试与验收

- 为 Reader 添加空消息、未知字段、截断消息和大消息测试。
- 以 1 千、1 万、5 万条弹幕建立 microbenchmark。
- 使用 Perfetto 确认解析和排序不再占用主线程长任务。
- 60 Hz 和 120 Hz 设备分别验证高密度场景。
- 比较优化前后弹幕显示数量、时序和轨道碰撞结果。

## 7. 阶段 4：搜索并行化与请求治理

### 7.1 快速搜索

视频搜索和用户搜索相互独立，应并行启动。视频结果返回后立即发布，用户结果稍后合并；用户请求失败不能让视频搜索进入失败态。

建议状态模型区分：

- `videoLoading` / `videoError`
- `userLoading` / `userError`
- 当前 query、order 和 generation

这样可以增量显示结果，不必为了两个子请求维护一个粗粒度 loading 状态。

### 7.2 统一请求规则

所有由 query、排序、筛选、用户身份或页面目标驱动的请求遵循同一规则：

1. 条件变化时增加 generation。
2. 取消旧 Job。
3. 响应提交前再次验证 generation。
4. refresh 与 append 不能并发修改同一分页游标。
5. loading 状态在 `finally` 中按当前 generation 释放。

优先检查：

- `SearchChatViewModel`
- `FeedViewModel`
- `SpaceViewModel` 或 `SpaceScreen` 内相关状态持有者
- `HistoryViewModel`
- `FollowingsViewModel`
- `CommentViewModel`

### 7.3 测试与验收

- 视频接口快、用户接口慢时，视频结果先显示。
- 连续输入 A、AB、ABC，只允许 ABC 更新最终状态。
- 加载下一页期间刷新，旧 append 响应不能写回。
- 网络取消不显示为用户错误。
- 同一资源任意时刻最多存在一个 refresh 和一个受控 append；默认情况下二者互斥。

## 8. 阶段 5：分页集合与 Agent 上下文

### 8.1 分页集合

公共 `appendDistinctBy` 每次追加都会扫描和复制全部旧列表。短列表可保留简单实现，可能无限增长的列表改用带持久去重索引的分页状态：

```kotlin
data class PagedState<T, K>(
    val pages: PersistentList<PersistentList<T>>,
    val seenKeys: PersistentSet<K>,
    val nextCursor: String?,
)
```

若引入 Paging 3，应先在 Feed 或空间投稿中完成一条端到端路径，验证刷新、错误重试和滚动位置恢复，再迁移其他页面。不要一次性改完所有列表。

验收重点：

- 新页去重只处理新数据，不重新构造全部旧 key。
- append 不复制全部历史元素。
- Compose item key 稳定。
- 刷新明确清空 page、seen key 和 cursor。
- 30 页场景的总分配量和 GC 次数显著下降。

### 8.2 Agent 上下文预算

Agent 历史按 token 预算管理，不能仅按消息数裁剪。推荐把上下文分成：

- 固定系统指令和工具 schema。
- 最近若干轮原始对话。
- 更早对话的结构化摘要。
- 当前任务必需的 bvid、aid、用户筛选条件等实体。
- 当前工具调用链的原始结果。

一轮结束后，将不再需要逐字保留的旧工具结果压缩为摘要。摘要必须保留：

- 用户明确要求和否定条件。
- 已选择或排除的视频。
- 后续工具调用需要的稳定 ID。
- 仍未解决的问题。

需要为摘要失败保留安全策略：摘要不可用时，优先裁剪体积最大的旧工具响应，并向模型明确标记历史已截断。

验收重点：

- 100 轮模拟会话仍不超过配置预算。
- 裁剪后能够正确引用最近选择的视频和筛选条件。
- 每个工具循环不会重复携带无关的大型历史 payload。
- 上下文压缩耗时和模型调用成本可观测。

## 9. 阶段 6：图片与构建优化

### 9.1 图片请求

在列表项中使用 `remember(url, context, headers)` 缓存 `ImageRequest`，避免每次重组创建请求头和 Builder 对象。全局 crossfade 改为按场景启用：

- 详情页大图、首屏 Hero 图：保留。
- Feed、评论头像和密集网格：默认关闭，除非测量表明没有额外 jank。

同时检查 URL 规格，列表缩略图应请求接近显示尺寸的 CDN 图片，避免下载后再大幅缩放。修改 URL 参数前必须验证 Bilibili CDN 的实际响应和缓存 key。

### 9.2 依赖与构建

候选清理项：

- 当前没有业务消费者的 Room database、Room runtime 和 KSP 配置。
- 没有安装 Logging plugin 的 `ktor-client-logging`。
- 未使用 `OkHttpDataSource` 时的 Media3 datasource-okhttp。
- 没有解析 MPD、只使用自建 `MediaSource` 时的 Media3 DASH 模块。

删除依赖前执行：

1. `dependencies` 和 `dependencyInsight` 确认没有隐藏的传递用途。
2. 编译 Debug、Release 和 unit test source set。
3. 运行 R8 Release 构建。
4. 安装 Release APK，验证视频、音频、字幕和后台播放。
5. 对比 clean build、incremental build 和 APK Analyzer 结果。

## 10. PR 拆分建议

每个 PR 只解决一个可以独立测量的问题：

1. `perf: add playback and danmaku benchmarks`
2. `perf: start playback before queue enrichment`
3. `perf: reuse video details and cache queue location`
4. `fix: scope video state to the active episode`
5. `perf: move danmaku parsing off main thread`
6. `perf: reduce danmaku timeline allocations`
7. `perf: cancel stale search and paging requests`
8. `perf: replace full-list pagination copies`
9. `perf: bound agent conversation context`
10. `build: remove verified unused dependencies`

每个 PR 需附带优化前后数据、测试设备、构建类型、运行次数和原始 trace 链接。只提供主观感受不足以证明性能改善。

## 11. 回归风险与回滚条件

| 改动 | 主要风险 | 必须回滚或阻断发布的条件 |
| --- | --- | --- |
| 异步队列补全 | 当前项错位、自动下一集错误 | 播放跳集、进度丢失或通知栏状态不一致 |
| VM 单实例切换 | 旧请求污染新页面 | 出现旧标题、旧评论、旧字幕或旧弹幕 |
| 后台弹幕编译 | 时序变化、结果迟到 | seek 后重复/漏弹幕，或轨道碰撞明显增加 |
| 请求取消 | loading 无法结束 | 页面永久 loading 或取消被显示为错误 |
| 分页数据结构 | 去重和滚动位置变化 | 重复卡片、缺页、刷新后位置异常 |
| Agent 历史压缩 | 丢失用户约束 | 推荐违反最近约束或引用错误视频 |
| 依赖清理 | Release 专属运行时缺失 | Release 播放、字幕或后台服务异常 |

所有高风险改动保留独立 feature flag 或能够单 PR revert 的提交边界。播放和弹幕优化先进入内部构建，至少覆盖一轮真实网络与低端设备测试后再默认启用。

## 12. 完成标准

本计划完成需同时满足：

- 阶段 0 的基准可以在本地或 CI 重复执行。
- P0 项目全部完成，并达到结构性验收条件。
- P1 项目有 Perfetto 或 Macrobenchmark 数据证明主线程和等待时间下降。
- 连续播放、seek、分 P、合集、番剧、后台播放和通知栏控制通过回归。
- 长列表和长 Agent 会话不会无限增加单次操作成本。
- Release 构建、单元测试、lint 和关键 instrumentation test 通过。
- 性能日志在 Release 默认关闭，不记录 cookie、token、播放 URL 或用户隐私数据。
- 每项优化的限制、测量方法和剩余问题同步到对应代码或工程文档。

完成 P0 和 P1 后应重新采集一次全量基线，再决定是否继续投入更细的 Compose 重组、网络连接池或播放器内部优化。后续工作以 trace 和 benchmark 数据为依据。
