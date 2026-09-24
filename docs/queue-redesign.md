# 播放队列重做

2026-09-24 定稿并实施。依据：本仓库现状、B 站接口实验（事实已并入
`notes/space-and-search.md` 1.4.3）、Media3 1.10.1 构件（javap 验证）。

## 要解决的问题

1. **队列内容与用户所见无关。** 无合集的视频用 UP 最新投稿 25 条凑队列，老视频则是
   「最新 24 条加这一条」。用户从收藏夹、稍后再看、UP 投稿栏（含排序、空间内搜索）点进来，
   看到的是另一份列表。
2. **页面与服务轮流改写对方。** 两处成因：
   - 「页面显示哪条」有两份真相：`VideoRoute` 的 `episode` 与服务的队列当前条，靠
     `followingQueue` 同步。后者是 `remember`，进 UP 主页时随组合销毁，回来后若队列当前条
     不等于路由 bvid，页面从此不再跟随队列。
   - `ACTION_OPEN_VIDEO` 身兼打开、补元数据、离页后续播、重试建队列、关弹幕输入续播五职，
     且随 MediaController 重连重发。离开期间队列前进过，回来即被拽回旧条；队列被别的视频
     换掉过，回来即按旧条的归属重建队列。
3. **切换忽略分 P。** 上一条/下一条走 `seekToNextMediaItem`，自动连播由 ExoPlayer 按
   media item 前进，没有一处先问「本视频还有下一 P 吗」。

## 决定

### 1. 队列即用户所见的列表

点进去之前看到什么列表，队列就是什么列表，含排序与筛选。入口上下文
（`data/QueueContext.kt`）：

| 入口 | 上下文 | 来源 |
| --- | --- | --- |
| 合集/系列目录 | `Collection(mid, id, isSeason, name, page)` | 目录接口，按页 |
| 空间投稿栏 | `UpArchive(mid, order, keyword, page)` | web 投稿接口，按页，带排序与生效中的搜索词 |
| 收藏夹 | `FavFolder(mediaId, title, page)` | 收藏夹内容，按页，失效稿件不收 |
| 稍后再看（列表页与「我的」预览） | `ToView` | 一次给全 |
| 缓存列表 | `Offline` | 缓存库，不联网 |
| 动态流、搜索、历史、站内跳转、分享链接、找相关 | `Affiliation` | 见下 |

`Affiliation` 退到**视频自身归属**：合集 → 系列（预算内扫前几个）→ UP 投稿里它前后的邻居
→ 单条。邻居走 App 端 aid 游标接口：web 投稿接口的深页号会被服务端夹住，游标接口按 aid
一次定位。动态视频与直播回放不在投稿列表里，游标返回 -1200，落到单条。

入口来源打不开（多半是离线）而本地有这条视频的完整副本时，退到缓存库。

「最新 24 条加这一条」的兜底删除。

### 2. 上下文随导航 key，页面只报帧

上下文由点击处写进 `Video` NavKey，随导航栈保存。页面组合时只发一条幂等命令：

| 命令 | 用途 |
| --- | --- |
| `ACTIVATE_FRAME(frameId, context, bvid)` | 这一页到了前台：新帧则按上下文打开，有快照则恢复，已是活帧则续播 |
| `RETRY_QUEUE` | 队列没建成时重试 |
| `EXTEND_QUEUE(before)` | 完整队列面板滚到一头时续取 |

元数据由服务取流时从详情补，页面不发第二遍。命令只在连上控制器时发，不跟着页面的 bvid
重发：页内切集是队列自己走的。不带 bvid 当身份，页面无从把播放器拽走。

### 3. 单一队列实例，帧叠在导航栈上

服务持有队列栈（`AudioPlaybackService.frames`）：

- **活帧**即 ExoPlayer playlist，仍然只有这一份活的队列。
- **挂起帧**存快照：条目、当前条、分 P、位置、随机开关。

每个视频页是一帧，帧 id 在压栈时分配（`withNewFrame`），不在构造 key 时生成：解析链接得到的
是目的地，还不是栈上的一页。

**帧的先后由导航栈决定，服务只按 id 存。** 找相关替换栈顶、`pushUnique` 挪动页面都会重排
导航栈，服务另记一份顺序就是第二份真相。MainActivity 在导航栈变化时调用
`AudioPlaybackService.retainFrames`，不在栈上的帧连同快照丢掉。

| 事件 | 服务动作 |
| --- | --- |
| 列表页点开视频（压栈） | 活帧存为快照，按上下文建新活帧 |
| 页内切集、切 P、连播 | 只改活帧 |
| 视频页回到前台 | 已是活帧则续播；否则活帧存为快照，换上该帧快照 |
| 打开直播间 | 活帧存为快照，直播不是帧 |
| 视频页出栈 | `retainFrames` 丢掉该帧；它是活帧时播放器照放，队列不再属于任何一页 |
| 进程被杀后恢复 | 服务无此帧，按 key 里的上下文与页面保存的当前条重建 |

新帧或恢复出的当前条正好是播放器正在放的那一条时，留着它只换两边，不重新取流。

页面只是活帧的视图：自己的帧是活帧时显示队列当前条，否则停在离开时那一条（随页面保存）。
`episode` 与 `followingQueue` 删除。

### 4. 窗口与续取

打开时当前视频前后各至少 10 条（`QUEUE_WINDOW`）。分页来源整页读，不在页内再截。当前条距
任一端不足 3 条（`EXTEND_THRESHOLD`），或完整队列面板滚到一端时，服务向该端续取一页并插入
playlist，按 bvid 去掉已有的条目。合集详情、稍后再看、缓存库一次给全，不续取。

听视频页的「N / M」是在整份来源里的位置与来源总数，不是 playlist 下标。来源给不出总数
（收藏夹、游标接口）时不显示。

### 5. 分 P 优先

`QueuePlayer` 由 `ForwardingPlayer` 改为 `ForwardingSimpleBasePlayer`，覆写 `handleSeek`：

- `COMMAND_SEEK_TO_NEXT`：有下一 P 切下一 P，否则下一条。队列末条上 `BasePlayer.seekToNext`
  走 `ignoreSeek`，仍以 `INDEX_UNSET` 调到 `handleSeek`，末条的下一 P 因此可达。
- `COMMAND_SEEK_TO_PREVIOUS`：播放超过 `maxSeekToPreviousPosition` 回本 P 开头；否则有上一 P
  切上一 P，否则上一条。
- `*_MEDIA_ITEM` 两条命令保持原义，供队列面板的「跳到某一条」使用。

应用内按钮由 `seekToNextMediaItem` 改为 `seekToNext`，与通知栏、耳机线控同一条路。

自动连播：当前条有下一 P 时开 `pauseAtEndOfMediaItems`，在 `END_OF_MEDIA_ITEM` 时切下一 P。
关闭自动连播、定时器「播完这条」均按 P 计。非末条停在末尾时播放器不进 `STATE_ENDED`
（javap 验证），收尾因此挂在 `END_OF_MEDIA_ITEM` 与 `STATE_ENDED` 两处，由 `endHandled`
去重。

## 待定

- 续取阈值 3 条是估计值，按真机手感调整。
- UP 投稿与收藏夹来源没有目录页入口，标题行点不动。
