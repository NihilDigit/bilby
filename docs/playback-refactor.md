# 播放架构重构

2026-08-13 定稿。依据三份调研：本仓库现状（file:line 见各节）、PiliPlus 的实现（API 事实已并入
`notes/playurl.md` §8）、Media3 1.10.1 文档镜像与构件（javap 验证）。

## 要解决的问题

1. 进度上报的驱动在 UI 层（`BilbyPlayer.kt:169-174` 的 5 秒循环），换条的权力在 service。由此：
   听视频模式无周期心跳；自动连播时上一条的最终位置与完播（`played_time=-1`）发不出去；暂停恢复
   不补报。
2. 当前 cid 有两份真相（service 的 `loadedCid` 与 `QueueItem.cid`，后者在装载前就被
   `updateCurrentCid` 改掉），切 P 窗口内心跳可能发出「新 cid 配旧位置」，污染服务端进度。
3. 续播读取有三个来源（playurl 附带字段、`wbi/v2` 补查、离线 `meta.json`），两套「忽略页面送来的
   默认 cid」防御机制（`resumedPartBvid/resumedFromCid` 与 `offlineBvid`）。
4. 队列不用 ExoPlayer playlist API，靠 `QueuePlayer`（ForwardingPlayer）覆写喂给 MediaSession，
   且从不发 `onXxxChanged`，MediaController 的命令缓存失效，逼出两组本可走标准 Player API 的
   自定义命令。直播靠清空队列等逐字段特判挤进点播路径。
5. `AudioPlaybackService.kt:1369-1380` 断言 MediaController 没有 `COMMAND_SET_VIDEO_SURFACE`。
   经 javap 证伪：1.10.1 的 `MediaController` 有全部 `setVideoSurface*` 方法，Surface 作为
   Parcelable 跨 binder 送达 session 端，且走同步快路（`MediaSessionStub` 对该命令有专门分支）。
   静态 `currentPlayer` 的存在理由不成立。

## 决定

### 1. 入口只有 bvid，cid 是播放层的内部状态

路由、队列项的身份只有 bvid。装载时一次解析 cid，优先级：用户显式指定的分 P >
服务端 `(last_play_cid, last_play_time)` > 第一 P。「用户指定」是一次性命令参数（页内切 P、
缓存列表点某行），消费即弃，不进路由身份，转屏重建后不再生效。

随之删除：`Video.cid` 路由参数（`Destinations.kt:28-33` 自标遗留）、`resumedPartBvid/
resumedFromCid` 与 `offlineBvid` 两套防御、`updateCurrentCid` 一路。上报用的 cid 一律取
装载层已确认的值，不取队列的。

服务端整个稿件只存一对 `(cid, 秒数)`，不按分 P 存（抓包证实，`notes/playurl.md` §8.2.1）。
本地不模拟按 P 存进度；离线 `meta.json` 按 `(bvid, cid)` 存是例外，服务于离线文件自身。

### 2. 队列住进 ExoPlayer playlist，取流延迟到开播

`MediaItem.mediaId` = bvid，队列即 player 的 playlist。CDN 直链带时效，不能建队列时批量取流
（现状注释 `AudioPlaybackService.kt:159-161` 的理由成立，结论不必是放弃 playlist）：给
ExoPlayer 注册自定义 `MediaSource.Factory`，返回一个延迟包装——`prepareSourceInternal` 时才
调 playurl、构造内层 `MergingMediaSource`（或本地/直播源）并委托。工厂按 MediaItem 分派：
离线副本在盘上则本地源，直播项则直播源，否则在线解析。

这是全案唯一的新自定义组件，风险最高，单独冒烟：timeline 透传、release 路径、解析失败时的
错误面（要能落到 `onPlayerError` 而不是吞掉）。若延迟 MediaSource 走不通，回退方案是保持
单条装载但把队列语义收进一个纯状态机——回退时本文档其余决定不变。

随之退役：`PlaybackQueue` 双表结构、`QueuePlayer` 全部覆写、`ACTION_SEEK_TO_BVID` 等被命令
缓存逼出来的自定义命令。shuffle 换 `setShuffleModeEnabled`：Media3 的索引语义
（`getCurrentMediaItemIndex` 永远返回原始顺序下标，只有 `seekToNextMediaItem` 走乱序）正好
实现「列表顺序不变、只变播放顺序」的产品规则。repeat 保持禁用。系统媒体控件与 Android Auto
的队列由 Timeline 自动生成，不再需要手工喂。

多 P 不是队列项，维持现状语义：cid 是当前队列项的内部状态，切 P 不动队列位置。

重装当前条不用 `replaceMediaItem`（已修，2026-08-13）：replace 的实现是先插新条再删旧条，
删除正在播的条目时 `resolveSubsequentPeriod` 按 shuffle 顺序选落点——顺序播放落到替换
条目，随机模式落到乱序表随机一条，表现为随机下切 P/切清晰度/重试跳去队列随机一条。
`reloadCurrent` 现在的顺序是插到后一位、显式 `seekToDefaultPosition`（不经 shuffle 解析）、
再删旧条。

实施中补定的三条（2026-08-13）：起播位置在解析末尾 `seekTo`，C/D 阶段由解析器接管；
cid 不写进 MediaItem，标题/封面回填推迟到解析完成后走 `replaceMediaItem`
（`canUpdateMediaItem` 为真不重新取流），避免重入释放正在 prepare 的源；随机播放下
队列面板的「N / M」改为列表位置——与「列表顺序不变、高亮滚动」一致，随机开关时数字不变。

### 3. 进度会话（ProgressSession）

每次装载成功建一个会话，身份 `(aid, cid)` 创建时冻结、不可变。会话拥有该内容的全部上报：

| 触发 | 行为 | 节流 |
|---|---|---|
| 位置前进 | 常规心跳 | 距上次 ≥5 秒 |
| 暂停 → 恢复 | 补发 | 距上次 ≥2 秒 |
| 暂停本身 | 不发 | — |
| seek 落点确认 | 立即发，节流基准重置到目标位置 | 无 |
| 播完 | 发 `played_time=-1`（距结尾 1 秒内即算完播） | 无 |
| close() | 定格补发最终位置 | 无 |

`close()` 幂等：第一次发定格上报然后置死，再调是空操作。任何退出路径（装载新内容、服务停止、
出错清理、登出）只管调旧会话的 close，不关心顺序和重复。

位置来源两层：连播切条时 `Player.Listener.onPositionDiscontinuity` 的 `oldPosition`
（`PositionInfo` 带 `mediaItem` 与 `positionMs`，即离开那条的权威最终位置；`reason` 区分
`AUTO_TRANSITION`（发 -1）与 `SEEK`）；会话自缓存的「最后观察位置」作兜底，覆盖服务停止、
出错等没有 transition 事件的退出。close 不在关闭时刻向播放器现取位置——那时 `currentPosition`
可能已属于下一条。

同稿件切 P 的定格上报很快被新 P 心跳覆盖，无害；换稿件时它是旧稿件历史记录的唯一机会。两种
情况同一条路径，不分支。

心跳参数维持现状四字段（`aid`、`cid`、`type=3`、`played_time`，csrf 由 `postAction` 补），
PiliPlus 同样只发最小集。`HeartbeatReporter.report` 四个从不进请求体的形参随本次删除。

直播不建会话。直播不上报心跳由「没有会话」表达，不是分支。

会话依赖的服务侧位置流同时喂弹幕时钟。A 阶段删静态引用后弹幕时钟改读 controller，
其位置外推在 `setPlaybackSpeed` 时不刷新锚点，长按倍速瞬间弹幕集体跳动（已知回退，
记录在 `PlayerDanmakuClock` 注释）。controller 侧无解，服务自己发位置刻度是唯一修法，
D 阶段随会话一起落地。

### 4. 进度恢复：一个解析器，合并规则保留

恢复只发生在装载时刻，一次解析：用户显式意图 > 离线副本本地进度（不等网络）> 云端。多 P 先问
`wbi/v2` 的 `last_play_cid` 定 P（它比 playurl 便宜且语义正确，见 `notes/playurl.md` §8.2.1），
再按解析出的 cid 取 playurl，其 `last_play_time` 必然匹配。装载后云端值再变不自动 seek，
`CloudResumeHint` 等用户点，维持现状。

离线与云端的冲突规则原样保留（`ResumePosition.kt`）：本地记 `serverBase`（上次成功上报时
服务端知道的值），`server != base` 取服务端、`== base` 取本地，不取较大值。基线推进的唯一
入口仍是心跳成功回调。多 P 边界（在线看过 P2 后离线播 P1，云端参考按 0 计）是服务端单对值
模型的必然结果，不是 bug。

### 5. 直播是普通 MediaItem

`isCurrentMediaItemLive` 等能力在 `Player` 接口上，直播项由 MediaItem（自定义字段或 tag 携带
roomId/qn）表达，工厂造直播源。删除 `live` 与 `queue` 的互斥字段、`playLive` 的逐字段清理、
`publishState` 的 `queue = null` 特判。保留的真差异只有三条，全部在数据层：取流接口不同、
不可 seek（UI 手势已按 `PlayerGestureOptions` 关闭）、无进度会话。

错误恢复：`ERROR_CODE_BEHIND_LIVE_WINDOW` → `seekToDefaultPosition(); prepare()`（文档标准
处理）。注意 FLV 是 progressive 流，没有 live window，live-streaming 文档那页的能力不适用——
取流时优先挑 HLS 是配套动作。直播重试的「下播则停」逻辑保留。

### 6. Surface 走 MediaController，删静态引用

`AudioPlaybackService.currentPlayer` 删除，两处读取点（`VideoScreen.kt:293`、
`LiveRoomRoute.kt:80`）改为把 controller 当 `Player` 传给 `PlayerSurface`。约束：连接授权
保持默认全量 Player.Commands——`COMMAND_SET_VIDEO_SURFACE` 被收窄时现象是视频无画面且无日志
（controller 侧静默 return）。`onVideoSizeChanged` 经 controller 监听。

`setBackgroundPlaybackAllowed` 与 `pauseForAppBackground` 两条 volatile 通道暂保留（读取方
是同步的，自定义命令异步投递解决不了），但随 playlist 落地重新评估是否仍需绕行。

## 进度

- **A、B 已完成并真机验证**（至 commit `60391eb`，2026-08-13）：Surface 走 controller、
  playlist + `LazyMediaSource` 延迟取流、`QueuePlayer` 仅存 playIntent 与循环守卫、
  随机切 P/切清晰度/重试、队列去重、加载指示器收敛为壳内一个。
- **C 已落地，真机未验**（commit `bc144d2`，2026-08-13）：装载解析器 `player/LoadResolver.kt`
  按「用户指名 > 本地副本 > 云端」一次解析；路由 `Video` 与 `ACTION_OPEN_VIDEO` 都不再带 cid，
  缓存列表点某行的指名走 `player/PartRequest.kt` 的一次性通道；`resumedPartBvid/resumedFromCid`
  与 `offlineBvid` 两套防御、`QueueItem.cid` 一并删除；`QueueState.currentCid` 上提为
  `AudioPlaybackUiState.currentCid`，上报、弹幕、字幕都读装载层确认的那一个。
  **多 P 云端续播此前不工作的直接原因没能在真机上定位**（本轮无设备），改动删掉的是它的两个
  产生条件：页面第二遍打开命令带着默认 P 覆盖解析结果，以及 `wbi/v2` 只在「打开视频那一次」
  才问（队列内换一条、点队列里的一条都问不到）。这一条仍按验收标准真机跑，不过则回到这里。
- **D 待做**：进度会话、服务侧位置流（顺带修弹幕倍速跳动，见决定 3）。
- **E 待做**：直播 MediaItem 化。
- 已知观感项：装上前后两个加载指示器是同种不同实例，切换瞬间动画相位重置，暂不处理。

## 实施顺序

依赖关系：B 是地基，C、D、E 都长在它上面；A 独立。每阶段编译通过、单测通过、各自冒烟，
一阶段一批 commit（按主题可再拆）。

- **A. Surface 走 controller**（决定 6）。小、独立、立刻删掉一个错误前提。
- **B. playlist + 延迟取流**（决定 2）。风险最高，先把延迟 MediaSource 单独冒烟再接队列。
- **C. cid 解析器 + bvid 入口**（决定 1、4）。验收（2026-08-13 定）：多 P 云端续播——服务端
  记着后面某 P 时打开视频要落在那个 P 的记录秒数上，B 阶段收尾后这条实测不工作，修复
  归 C，不单独补丁；切 P 是同稿件内的命令，过渡不得再呈现为「换了一条视频」（起播走
  该 P 的记录，弹幕/字幕随 cid 换，页面身份与队列位置不动）。
- **D. 进度会话**（决定 3）。依赖 B 的 transition 事件。
- **E. 直播 MediaItem 化**（决定 5）。依赖 B 的工厂分派。

## 随改动必须同步的过时断言

- `AudioPlaybackService.kt:1369-1380`、`VideoScreen.kt:290-292`：COMMAND_SET_VIDEO_SURFACE
  断言，A 阶段删除。
- `CLAUDE.md` Architecture traps 一节的「video must be attached to the same-process
  `currentPlayer`」，A 阶段改写。
- ~~`HistoryRepository.kt:108` 注释写进度走 `x/v2/history/report`~~，已改为
  `x/click-interface/web/heartbeat`（commit `b9021cd`）。
- ~~`ui/Destinations.kt:28-33` 的 `Video.cid` 遗留说明~~，连同参数一起删（C 阶段）。

## 测试

能抓到问题的三处：延迟 MediaSource 的 prepare/release/错误传播（B）；ProgressSession 的
协议正确性——幂等 close、身份冻结、各触发的节流（D）；cid 解析器的优先级与降级
（C，`LoadResolverTest`，本地副本那几条同时断言两个网络源一次都没被问过）。
`PlaybackQueue.kt` 现有测试随其退役删除，shuffle 语义由 Media3 保证，不重写断言。
UI 与网络胶水不写。
