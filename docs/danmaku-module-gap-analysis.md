# 弹幕模块现状与演进建议

> 调研日期：2026-08-09；对照版本：Bilby 当前工作区、pakku.js 2026.5.1。

## 结论

Bilby 已经有一个结构清楚的弹幕渲染原型：数据源映射、时间轴编译、播放器时钟和 Compose Canvas 渲染彼此分离；滚动弹幕采用确定性轨迹，同轨碰撞判定也比常见的“固定速度 + first-fit”更严谨。这部分适合继续演进，不需要换引擎。

当前短板主要不在“能不能画弹幕”，而在配置没有成为完整的公共能力，预处理层几乎为空，以及高密度、高刷新率场景尚未经过测量和专门优化：

- 显示区域固定为画面高度的 50%，而且该比例只影响轨道数，并没有定义真正的布局和裁剪区域。
- 用户侧只有开关和透明度。核心虽然已有 `30 / 60 / UNLIMITED` 三档和三种溢出策略，Bilby 接入时仍固定使用 60fps 与 `DISCARD`。
- 帧率上限算法在 90Hz、144Hz、165Hz 等面板上不能稳定得到目标平均帧率；当前名为 `UNLIMITED` 的“跟随屏幕”档也只是每个 Compose frame 更新一次，没有主动向系统请求高刷新率。
- 轨道内碰撞处理较好，但滚动、顶部、底部三类轨道互不占用，跨类型仍会互相遮挡；当前 `OVERLAP` 也不是真正的无限模式，仍先支付碰撞扫描成本，再用不完整的单尾状态强制叠放。
- 运行时仍把文字测量和描边/填充命令放在逐帧热路径中。目标架构应严格分离 scheduler、immutable timeline、emitter 和 renderer，并以 prepared layout、`GraphicsLayer` 和可选 sprite atlas 形成分级后端。
- 没有规范化、过滤与去重管线。范围已收敛：规范化后完全相同的弹幕做“同屏单实例 + 飞行中递增计数”的去重显示，pakku 的相似度匹配、代表弹幕合并与内容密度整形明确不采用（理由见 3.5）。
- `:danmaku` 目前仍是 Android library，不是可发布的 KMP library；会话管理、重编排和性能诊断也还留在 Bilby 私有接入代码中。

建议先完成“可配置、可测量、可解释”的渲染核心，再做合并与高级弹幕。优先顺序应是：显示区域与密度语义 → scheduler/emitter/renderer 分层 → 固定 duration 与轨道代价匹配 → 帧率正确性和文字后端基准 → 预处理管线 → 高级弹幕 → KMP 发布。KMP 的模块边界需要现在确定，但不必在 API 仍快速变化时急着发布稳定版。

## 1. 调研范围与 pakku 的正确参照方式

工作区内的 pakku.js 源码版本为 2026.5.1，与当前 Chrome Web Store 版本一致。pakku 的核心身份是“B 站弹幕响应预处理器”：它拦截 XML/protobuf 弹幕，做合并、过滤和属性调整，再把结果交给 B 站播放器。轨道调度、逐帧位置和实际绘制仍由 B 站播放器负责。

因此可以从 pakku 借鉴：

- 处理管线如何分层；
- 文本规范化和跨分片上下文；
- 如何提供黑白名单和可扩展处理钩子。

调研过但决定不采用（理由见 3.5）：

- 相似度匹配（伪编辑距离、拼音、2-Gram）；
- 代表弹幕 + 总量计数的合并显示——Bilby 改用“同文本同屏单实例 + 飞行中递增计数”的去重模型；
- `dispval` 内容密度整形——pakku 用 5 秒时间窗口内按文本长度和字号估算的 `dispval` 缩小或概率删除弹幕；Bilby 把高能片段的弹幕过载交给渲染侧的显示区域与同屏密度档位解决，不在内容侧再删一层。

不应直接从 pakku 推导：

- 轨道碰撞算法；
- 同屏轨道容量；
- 30/60/屏幕刷新率调度；
- Compose/Skia 文本绘制性能。

## 2. 当前实现盘点

### 已有基础

| 层次 | 当前能力 | 评价 |
|---|---|---|
| 数据获取 | 按 6 分钟分片读取 `seg.so`，手写最小 protobuf 解析 | 依赖小，足够支撑普通弹幕；协议字段覆盖偏窄 |
| 中立模型 | `Danmaku` 只含 id、时间、三种位置、颜色、文本、字号和 `isSelf` | 数据源隔离正确；不足以承载过滤、合并和高级弹幕 |
| 时间轴 | 批量编译与增量 append；确定性选轨和速度；seek 时直接按时间求值 | 方向正确，容易测试和跨平台 |
| 碰撞 | 滚动轨道同时检查“追及”和“生成点重叠”；固定弹幕做区间调度 | 同类型、同轨无碰撞已有测试保障 |
| 溢出 | `DISCARD / DEFER / OVERLAP` | 核心有能力，Bilby 没有向用户暴露，也没有统计结果 |
| 帧循环 | 30、60、每帧三档；暂停或无可见弹幕时挂起 | 节能意识很好；档位没有接入设置，节拍算法需修正 |
| 渲染 | Compose Canvas；文本布局复用 Compose LRU；描边与填充共用 layout | 普通密度可用；高密度时仍有缓存抖动和双倍绘制成本 |
| 接入 | 时钟读 `surfacePlayer ?: controller`：有 surface 时直接读同进程 ExoPlayer 的连续进度，避开 MediaController 变速抖动；无 surface 时仍回退到 MediaController | 方向正确；`BilbyPlayer.kt` 时钟旁有一条方向说反的过期注释（声称包的是 MediaController），需要清理 |

### 用户实际能配置的能力

Bilby 目前只持久化：

- 弹幕开关；
- 整体透明度。

字号、速度、显示区域、轨道密度、模式过滤、帧率、描边、合并和文本转换都仍是常量或不存在。核心层中已经存在的帧率和溢出选项，也没有进入 `DanmakuPrefs` 和 `buildDanmakuSession`。

## 3. 六项主要需求的差距与建议

### 3.1 自定义弹幕显示区域

当前 `DANMAKU_SHOW_AREA_RATIO = 0.5f` 只参与“画布高度 × 0.5 ÷ 行高”的轨道数计算。Canvas 仍覆盖几乎整个视频区域：

- 滚动和顶部弹幕从 Canvas 顶部开始排；
- 底部弹幕仍锚定 Canvas 底部；
- 裁剪范围仍是整个 Canvas，而不是上半屏。

这不是完整的“显示区域”抽象。特别是底部固定弹幕不会随 50% 区域收进上半屏；未来把比例改成 25%、75% 时，模式之间的空间语义会更混乱。

建议引入归一化视口 `DanmakuViewport`，至少包含：

- `topFraction`、`bottomFraction`，或一个 `[0, 1]` 的纵向区间；
- 安全边距；
- 可选的左右边距；
- 将来可扩展的避让区域，例如字幕和播放器控件。

所有模式都在同一个 viewport 内布局并 `clipRect`：顶部弹幕锚定 viewport 顶部，底部弹幕锚定 viewport 底部，滚动轨道铺在 viewport 内。第一版 UI 可以只给 25% / 50% / 75% / 100% 四档，公共 API 不应写死为四个枚举值。

### 3.2 同屏密度与“无限”档

当前密度由“可用高度能放多少条安全轨道”隐式决定。放不下时默认直接丢弃，所以用户看不到丢弃率，也无法区分“原视频没弹幕”和“引擎为了避碰丢了弹幕”。丢弃在 API 层就已经不可见：`append` 以返回 null 表示丢弃，但两个调用点都用 `forEach` 忽略了返回值，统计不是“没暴露”而是“没接住”。

建议把三个概念分开：

1. **显示区域**：有多少纵向空间。
2. **安全排布**：同轨是否允许重叠、最小水平间距是多少。
3. **密度预算**：超出安全容量后允许显示多少，以及按什么规则舍弃。

普通模式和无限模式应使用两个独立调度器，而不是把无限模式实现成普通碰撞算法的溢出分支：

- `CollisionFreeScheduler`：从上到下检查轨道，选择可安全发射且空间浪费最小的轨道；没有候选轨道时丢弃，不延迟原始时间。
- `RoundRobinScheduler`：完全跳过碰撞状态和文字宽度参与，按 `sequence % trackCount` 轮询发射；不因密度丢弃，也不延迟原始时间。

当前 `OverflowPolicy.OVERLAP` 仍会先扫描安全轨道，全部失败后才允许重叠，不符合“不限密度”的性能语义；强制重叠后只记录最新的轨道尾弹幕，也会丢失同轨上仍可见的更早弹幕状态。新的无限模式不应读取或更新普通模式的轨道占用信息。滚动、顶部和底部模式分别维护轮询计数器，同时间戳弹幕按原始顺序或稳定 id 排序，保证 seek 和重编排结果一致。

无限模式的编排复杂度接近 `O(N)`，代价转移到每帧可见文字数量。它不需要虚拟轨道、最小遮挡搜索或实时碰撞列表；这些做法会重新引入预计算和状态维护，偏离“不限即允许碰撞”的定义。

对用户应明确写成“不限密度（仍受设备性能限制）”。引擎内部可以保留防止 OOM/ANR 的资源上限，但必须通过统计回调报告，不能把资源保护伪装成普通密度过滤。

### 3.3 30fps、60fps 与屏幕刷新率

核心已有 `FPS_30 / FPS_60 / UNLIMITED`，Bilby 构造 `DanmakuHostState` 时使用默认值，因此实际固定为 60fps。这里需要修复的不只是设置接线。

#### 当前节拍算法的问题

当前实现以“距离上次输出是否超过目标间隔的 90%”决定是否画下一帧。这只能在面板刷新率恰好是目标帧率整数倍时工作得好：

- 144Hz 面板选择 60fps：两个 vsync 约 13.89ms，小于 15ms 阈值；三个 vsync 约 20.83ms，最终只有约 48fps。
- 90Hz 面板选择 60fps：每两个 vsync 输出一次，约 45fps。
- 165Hz 面板选择 60fps：每三个 vsync 输出一次，约 55fps。

应改为绝对时间轴上的 phase accumulator/deadline scheduler，让 144Hz 上按 2、2、3 个 vsync 等节奏交替，长期平均接近目标帧率，而不是每次从实际输出帧重新计时。

#### “跟随屏幕”还缺系统刷新率请求

`withFrameNanos` 只保证等待 Compose frame clock 的下一帧，不保证系统为这个窗口选择设备最高刷新率。`Modifier.preferredFrameRate` 的 Float 重载自 Compose UI 1.9.0 提供，`FrameRateCategory` 重载自 1.10.0；当前项目解析到 ui 1.12.0-rc01，两个重载都在且无需 opt-in。建议：

- 30/60 档由内部 scheduler 限制绘制频率；
- “跟随屏幕”档对弹幕层请求 `FrameRateCategory.High`。该 modifier 是 `DrawModifierNode`，帧率偏好在每次 draw 时自动下发，调用方不需要每帧重设；但它必须挂在实际产生绘制的节点上才生效；
- 不要把 `UNLIMITED` 描述为真正不受限制，改名为 `DISPLAY` 或 `VSYNC`；
- 系统只把刷新率请求当作偏好，官方指南列出的影响因素是面板能力、省电模式、更高优先级的 Surface 和其他应用的帧率设置，UI 应避免承诺一定达到 120/360Hz。

浮点重载的 360 上限来自 Compose 侧的 `@FloatRange(0.0, 360.0)` lint 注解，平台 `setRequestedFrameRate` / `Surface.setFrameRate` 并无此上限。面向未来可能超过 360Hz 的屏幕，使用 `FrameRateCategory.High` 比传一个写死的数值仍然更合适。

#### 高刷新率下的时钟精度

`DanmakuClock.positionMillis` 是整数毫秒。360Hz 一帧约 2.78ms，暂时仍可使用，但已经能看见 2ms/3ms 交替的量化。通用库可以把时钟改为微秒、纳秒或 `Double` 毫秒；播放器 adapter 仍必须保证只有一层位置外推，不能重新引入 Bilby 已经修掉的双重插值问题。

### 3.4 轨道设计、性能与显示效果

#### 目标架构：调度与在屏内容严格分离

Bilby 的性能优势应来自“预先调度，按时间投影”，而不是逐帧维护在轨弹幕并重新检测碰撞。目标数据流为：

`Source → Process → Measure → Schedule → Immutable Timeline → Emit/Query → Canvas Render`

- `DanmakuScheduler` 读取文字尺寸、viewport、速度和密度策略，计算发射时间、轨道和运动参数。碰撞只在这一层发生。
- `DanmakuFlightPlan` 是不可变结果，至少保存 `emitTime`、`duration`、`track`、`speed`、尺寸和 `visualKey`。调度器完成后即可丢弃轨道临时状态。
- `DanmakuEmitter` 根据全局播放时间查询 timeline。正常播放可以维护起止游标，seek 时直接重建时间窗口；它不判断碰撞，也不修改轨道。
- `DanmakuRenderer` 将当前时间投影成 `DrawItem`，只负责坐标、裁剪、绘制和命中测试。一个 Canvas 承载所有弹幕，不为每条弹幕创建 View 或 Composable。

`visibleUntil` 用于结束活动渲染期或建立区间索引，不表示删除原始弹幕。实现可以维护轻量 active set，也可以每帧查询 `[emitTime, visibleUntil)`；timeline 中的原始计划始终保留，以支持回退和 seek。

点击区域由当帧实际文字 bounds 生成。无限模式出现重叠时，默认按绘制顺序反向命中最上层弹幕；网络侧的点赞、举报等行为仍由宿主处理。

#### 固定穿屏时长与最小空间匹配

Bilibili 系播放器常用固定穿屏时长：长弹幕移动更快，使不同长度弹幕在近似相同的时间内完成显示。Bilby 当前采用“字宽缓幂律加速 + ±8.75% 确定性速度扰动”，字宽翻倍只加速约 14%；长弹幕仍明显停留更久，速度扰动也增加了追尾判断和轨道状态。

建议改为固定 duration：

```text
speed = (viewportWidth + virtualWidth) / duration
```

为避免整齐的机械运动，同时保留可证明的碰撞模型，可以给文字增加确定性的尾部虚拟留白：

```text
jitter       = hash(id) × 7.5%          // 0%～7.5%
padding      = jitter × measuredWidth
virtualWidth = measuredWidth + padding
speed        = (viewportWidth + virtualWidth) / duration
```

从右向左滚动时，padding 放在文字右侧，也就是运动方向的尾部。实际文字仍在 `emitTime` 从右边缘出现，虚拟包围盒则为后一条弹幕预留额外空间。基础 gap 可以针对参考字宽下调，但必须保持非负；百分比 padding 对长弹幕影响更大，无法与固定 px gap 完全抵消。

这一模型允许每条轨道只保留上一条弹幕的 `lastEmitTime` 和 `lastSpeed`。设统一穿屏时长为 `D`、viewport 宽度为 `W`、最小间距为 `g`，当前时间和速度为 `t`、`v`，某轨道尾弹幕的发射时间和速度为 `sᵢ`、`uᵢ`：

```text
remainingᵢ = max(0, sᵢ + D - t)
slackᵢ     = W - g - max(uᵢ, v) × remainingᵢ
```

`slackᵢ >= 0` 当且仅当发射瞬间不重叠，并且后车不会在屏内追上前车。普通模式在所有安全轨道中选择 `slack` 最小的一条，以最少的额外像素间隔接续前车；代价相同时优先上方轨道。没有安全轨道就丢弃。该公式中的 `max(uᵢ, v)` 同时覆盖两种危险点：后车较慢时检查入口间距，后车较快时检查前车退出左边界的时刻。

同一时间戳出现多条弹幕时，可以按速度从快到慢做稳定贪心匹配；若需要严格最优，则以 `slack` 为边权做最小代价最大匹配，目标依次为最大化成功数、最小化总空白、保持稳定轨道顺序。轨道数通常很小，普通模式使用无分配的 `O(N × T)` 扫描已经足够。无限模式不进入这套公式。

固定 duration、无独立速度 jitter 是只保存 `emitTime + speed` 的前提。若继续保留速度 clamp 或独立扰动，就无法从 `speed × D - W` 反推出虚拟宽度，轨道状态必须额外保存 width、`spawnClearAt` 或完整尾部信息。

#### 仍需定义的显示规则

1. **跨模式碰撞。** 滚动、顶部、底部目前各自维护占用。第一版应明确模式优先级和共享 viewport 分区；只有产品确实要求三类弹幕互相避让时，才引入共享空间占用层。
2. **可变字号。** 轨道高度必须使用实测高度，或先把协议字号映射为有限档位并按最大高度建轨；否则大字会侵入相邻轨道。
3. **自发弹幕优先。** `isSelf` 应在有限模式丢弃前获得保留机会。必要时允许单条自发弹幕越过密度限制，但不要让它改变其他已编排轨迹。
4. **固定弹幕。** 顶部和底部弹幕继续使用 `[emitTime, emitTime + duration)` 区间调度；无限模式分别轮询对应轨道。
5. **不默认延迟。** `DEFER` 会使弹幕脱离对应台词，不适合作为点播视频默认策略；若保留，应限于自发或实时弹幕。

#### 文字准备与绘制后端

当前绘制循环每帧对每条可见弹幕调用 `TextMeasurer.measure`，随后用同一个 `TextLayoutResult` 分别画描边和填充。渲染侧的 LRU 容量 512 是项目显式传入的（Compose 默认只有 8），普通密度下能够减少重复排版，但无限密度和大量唯一文本会发生淘汰；即使命中，每帧仍要构造并查询缓存 key，并提交两遍文字绘制命令。此外编译路径用的是另一个未传容量的 measurer，缓存只有默认 8 项——轨道调度所依赖的字宽测量比渲染路径更早发生缓存抖动，两条路径的测量也不保证同源。

推荐把文字处理拆成三个层级：

1. **Prepared layout。** 弹幕进入预热窗口时只测量一次，保存 `TextLayoutResult` 和实际 bounds。调度器只接收中立的宽高；平台 layout 由渲染准备层持有。缓存 key 包含文本、字体族、字号、字重、locale 和所有影响排版的属性。
2. **Compose `GraphicsLayer`。** 默认高性能后端把描边和填充录制进一个 display list，之后每帧只做 Canvas 平移和 `drawLayer()`。该方案保留平台文字质量，不需要给每条弹幕创建 RGBA 位图，也避免每帧重新提交两遍 `drawText`。缓存只覆盖当前在屏、未来 1～2 秒和有界的最近使用窗口；字号、字体、颜色、描边或 density 改变时释放并重建。
3. **Sprite atlas。** 极高密度下可将整条弹幕的最终外观预渲染为 sprite，打包进若干纹理页，由专用 Skia/OpenGL/Vulkan 后端批量绘制 quad。整句 sprite 能保留 shaping、fallback、emoji 和描边，第一版无需自研 glyph atlas。它会增加显存、首次纹理上传和亚像素采样风险，因此应作为可选后端，而不是默认路径。

`GraphicsLayer` 缓存 display list，不等同于强制生成离屏位图；但默认 `CompositingStrategy.Auto` 下，layer alpha 设到 `< 1` 必然提升为离屏缓冲。`ModulateAlpha` 可以绕开离屏，代价是重叠内容的 alpha 合成结果不同——弹幕恰恰会重叠，不能盲目切换。更稳妥的第一版是在样式变化时重录颜色和 alpha，再通过基准测试决定后续策略。

#### 其他性能热点与验证

- 整池编译仍在 Compose 主线程的 `LaunchedEffect` 中；纯调度应移到调用方提供的后台 dispatcher，平台文字准备按后端线程约束分批预热。
- 当前每条滚动弹幕重复测量正文，基准文本也重复测量；候选轨道的 `filter`/`toList` 会产生短命对象。应改为单次测量和无分配扫描。
- `DanmakuHostState` 的可见列表复制与同屏数量线性增长。可以让 emitter 输出可复用的 frame snapshot 或只暴露索引范围，避免复制全部元素。
- 每条弹幕的位置不需要独立时钟或 `Animatable`。对线性运动预计算 `slope` 和 `intercept`，每帧使用同一个播放时间做一次乘加；固定弹幕不做位置计算。

当前没有设备基准数据，不能仅凭 API 选择断言 `GraphicsLayer` 或 atlas 一定更快。基准至少覆盖 60/90/120/144/165/360Hz、1080p/1440p、10/30/60/120/500/1000 条同屏，以及重复文本和唯一文本、描边开关两组。对比当前实现、prepared layout、`GraphicsLayer` 和 sprite 四条路径，记录实际 draw fps、P50/P95/P99 帧耗时、UI/RenderThread/GPU 时间、每帧分配、缓存命中、纹理上传和 10 分钟热稳定性。360Hz 的单帧预算约为 2.78ms。

### 3.5 合并、繁简转换和高级弹幕

#### pakku 值得借鉴的处理管线

pakku 2026.5.1 包含以下设计：

- 在可配置时间窗口内聚类（窗口指簇首时间差，默认 30 秒，一个簇的实际跨度可以更长）；
- 完全相同、“编辑距离”、拼音和 2-Gram 词频向量相似度。其“编辑距离”并非教科书算法：源码注明为求速度改用字符多重集的 L1 差，词序完全打乱的两条距离为 0；cosine 实际比较的是 cos²，UI 上的 45% 对应 cos ≈ 0.67；
- 忽略末尾标点、多余空格、全半角差异（归一到混合规范形：字母数字归半角、标点归全角，不是单向半角化）；
- 正则替换、白名单、黑名单、是否跨模式合并。黑名单默认从 B 站播放器自身的屏蔽词表读取，pakku 的同步配置里已无该字段——正对应下文“服务端屏蔽规则由宿主同步后喂给核心”的分工；
- 处理跨 6 分钟分片边界的相似弹幕；
- 从聚类中选择代表文本和代表时间点；
- 合并数量前/后缀、合并后放大字号、模式提升。模式提升与合并数量无关，取的是簇内最高优先级的模式（底部 > 顶部 > 其他），簇里只要有一条底部弹幕，代表就变底部；
- 按 5 秒内容密度缩小或筛除弹幕；
- 处理前/处理后扩展钩子和统计结果。

Bilby 目前只是把分片追加到列表，没有去重、规范化、过滤或合并。建议建立显式管线：

`Source adapter → Decode → Normalize → Filter → Dedup → Layout → Render`

每一层应保留原始弹幕和处理原因，最终输出 `ProcessingReport`，至少包含输入数、去重吸收数、过滤数、因布局丢弃数、最大同屏数和耗时。现在 `OverflowPolicy` 的注释要求调用方感知丢弃率，但 API 实际没有汇总统计，需要补上。

匹配范围收敛为一档：**规范化后完全相同**。规范化沿用 pakku 验证过的手段（套路正则表、剥尾部标点、折叠空格、全半角归一），这一步加精确匹配已经吃掉刷屏弹幕的大头。相似度三件套和 dispval 整形明确不做：这条链的价值结构头重脚轻——相似度比对成本高（pakku 为此上了 wasm）、只覆盖打错字和同音字的长尾，且带误合并风险，pakku 的伪编辑距离连词序都不区分；在订阅制、不刷热门的使用方式下，命中万人刷屏名场面的频率本来就低。后台 dispatcher、确定性行为和跨分片上下文由核心定义，不依赖 JS Worker/WASM 的具体实现。

#### 去重的显示模型：同屏单实例 + 飞行中递增计数

pakku 的“代表弹幕 + 时间窗总量计数”和 PiliPlus 的“分片内去重 + `(N)` 前缀”（后者默认关闭）都被否决，原因相同：一个声称“这段时间共 N 条”的静态计数，要么在下一条同文本弹幕出现时被戳穿（窗口短），要么把好几个梗点压成一条脱离语境的孤例（窗口长），窗口参数调不掉这个结构矛盾。Bilby 改用瞬时语义的模型：

- **同文本同屏至多一条实例**。实例在屏期间到达的重复弹幕不再发射，而是让该实例的计数递增，屏上看到 `(2)` 涨到 `(47)`。
- **计数按“波”延续，不随实例离屏重置**。同文本相邻两次出现间隔不超过 G（取 5～10 秒；刷屏内部间隔与波间间隔差着量级，取值不敏感）视为同一波，实例接力时计数连续——上一条带着 `(47)` 离屏，下一条从 `(48)` 起跳，观众看到的是同一个计数器周期性重新入屏。间隔超过 G 清零，避免“哈哈哈”这类日常稀疏重复攒出终身里程表。
- **波内第二条到达才显示括号**。孤条和每波首条素着飞，屏上不出现 `(1)`。计数上限 999。
- **宽度按波终值预留**。点播的波终值编译期已知，调度时按最终数字建包围盒，飞行中换数字不改变占位，碰撞模型与 `slack` 公式不受影响；数字强制 tabular figures。实时弹幕终值未知，按一位余量预留，溢出即封顶。
- **架构落点**：波的划分是编译时对同文本出现序列按间隔切段，确定性、seek 重算一致；`FlightPlan` 携带 `(time, count)` keyframe 列表，emitter 到点通知渲染层更换 prepared layout。计数变化是稀疏事件，不进逐帧热路径。实时弹幕走同一条路：查同文本在屏实例，有则递增，无则按波规则发射。

#### 繁简转换

pakku 当前没有内置的繁简显示转换；拼音只用于相似度判断，这部分没有可抄的实现。合并收敛为精确匹配后，“繁简视为相同”的匹配规范化也一并不做——它只在简繁用户刷同一句话且要求合并计数时才有收益。繁简只保留**显示转换**一个用途：按用户选择将输出统一为简体或繁体。

实现形态：核心定义可选的 `TextTransformer` 接口，实现缺失时核心照常工作；Bilby app 侧用 OpenCC 词表实现。词表不打进 APK（短语表是兆级文本，当前 APK 仅 6.1MB），由客户端按需下载：

- pin 到 OpenCC 固定 release，对应 sha256 随 app 内置，下载校验后存 `filesDir`；不 pin 版本会让转换结果随词表漂移；
- 转换算法是词典链上的最长匹配替换，用 trie 自行实现，不引入 OpenCC 的 C++ 运行时；
- 转简体只需 TS 字表；转繁体需要 ST 短语表消歧一简多繁（头发→頭髮），第一版只做 OpenCC 标准繁，港台变体留作设置项的扩展空间；
- 词表未下载时该选项不生效并提示下载体积，不静默失败；
- OpenCC 及词表为 Apache-2.0，设置页署名即可。

#### 高级弹幕

当前 B 站 adapter 把模式 6 退化为普通滚动，把 7/8/9 直接丢弃；protobuf 解析也没有保留 `animation`、`extra`、`colorful`、`likeCount` 等字段。

高级弹幕支持应分级：

1. 逆向滚动（mode 6）：是普通轨迹系统的自然扩展，可优先实现。
2. mode 7 定位/运动弹幕：解析为安全的、数据驱动的 motion model，包括归一化坐标、持续时间、alpha、旋转、缩放和关键帧。
3. 彩色/动画字段：作为富样式扩展，不污染最小 plain-text 模型。
4. mode 8 代码弹幕：不要在通用库中执行任意脚本。可以明确不支持，或只接受宿主提供的沙箱 adapter。
5. mode 9 BAS：若实现，应先翻译为同一个安全 scene graph，再由 renderer 绘制，不能直接执行来源内容。

pakku 对 mode 7 的能力主要是从 JSON 中抽取可见文本参与合并，再把修改后的数据交回 B 站播放器；代码/BAS 也由原播放器处理。它不是 Bilby 高级弹幕 renderer 的现成实现。

### 3.6 作为通用 KMP 弹幕库还缺什么

#### API 与运行时能力

- **真正的 session/engine API**：接收 pool、clock、viewport、style 和 policy，负责尺寸变化、重编排、增量追加、seek 和取消。现在这层逻辑是 `BilbyPlayer.kt` 的私有代码，库使用者必须重新实现一遍。
- **稳定的运行时边界**：`Scheduler` 只生成不可变 `FlightPlan`，`Emitter` 只按全局时间查询，`Renderer` 只消费当帧 `DrawItem`。平台渲染对象不能进入 `commonMain` timeline。
- **可替换文字后端**：定义测量、预热、绘制、缓存回收和命中 bounds 接口；Compose layout、`GraphicsLayer` 和 atlas 可以独立演进，宿主也可注入自己的 Skia/OpenGL/Vulkan renderer。
- **动态配置更新**：区分只需重绘、需要重布局、需要重编时间轴的设置，避免所有变化都整池重编。
- **线程模型**：明确哪些 API 可从任意线程调用，编译在哪个 dispatcher，timeline 是否线程安全。
- **诊断与统计**：编译、过滤、丢弃、缓存命中、活跃条数和帧耗时回调。
- **交互能力**：命中测试、点击/长按、暂停选中弹幕、获取 bounds。点赞、举报、撤回等网络行为属于数据源 adapter，不应写进核心。
- **实时弹幕**：本地立即插入、自发优先、服务端确认后 id 替换、去重和时钟校正。
- **格式 adapter**：B 站 protobuf/XML、通用 XML/JSON/ASS 等做独立 artifact，不让通用核心认识 B 站模式号。
- **过滤 API**：按模式、关键词、正则、发送者、颜色、权重等；服务端账号屏蔽规则由宿主同步后喂给核心。
- **可访问性策略**：默认不让连续弹幕污染屏幕阅读器语义树，同时允许宿主提供“弹幕列表”这种可访问替代界面。

#### 推荐模块拆分

| artifact | 内容 | 目标 |
|---|---|---|
| `danmaku-core` | 中立模型、scheduler、不可变 timeline、emitter、policy、处理管线和统计 | `commonMain`，不依赖 Compose/Android |
| `danmaku-compose` | Compose Multiplatform host、prepared layout、`GraphicsLayer` 后端和命中测试 | Android、Desktop、iOS；按实际支持矩阵发布 |
| `danmaku-render-atlas` | 整句 sprite atlas 和批量绘制 backend | 可选；只发布经过基准验证的平台 target |
| `danmaku-player-media3` | Media3/ExoPlayer 时钟 adapter | Android only |
| `danmaku-source-bilibili` | protobuf/XML、模式映射和高级字段解析 | 可选；网络客户端由宿主注入 |
| `danmaku-transform-zh` | 繁简与中文规范化 | 可选，独立字典与许可证 |
| `danmaku-sample` / benchmark | 示例播放器、截图测试、Macrobenchmark | 不进入运行时依赖 |

如果首个版本只承诺 Android 和 Desktop，就应明确写出目标矩阵，不要因为用了 Kotlin Multiplatform plugin 就宣称 iOS/Wasm 已支持。

#### 当前打包缺口

`:danmaku` 目前应用 `com.android.library`，只有 Android source set，没有：

- Kotlin Multiplatform target 和层级 source set；
- `groupId / artifactId / version`；
- `maven-publish`、签名和 Maven Central 发布流程；
- POM 中的许可证、SCM、开发者信息；
- sources/documentation artifacts；
- 稳定 API/ABI 检查、`explicitApi()` 和兼容性策略；
- consumer ProGuard 规则与 Android Baseline Profile；
- 独立的 sample、跨平台测试矩阵和性能门槛。

公共 API 暴露了 Compose 的 `TextStyle` 和 `Color`，对应 Compose 依赖在当前 Gradle 文件中却全部使用 `implementation`。独立发布时需要重新核对 API dependency 边界，否则消费者可能缺少编译期类型。

KMP 发布还要注意：Gradle 会生成根 `kotlinMultiplatform` publication 和各平台 publication；Apple target 必须在 macOS 构建。发布流程应由独立 tag 触发，生成带源码和文档的签名制品，并验证 Gradle Module Metadata 与 Maven POM。Bilby 当前只发布 APK 的 workflow 不能直接承担这件事。

## 4. 建议的目标配置模型

第一版设置可以保持克制，但底层类型要避免再次写死：

| 用户设置 | 建议默认 | 核心语义 |
|---|---:|---|
| 显示区域 | 50% | viewport 的纵向范围，不只是轨道数倍率 |
| 同屏密度 | 标准 | 标准档做无碰撞最小空白匹配；不限档完全跳过碰撞并轮询轨道 |
| 帧率 | 60fps | 30 / 60 / 跟随屏幕 |
| 字号 | 标准 | 全局 scale；协议字号只作为相对倍率 |
| 速度 | 标准 | 以统一完整穿屏 duration 表达；长弹幕据字宽自动加速 |
| 模式 | 全开 | 滚动/顶部/底部/逆向分别过滤 |
| 合并 | 开 | 规范化后相同文本同屏单实例，计数飞行中递增，波间歇超 G 清零；不做相似度合并 |
| 繁简 | 保持原文 | 原文/简体/繁体；仅显示转换，OpenCC 词表按需下载 |
| 描边 | 标准 | 宽度、颜色；允许关闭 |

设置变化应分类：透明度和颜色只重绘；viewport、字号、速度和密度需要重布局/重编；文本转换、过滤和合并需要重跑处理管线。库应把这套 invalidation 规则封装起来。

## 5. 建议路线图

### 阶段 A：先把现有能力做正确

1. 引入真正的 viewport，接入 25/50/75/100% 设置。
2. 将帧率三档接入偏好；修正非整数倍面板上的 scheduler；跟随屏幕时请求高刷新率。
3. 新建密度 policy 和统计报告：有限档使用无碰撞 scheduler，无限档使用不检查碰撞的 round-robin scheduler。
4. 修复失败分片被永久跳过的问题：段号在请求发起前就登记进 `requestedDanmakuSegments`，失败后不移除，seek 回该段也不会重拉——不是缺重试，是失败即永久缺段。再补 segment 预取和“空分片/请求失败”状态区分，避免分片边界短暂缺弹幕。
5. 用假 frame clock 给 30/60 scheduler 写确定性测试。

### 阶段 B：轨道和高刷新率性能

1. 固化 `Scheduler → FlightPlan → Timeline/Emitter → Renderer` 边界，移除运行时碰撞反馈和逐条动画状态。
2. 改用固定 duration 速度模型和尾部 virtual padding jitter；用 `slack` 公式完成有限模式的安全判定与最小空间匹配。
3. 处理跨模式规则、最小间距、自发优先和可变字号。
4. 后台编译、单次测量和无分配轨道扫描；提前生成 `TextLayoutResult`，实现有界的 `GraphicsLayer` 预热与回收。
5. 建立真机 Macrobenchmark/Perfetto 基线；只有 `GraphicsLayer` 仍无法满足高密度目标时，才实现整句 sprite atlas backend。

### 阶段 C：预处理管线

1. 建立可插拔 processing pipeline 和报告。
2. 规范化（套路正则、剥尾标点、空格折叠、全半角归一）+ 按间歇阈值 G 分波的精确去重；黑白名单和跨分片上下文。
3. 递增计数显示：`FlightPlan` 计数 keyframe、波终值宽度预留、素显示规则与 999 上限。
4. 繁简显示转换作为可选 `TextTransformer`，OpenCC 词表按需下载；保持原文与处理后文本同时可追踪。

明确不做：相似度合并（伪编辑距离、拼音、2-Gram）、代表弹幕 + 总量计数的显示形态，以及 dispval 内容密度整形，理由见 3.5。

### 阶段 D：高级能力与独立发布

1. 逆向滚动、mode 7 motion model、彩色/动画。
2. 命中测试、弹幕列表和实时追加；B 站发送/点赞/举报放在 app adapter。
3. 按 `core / compose / render-atlas / adapters / transforms` 拆分 KMP 工程，先发 alpha。
4. 加 API/ABI 检查、文档、sample、benchmark、Maven Central 签名发布和目标平台兼容矩阵。

## 6. 验收标准

下一轮实现不应只以“设置项出现了”为完成标准。至少应满足：

- 显示区域改变后，三种普通弹幕都严格落在同一 viewport 内，且转屏/分屏后无一帧错位。
- 30/60 档在 90/120/144/165Hz 面板上长期平均帧率接近目标，没有固定降为 45/48/55fps。
- 跟随屏幕档能观测到系统实际选择的刷新率；未达到请求值时有可解释数据。
- Unlimited 不因普通轨道容量丢弹幕，资源保护导致的舍弃有独立统计。
- Unlimited 的轨道序列严格轮询，不执行安全轨道扫描；普通模式没有安全轨道时不延迟，直接丢弃并计数。
- 有限密度下可报告输入数、处理后数、布局丢弃数和峰值同屏数。
- 去重显示满足：同文本同屏至多一条实例；波内计数跨实例连续、间歇超 G 清零；seek 后计数与顺播结果一致；计数变化不触发逐帧重排。
- 固定 duration 下，轨道 `slack >= 0` 与“入口不重叠且屏内不追尾”等价；同速、前车快、后车快三类性质均有自动化测试。
- scheduler 输出后，emitter 和 renderer 不读取或修改轨道占用状态；seek 只依赖 timeline 和全局播放时间即可恢复画面。
- 普通绘制帧不调用 `TextMeasurer.measure`；prepared layout 和 `GraphicsLayer` 的缓存命中、回收及样式失效有可观测数据。
- 同轨、跨模式和可变字号的碰撞性质有自动化测试。
- 在声明支持的最高刷新率和密度档上，真机 P95/P99 帧耗时满足预算，10 分钟运行不因热降频持续恶化。
- KMP artifact 能从一个最小外部 sample 通过 Maven 坐标消费，而不依赖 Bilby 私有类型或主题。

## 参考资料

- [pakku.js 源码](https://github.com/xmcp/pakku.js)；工作区副本为 2026.5.1。
- [Chrome Web Store：pakku 2026.5.1](https://chromewebstore.google.com/detail/pakku%EF%BC%9A%E5%93%94%E5%93%A9%E5%93%94%E5%93%A9%E5%BC%B9%E5%B9%95%E8%BF%87%E6%BB%A4%E5%99%A8/jklfcpboamajpiikgkbjcnnnnooefbhh)。
- [Compose `withFrameNanos`](https://developer.android.com/reference/kotlin/androidx/compose/runtime/package-summary#withFrameNanos(kotlin.Function1))。
- [Compose `Modifier.preferredFrameRate`](https://developer.android.com/reference/kotlin/androidx/compose/ui/preferredFrameRate.modifier)。
- [Android frame-rate matching](https://developer.android.com/media/optimize/performance/frame-rate)：刷新率请求是偏好，不保证被系统满足。
- [DanmakuFlameMaster FAQ](https://github.com/bilibili/DanmakuFlameMaster/wiki/%E5%B8%B8%E8%A7%81%E9%97%AE%E9%A2%98)：滚动弹幕共享 duration，长弹幕以更高速度完成显示。
- [Compose `TextMeasurer`](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextMeasurer)：文字布局成本和内置 LRU 的适用范围。
- [Compose `GraphicsLayer`](https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/layer/GraphicsLayer)：录制 display list，并在不重录内容时应用变换。
- [Android rendering performance](https://developer.android.com/topic/performance/vitals/render)：UI/RenderThread 分析、Bitmap 缓存和首次纹理上传注意事项。
- [Kotlin Multiplatform library publication](https://kotlinlang.org/docs/multiplatform-publish-lib.html)。
- [KMP 发布到 Maven Central](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-libraries-to-maven.html)。
- [Android Baseline Profiles for Compose](https://developer.android.com/develop/ui/compose/performance/baseline-profiles)。
