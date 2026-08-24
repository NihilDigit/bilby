# 直播间重新设计

一份待拍板的方案。目标是让直播间的信息流承载它本来就有的那些消息类型，而当前实现只认三种。

参照实现的事实已经写进 `notes/live.md` 第 6 至 9 节，本文不重复接口细节，只在需要时引用。
M3 的原文引用标了镜像里的文件与行号。

---

## 1. 现状

`LiveRoomScreen.kt` 的分区是四层：播放画面、主播行、两个 Tab（聊天、大航海）、常驻输入栏。
聊天那一屏顶上是一条横向滚动的醒目留言卡片带，下面是弹幕列表。

`LiveDanmakuClient.kt` 的 `emitCommand` 认三条 cmd：`DANMU_MSG`、`SUPER_CHAT_MESSAGE`、
`WATCHED_CHANGE`，外加心跳回包里的人气值。**其余全部丢弃**，注释里写着“礼物、进场、人气一概
丢弃”。

于是直播间里实际发生的事，界面上能看见的只有一部分：

- 送礼、上舰、进场、点赞、红包、超管警告、主播中途下播、主播改标题，一律看不到。
- 弹幕只剩昵称与正文。粉丝勋章、表情弹幕、回复弹幕（`@某人`）都在数据里，解析时被丢掉。
- 醒目留言没有倒计时、没有撤回、进房时不补历史，所以进房前发的那些一条都看不到。
- 醒目留言那条横条在数量为零时整条消失，聊天列表跟着上下跳一次。

## 2. 分区

```
┌───────────────────────────────────────┐
│                                       │
│            播放画面 16:9               │  ← 不变
│                                       │
├───────────────────────────────────────┤
│ ◯ 主播名                              │
│   这场的标题                        →  │  ← 不变
├───────────────────────────────────────┤
│   聊天      醒目留言      大航海        │  ← 由两屏改为三屏
├───────────────────────────────────────┤
│                                       │
│  ◯ 昵称[勋章] 弹幕正文                 │
│  ◯ 昵称 弹幕正文                       │
│  ┃ ◯ 昵称  ¥30                        │  ← 醒目留言内联，带左侧档位色竖条
│  ┃ 留言正文                            │
│  ⚓ 昵称 开通了舰长                     │
│  ◯ 昵称 弹幕正文                       │
│  ─────────────────────────────────    │
│  主播已下播                            │  ← 系统行，居中
│                                       │
├───────────────────────────────────────┤
│ [ 说点什么…                  ] [ ↑ ]   │  ← 不变
└───────────────────────────────────────┘
```

三处结构性改动：

**醒目留言从横条改为内联进流，另占一屏。** 横条撤掉。它的两个代价是实打实的：数量为零与非零
之间布局要跳一次高度；窄屏上一次只露得出一张半卡片，横滚在一条本来就要竖滚的列表上面。改成内联
之后，一条醒目留言在流里出现一次，不再错过；要回看历史就切到「醒目留言」那一屏，那里是一份按
到期时间排的完整列表。

> **2026-08-24 修订（owner 定）：横条以 chip 的形态加回来。** 真机上看下来，内联那一条在一屏
> 几十行里太不显眼。新的一栏在聊天列表上方，**高度恒定、常显**，没有留言时就是一条空的浅色底
> —— 上面第一条代价（零与非零之间跳高度）由此消失，不是被接受。第二条由形态解掉：一个 chip
> 只有头像加金额，一屏排得下四五个，多了才需要滑。点一个 chip 翻到「醒目留言」那一屏并滚到
> 那一条、亮一下。内联那一份保留，两者不冲突：流是记录，横条是"此刻还有哪几条"。

**「醒目留言」这个 Tab 标题不带数量。** PiliPlus 在聊天区右上角放了一个 `SC(n)` 的胶囊按钮，
本项目不抄。风格指南 §4.2 的判据是“它有没有在暗示现在有新东西”，一个会自己变大的数字正是那个
暗示。切过去就能看见有几条。

**聊天那一屏由单一弹幕列表改为异构消息流。** 下一节逐类给处理。

宽屏（expanded）与全屏两档暂不改，沿用现有行为。

## 3. 每类消息怎么处理

`notes/live.md` §6 记了一条事实：礼物、上舰、进场、红包这四类在 PiliPlus 里**没有可对照的
实现**，字段名与合并策略都得自己实测。所以下表里这几类的字段是待验证的。

| 类别 | cmd | 处理 | 视觉 |
|---|---|---|---|
| 弹幕 | `DANMU_MSG` | 进流，进画面弹幕层 | 无容器。昵称 `onSurfaceVariant`，正文 `onSurface`，勋章内联在昵称前 |
| 自己的弹幕 | 同上，`isSelf` | 同上 | 昵称换 `primary`，其余相同 |
| 醒目留言 | `SUPER_CHAT_MESSAGE` | 进流，另进「醒目留言」屏 | 左侧 4dp 档位色竖条 + `surfaceContainerHigh` 底，两行：署名与金额一行，正文一段 |
| 醒目留言撤回 | `SUPER_CHAT_MESSAGE_DELETE` | 从两处一并移除 | 流内那条留下，正文加删除线 |
| 礼物 | `SEND_GIFT` | **丢弃**（owner 定，2026-08-23） | — |
| 上舰 | `GUARD_BUY` | 进流 | 图标 + `secondaryContainer` 底的一行 |
| 进场 | `INTERACT_WORD` | **丢弃**，见 §4 | — |
| 高能进场特效 | `ENTRY_EFFECT` | **丢弃**，见 §4 | — |
| 红包与天选 | `POPULARITY_RED_POCKET_*` | **丢弃**，见 §4 | — |
| 全站广播 | `NOTICE_MSG` | **丢弃**，见 §4 | — |
| 点赞 | `LIKE_INFO_V3_*` | 丢弃 | — |
| 高能榜人数 | `ONLINE_RANK_COUNT` | 更新计数 | 不入流 |
| 看过人数 | `WATCHED_CHANGE` | 更新控制条那一格 | 不入流，同现状 |
| 改标题 | `ROOM_CHANGE` | 更新主播行的标题 | 静默更新，不入流 |
| 开播与下播 | `LIVE` / `PREPARING` | 改页面状态，并入流一条系统行 | 居中一行 `onSurfaceVariant` |
| 超管警告与切断 | `WARNING` / `CUT_OFF` | 进流 | 居中一行，底色 `errorContainer` |
| 禁言 | `ROOM_BLOCK_MSG` | **仅当被禁言的是自己时**进流 | 同上 |
| 人气值 | 心跳回包 | 收着不显示 | 同现状 |

### 3.1 醒目留言

**档位色本地定，不用服务端给的两个色。** `notes/live.md` §8.1 记着服务端在消息里给
`background_color` 与 `background_bottom_color`。现有代码已经把它们否掉过一次，理由仍然成立：
那两个值是照白底设计的，深色主题下直接糊。

替代方案是一张本地档位表，只出**一个**色，画成行左侧 4dp 宽的竖条。取色的手法照 §1.2 的
`MentionLight` / `MentionDark`：色相取 B 站各档的色相保持不变，沿 HCT 的 tone 轴给明暗两套值。
**携带语义的是色相，承担可读性的是明度。** M3 对这类值给的路径也是这个：

> "Colors can stay completely static and forgo harmonization if their values are tied to literal
> sources, such as brand colors or real-world signage"
> —— `m3-material-mirror/pages/styles/color/advanced.md:164`

金额本身继续用 `tertiaryContainer` 加 `onTertiaryContainer` 的小块，这一处 M3 明确背书：

> "Tertiary roles are for smaller elements that need special emphasis but don't require immediate
> attention, such as a badge or notification."
> —— `pages/styles/color/roles.md:54`

**颜色不作为唯一线索。** 档位色竖条之外，金额数字本身就写在那儿，色觉障碍用户读金额即可。

> "To make selected items clear for everyone, don't rely on color as the only visual cue."
> —— `pages/components/lists.md:1014`

**正文用 `bodyMediumEmphasized`。** 同字号换 token，行高与布局都不动，这正是 15 档 Emphasized
的用法：

> "When used in components, emphasized type styles can communicate hierarchy or importance, such as
> an active or selected component, or an unread message."
> —— `pages/styles/typography.md:299`

**倒计时只在「醒目留言」那一屏显示，且是数字，不是进度条。** 流内那条会被滚走，不需要倒计时。
汇总屏那份显示剩余秒数，用 tabular figures：

> "Use tabular numbers to prevent layout shifting when values change, such as in a clock UI"
> —— `pages/styles/typography.md:676`

不用 `LinearProgressIndicator`。规范给的形态确实是 determinate，但一条持续跑动的进度条在直播间
里就是一处持续吸引视线的动画，而 usability 页自己写着 "use it sparingly since motion can be
distracting"（`pages/foundations/usability.md:82`）。

**进房补历史。** `getMessageList` 接口见 `notes/live.md` §8.2。进房拉一次，与长连接推来的合并，
按 `start_time` 排。PiliPlus 那份历史追加在尾部而推送插在头部，两个方向对不上，抄接口不抄这一处。

**本场早前的完整 SC 用 danmakus.com 补**（owner 定，2026-08-23）。官方 `getMessageList` 只给
还挂着的那几条；第三方归档站 ukamnads 能给**本场**从开播起的全部 SC，进行中的场次可查，接口
事实见 `notes/danmakus-com.md`。范围钉死在本场：**往期场次的浏览不进这个 app**（owner 原话
「历史记录不应该在我们 app 内」），channel 接口只用来把 roomId/uid 换成本场 liveId，场次列表
不呈现。它是第三方服务，与 SponsorBlock 同性质：关掉的含义包含「别去问它」；来源在界面上标注。

> **2026-08-24 修订（owner 定）：默认开。** 与 SponsorBlock 的默认关不一致，是明知代价后的
> 选择——进直播间即向站外服务器发出主播的 mid（不带任何 B 站凭据，也不含用户身份），那台
> 服务器因此知道有人在看这位主播。开关仍在隐私页，关掉一个请求都不发。
>
> **取数据要挑录制版本。** 一场有多个版本，本站官方那份在进行中的场次里通常是空的，数据在
> 贡献者的版本里（各版本的 liveId 只差几位十六进制，看起来像同一个 id 打错了）。按
> `rangeDanmakusCount` 取最多的那一份。查错版本会得出「进行中的场次没有数据、这个功能做不成」
> 这个结论，而它是错的。
>
> **danmakus 的消息没有 id**（2026-08-24 实测，见 `notes/danmakus-com.md`）。swagger 声明的
> `ct` 在真实响应里不下发，v3 的记录同样只有 `ts/type/actorId/payload`。所以与 B 站那侧合并
> 时按 `uId + sendDate 秒 + 正文` 去重，重复时保留带真 id 的那一份——只有它点得开、撤得掉。
> 两侧的时间不是同一个字段（B 站是 `start_time` 秒，danmakus 是自己收到的 `sendDate` 毫秒），
> 差一秒就漏一条；漏了的后果是「本场早前」里多出一条重复的，不是少了什么。

### 3.2 礼物

**不显示**（owner 定，2026-08-23）。流里只保留上舰与醒目留言两类付费消息。原方案的
按 `(uid, giftId)` 五秒窗口合并随之整段作废；`SEND_GIFT` 在 `emitCommand` 里照旧丢弃，
连解析都不加——高峰期它是消息量的大头，解析了再扔仍然白付一遍反序列化。

### 3.3 弹幕本身

三样在数据里、现在被丢掉的东西，一并补上（字段见 `notes/live.md` §9）：

- **粉丝勋章**：牌名加等级的一小块，内联在昵称前。底色与字色服务端给（`v2_medal_color_start` /
  `v2_medal_color_text`），这两个是照深色底设计的，与 SC 那两个色的情况不同，可以直接用。
  形态照 `LevelBadge` 那条：它标的是身份属性，不暗示“有新东西”，不落在 §4.2 否掉的那类徽章里。
- **表情弹幕**：整条一张图与正文夹图两种，走 `ui/components/InlineEmote.kt`。**上限是这一行的
  行高**，这条 §2.7c 已经踩过一次。
- **回复弹幕**：`extra.reply_mid` 非零时，正文前挂一个 `@昵称`。本项目没有可跳转的目标页，所以
  它取 `onSurfaceVariant` 且不可点，同 §2.7c 对话题标签的处理。

### 3.4 行的构造

一条消息是一个三槽位的列表行，**不是卡片**。

> "Think of a custom list as a container with three different slots: leading, content, and trailing."
> —— `pages/components/lists.md:263`

不用 Card 有两条依据：

> "Don't force content into cards when spacing, headlines, or dividers would create a simpler visual
> hierarchy" —— `pages/components/cards.md:219`
> "On smaller screens with the compact breakpoint, consider swapping cards for lists, which can
> display images and text in a more compact form." —— 同上 `:488`

代价记下来：三槽位要自己实现和维护（`lists.md:288`）。收益是所有类别共用一份骨架，
leading 放头像或类别图标，content 放正文，trailing 放金额或数量，各类别的差异收敛在槽位内容上。

**槽位位置在各类别之间保持一致**，否则整条流扫不动：

> "Place supporting visuals and primary text in the same position in each list item. Don't vary the
> position of elements within a list." —— `pages/components/lists.md:680`

**行之间只留间隔，不画分割线。**

> "Use gaps for contained lists. Gaps leverage expressive shape and containment tactics. Limit
> dividers to uncontained or complex lists, only when a stronger visual separation is necessary."
> —— `pages/components/lists.md:829`
> "List items with repetitive formats may not require an inset divider, in which using only the
> margin between items is acceptable." —— `pages/components/divider.md:168`

系统行（开播、下播、警告）上下各一条 full-width 分割线，这一处符合 divider 页对 full-width 的
定义：“separate larger sections of unrelated content”（`divider.md:121`）。

### 3.5 动效

**新消息进入不做动画。** 现有注释已经记了一次实测结论：直播消息密集时 `animateScrollToItem`
每来一条就被取消重来，永远走不完。逐行的进入动画是同一类问题，在每秒几十条的流上只会糊成抖动。

「醒目留言」那一屏是低频列表，可以做。取 spring token 而不是 tween，easing/duration 那套已经
停止维护：

> "In the expressive update, components and motion now use the motion physics system, which uses
> springs. ... The easing and duration system is still used for transitions ... but is no longer
> maintained." —— `pages/styles/motion/easing-and-duration.md:13`

单条消息属 "small components"，取 `spring.fast.spatial` 配 `spring.fast.effects`
（`pages/styles/motion/overview.md:143`）。

## 4. 产品边界

直播间里混着两类东西：主播与观众在说的，以及平台想让你看的。README 贡献一节的第三条把后者划在
项目范围之外。逐项表态如下，这几条正是最需要 owner 拍板的部分。

**进场提示（`INTERACT_WORD`）不做。** 它不携带任何人说的内容，只报告“有人来了”，作用是制造在场
感，让房间看上去比实际热闹。它还按用户等级与舰长等级分档决定显示得多显眼，也就是可见度可以花钱
买。这一条落在“打扰用户或争夺其注意力”里。

**不给开关。** 风格指南 §2.8 的判据是“设置只调整怎么做，不调整做不做”。给进场提示一个开关，等于
先把它做出来再让用户自己关掉，而它默认开着的那一刻就已经在做它被设计来做的事。

**高能进场特效（`ENTRY_EFFECT`）不做。** 它是一条盖在画面上的横幅动画，同一条边界，且更硬。

**红包与天选时刻（`POPULARITY_RED_POCKET_*`）不做。** 这是四条里最清楚的一条：它的机制是要求
用户留在房间里等开奖，直接以停留时长为目标。

**全站广播（`NOTICE_MSG`）不做。** 它推的是别的房间正在发生的事，形状等同于推荐流。

**带货与小黄车不做。** 同上，且落在“不动计费与授权”那一条附近。

**上舰做，礼物不做**（owner 定，2026-08-23）。上舰与醒目留言同类，是观众自己的、低频的表达；
礼物在高峰期是刷屏的主力，owner 选择整类不显示，而不是靠合并把它压下去。

**点赞计数不做。** 它是一个只增不减的数字，不携带任何人说了什么。

**全屏时不做醒目留言浮层。** PiliPlus 有一个固定在左下角的浮层（`notes/live.md` 记了它把展示时长
截到 10 秒）。本项目的全屏是为了看画面，往上面盖东西与那个目的相反。退出全屏就能看到完整的一屏。
这一条是取舍，不是边界，列在这里等拍板。

## 5. 与风格指南的一致性

逐条对过，没有冲突，有三处需要在实现时守住：

- **不出现中点。** 礼物行的“昵称 送出 小心心 ×12”用空格分段，不用 `·`。风格指南对这一条的理由
  在直播间里更成立：昵称是最先被截断的那一段。
- **文案是书面语。** 「送出」不写「刷了」，「开通了舰长」不写「上舰了」，下播写「主播已下播」。
- **不用 `Badge` 组件。** M3 对 badge 的定义把它绑在导航目的地上，默认取 error 配色
  （`pages/components/badges.md:187`），而 §4.2 单独否掉了注意力标记。金额、勋章、舰长等级都走
  自绘或容器色。

一处需要注意的规范事实：**M3 已经没有 banner 组件了。** 组件目录下没有 `banner.md`，术语表
（`pages/foundations/glossary.md:15`）留了词条但是全表唯一一个不带链接的组件词条。所以系统提示
只能落在 snackbar 或流内的行上。选流内的行，因为 snackbar 一次只能显示一条
（`pages/components/snackbar.md:77`），而开播、改标题、警告可能连着来。

## 6. 落地顺序

六步，每步能单独编译与验证。**编译是排他资源，这几步不要并行跑 Gradle。**

**第一步，认全 cmd。** 扩 `live/LiveDanmakuClient.kt` 的 `LiveMessage` 与 `emitCommand`，
新增上舰、系统消息两类（礼物不做，owner 定），弹幕补上勋章、表情、回复三组字段。这一步不动界面，
先在日志里核对字段。上舰的字段名没有参照实现，要真机抓一遍。

**第二步，统一流模型。** `ui/live/LiveRoomViewModel.kt`：把 `LiveChatLine` 换成一个
密封的 `LiveFeedItem`，醒目留言从 `superChats` 单列改为同时进流与进汇总列表。

**第三步，重画消息流。** 新建 `ui/live/LiveFeedRow.kt` 放三槽位骨架与各类别的行，
`ui/live/LiveRoomScreen.kt` 撤掉醒目留言横条，`ChatPane` 改为渲染 `LiveFeedItem`。
`SuperChatCard` 从横向卡片改为内联行。新增的文案进 `res/values/strings.xml`。

**第四步，醒目留言那一屏。** `data/LiveRepository.kt` 加 `getMessageList`，
`LiveRoomScreen.kt` 的 Tab 由两个改三个，新建汇总屏与倒计时。接上
`SUPER_CHAT_MESSAGE_DELETE`。

**第五步，档位色。** `ui/theme/Color.kt` 加档位色表（明暗两套），行左侧竖条接上去。这一步单独走，
因为色值需要 owner 看真机。

**第六步，中途开播与下播。** `LIVE` 与 `PREPARING` 改 `isLive`，进而改播放器状态；`ROOM_CHANGE`
改主播行的标题。这一步碰播放路径，单独放在最后。

## 7. 待拍板

| # | 决策 | 建议 |
|---|---|---|
| 1 | 进场提示做不做 | 不做，且不给开关 |
| 2 | 高能进场特效、红包天选、全站广播、带货 | 一律不做 |
| 3 | 礼物与上舰做不做 | **已定**（2026-08-23）：礼物不做，上舰做 |
| 4 | 醒目留言横条撤掉，改内联加独立一屏 | 撤 |
| 5 | Tab 标题带不带醒目留言数量 | 不带 |
| 6 | 档位色用本地表还是继续只用 `tertiaryContainer` | 用本地表，第五步单独落地 |
| 7 | 倒计时用数字还是进度条 | 数字，且只在汇总屏 |
| 8 | 全屏时的醒目留言浮层 | 不做 |
| 10 | 主播行要不要加粉丝数与关注按钮 | 本轮不动，另议 |
