# M3 Expressive 体验重整方案

日期：2026-09-13
依据：`m3-material-mirror/pages/`（styles/motion、styles/shape、styles/typography、components/*）、
`docs/ui-style-guide.md`、`DESIGN.md`。旧审计 `docs/m3-ux-audit.md` 已复核，其中 30 项里 24 项已修，
剩余项并入本文附录。

## 0. 总判断

骨架是合规的：主题、十档形状、双套字号、`NavDisplay` 四种转场、四档等待指示、破坏性操作的确认与
撤销，这些都对得上规范。粗糙感来自三层缺失，按对体感的影响排序：

1. **动效层几乎为零。** 全部 `ui/` 里 `animateItem`、`Crossfade`、`animateContentSize`、
   `LookaheadScope`、矢量动画的出现次数都是 0，`AnimatedContent` 一处，触感反馈一处。列表增删、
   首屏三态、内容显隐、多选态、看视频切听视频全是同帧硬切。这是「用起来粗糙」的主因。
2. **层级扁平。** 200 余处字号取样里 `bodySmall`/`bodyMedium`/`labelSmall` 占六成，`headline`
   两处，`Emphasized` 五处。没有一页有标题锚点，听视频页没有主动作，设置页是落在 `surface` 上的裸
   列表，`surfaceContainer` 系色阶只用了 27 处。
3. **反馈链断口。** 评论与私信发送失败即丢草稿，六处接口异常原文与风控码上屏，助理跑动中停不下来，
   进度条每 500ms 跳一格且不画缓冲。

字体本身不在范围内。

## 1. 动效

### 1.1 元素进出（机械，无需拍板）

| 位置 | 现状 | 改法 |
|---|---|---|
| 所有 `LazyColumn` 条目 | 删除、撤销、排除 UP 后整列瞬间跳位 | `PagedColumn.itemContent` 外套 `Modifier.animateItem()`；`FeedScreen`、`ToViewScreen`、`OfflineScreen`、`FavFoldersScreen` 四处直写的各加一行 |
| 首屏三态（转圈 / 错误 / 列表） | `when` 直接换子树 | 外套 `Crossfade`，spec 取 `motionScheme.defaultEffectsSpec()` |
| 内容显隐五处 | SponsorBlock 子行、空间页搜索框、动态展开、助理过程折叠、个人页三态 | `AnimatedVisibility` + `animateContentSize` |
| 多选态三处 | 顶栏、行底色、行尾勾选框同帧硬切 | 顶栏两态 `AnimatedContent`，底色 `animateColorAsState`，行尾槽 `AnimatedContent` |
| 播放器手势浮层 | `hint?.let` 直接进出 | `AnimatedVisibility(fadeIn/fadeOut)`，走 `fastEffectsSpec` |
| 字幕位移 | 控制条显隐时瞬移 64dp | `animateDpAsState`，走 `fastSpatialSpec` |
| 短缓冲转圈 | seek 后几十毫秒也闪一下 | 接 `rememberLoadingVisible` 的 200ms 门槛 |
| Tab 指示条三处 | 直播间、空间、消息页过半才跳 | 照 `VideoTabs` 按 `currentPageOffsetFraction` 插值 |

### 1.2 容器变换（需拍板）

**看视频 ↔ 听视频。** `VideoScreen.kt:611` 三种形态是三段 `return` 的独立子树，代码里已有
TODO 记着这属于 container transform。改法是三态合进一棵树，套 `LookaheadScope`，播放器容器
用 `animateBounds` 从画面位置落到唱片位置，其余内容做 `styles/motion/transitions.md` 要求的
clean fade（先淡出再淡入，不交叉）。

要定的一件事：持续存在的元素是**画面容器**还是**封面**。画面容器的好处是切换那一刻画面还在动，
从 16:9 缩成圆形唱片；封面的好处是听视频本来就不渲染画面，落点即终态。建议画面容器，
`setTrackTypeDisabled` 在动画结束再调。

**列表封面 → 播放器。** 从动态流、稍后再看点进播放页时封面飞到播放器位置。Navigation 3 的
`NavDisplay` 支持 `SharedTransitionScope`，但播放页在 `LazyMediaSource` 拿到流之前是黑底，
封面飞过去接的是黑框。放到 1.2 之后再做，先看听视频那条跑通后的观感。

### 1.3 自绘与矢量动画

M3 对这一类的指导在 `styles/shape.md`：形状变形应当响应交互，用来传达交互状态、进行中的动作、
环境变化；抽象形状放在图片裁切、头像遮罩这类装饰位；没有明确理由的异形只增加杂乱。图标动画
规范里没有正面条目。按这三条，能落地的有：

| 位置 | 做什么 | 依据 |
|---|---|---|
| 唱片页封面 | 遮罩用 `MaterialShapes` 的圆形，播放时缓慢自转（已有），暂停时 morph 到一个多边形（如 `Cookie9Sided`）再停转 | 「actions in progress」；alpha25 的 jar 里 `MaterialShapes` 与 `Morph` 已确认存在 |
| 播放 / 暂停 | 两个 path 之间插值，不换图标 | 同上 |
| 一键三连 | 赞、币、藏三格在进度环走满时依次由描边变填充，带一次 spatial 弹性 | 「interaction states」 |
| 底栏三格 | outline → filled 之间做 path 插值，替代当前的换图标 | 同上 |
| 拉刷新 | `PullToRefreshDefaults.LoadingIndicator` 已用，contained 形态按规范补容器 | `components/loading-indicator.md` |

实现路线：不引 `animation-graphics`（AVD 要每个图标手写 XML，改起来看不见结果）。图标形变用
`graphics-shapes`（material3 已传递依赖）的 `Morph` 或自己在 `Canvas` 里对 path 做插值；
封面遮罩直接 `MaterialShapes.*.toShape()`。建议先做唱片页与三连两处，看效果再定底栏。

### 1.4 触感

全应用只有 `CommentCopy` 一处 `performHapticFeedback`。补齐：长按加速、三连走满、锁定 / 解锁、
横划 seek 进入取消区、长按进多选、拉刷新过阈值、顺序 / 随机切换。类型按语义取
`LongPress` / `ToggleOn` / `ToggleOff` / `GestureThresholdActivate`。机械。

## 2. 结构与动线

### 2.1 顶栏（部分需拍板）

**子页面接滚动行为。** `Bars.kt` 的 `scrollBehavior` 参数从加进来起没有一个调用方传过，十处
子页面顶栏与内容之间没有边界。各 `Scaffold` 挂 `nestedScroll` 并传同一个 behavior。机械。

**「我的」与空间页换 `MediumFlexibleTopAppBar`。** 规范把 medium/large 标为不再推荐，替代品
是 flexible 变体，支持多行与图片。账号名 / UP 名成为页面标题，折叠后留小栏，头像与统计行
放在 flexible 区域。这是整个 app 唯一能拿到 `headline` 字号锚点的地方，也是排版层级问题的
根。需拍板：这两页头部现在是自己排的 `Column`，换过去是结构改动。

### 2.2 主动作与选择控件（需拍板）

- 听视频页播放键换 `FilledIconButton`，尺寸提一档。风格指南 §2.4 给每屏留的那个 tonal 名额
  在这一页是空的。
- 顺序 / 随机两处图标恒为 `Shuffle`、文字说当前状态，两个通道给相反信号。换 `ToggleButton`，
  图标随状态换。文案要定：按钮上写「随机」表示按下去变随机，还是写当前状态。
- 动作栏已是五格，`VideoTabs.kt:886`、`:1667` 的注释与指南 §2.3 仍按四格写，其中一句正是
  拿来反对第五格的。定五格为准并改文档，或把「听视频」移回 tonal 按钮位。

### 2.3 反馈链

| 位置 | 现状 | 改法 | 拍板 |
|---|---|---|---|
| 评论发送 | 发出即清空，失败写进列表页脚 | 成功才清空，失败就地一行可重试；补计数与 `ImeAction`；`OutlinedTextField` 换 `SearchField` 同形 | 否 |
| 私信发送 | 同上 | 同上 | 否 |
| 助理跑动中 | 发送键可按，再发静默取消上一轮 | 跑动时发送键变停止 | 是 |
| 接口异常上屏 | 消息、空间、个人、直播、设置、AgentLoop 六处 `"$message($code)"` | 归成三档：没连上网络 / 登录已过期 / 服务拒绝，原文只进 `BiliLog` | 措辞 |
| 进度条 | 500ms 轮询，无缓冲条 | 控件可见时 200ms；底槽与已播之间画 `bufferedPosition` | 否 |
| 内嵌播放 | 画质与字幕只剩图标 | 已定规则（2026-09-13）：控制条量得下才给档名，量不下整条不给，不截断，截断会让 1080P60 和 1080P 高码率看起来一样 | 否 |
| 三连提示 | 2.5s | 对齐 `SkipToast` 的 4s | 否 |
| 简介正文 | 纯 `Text`，链接、时间戳、@ 全是死字 | 换 `BiliRichText`，抬到 `bodyMedium`/`onSurface` | 否 |

### 2.4 直播间（需拍板，`live-room-redesign.md` 已有定案的按定案）

- 输入栏提到 `HorizontalPager` 之外常驻。
- 断线与恢复各向流里追加一条系统行。
- 醒目留言改为参与布局的恒定高度栏。
- `feed` 换 `SnapshotStateList` 就地追加，弹幕高峰不再整页重组。

### 2.5 其他

- 排除名单「清空」加确认；取关对话框标题改成具体问句（现在标题、确认、取消三处都是「取消」）。
- 空间页先渲染投稿与动态，合集探测回来再追加。
- 私信会话接 `ScrollFollow`，键盘不再顶掉最新一条。
- 播放队列改用 `EpisodeList` 那份居中逻辑，key 换 `currentIndex`。
- 平板：动态流两栏加分隔与行长上限；评论列表 `widthIn(max)`；弹幕层裁到画面矩形。
- 同一张图两次出现时 `indexOf` 命中首张，改 `forEachIndexed`。

## 3. 排版与布局

### 3.1 字号层级（需拍板）

| 元素 | 现状 | 建议 |
|---|---|---|
| 列表条目标题 | `bodyMedium` 14sp 两行 | `bodyLarge` 16sp 两行；M3 list headline 即 16sp。行高随之从 22 到 24，一条视频行高约多 4dp |
| 页面标题 | 无 | 由 2.1 的 flexible 顶栏提供，`headlineSmall` |
| 分节标题 | `titleMedium` | `titleMediumEmphasized`，与正文拉开字重 |
| 选中 / 当前 / 未读 | 换色 | 换色加 `Emphasized` 档，规范点名这是 emphasized 的用途 |
| 助理正文 | `bodyMedium` 14/22，块间距 4dp | 行高 24，块间距 8dp；全应用最长的中文段落用了最紧的行距 |
| 简介正文 | `bodySmall` | `bodyMedium`，见 2.3 |

列表标题那一条是密度决定，动态流一屏少显示约半条。

### 3.2 容器与形状（需拍板）

- 设置页每组装进 `Surface(surfaceContainer, shapes.large)`，与播放队列、动态卡片的 contained
  风格一致。
- 九处裸 `DropdownMenu` 统一成动态流那套 expressive 形状，包成共享组件。
- 搜索框接 focus 状态换一档容器色。
- 助理气泡上限 280dp 在 320dp 宽的屏上几乎铺满，改按窗口宽度比例。
- 平板动态流的次区换 `surfaceContainer` 或加 `VerticalDivider`。

### 3.3 文案与一致性（机械）

- 助理答案段首残留标点（「,小阳侃把…」），当前代码仍如此。渲染时去掉文本块首标点，或让
  `submit_answer` 在句边界切块。后者更对，前者更便宜。
- 助理正文标点半角全角混排，渲染前归一。
- 空签名两页两种处理，且空间页那句是口语。统一为不画。
- 界面文案写成 Kotlin 字面量的五处进 `strings.xml`。
- 裸 dp 字面量归 `Spacing` 档：动态流、直播、播放器、听视频、搜索、私信共约二十处。

## 4. 四处结构性问题

### 4.1 播放页：「整栏不滚、队列占满剩余」这个前提本身是 workaround 的来源

现状由它派生出五件互相牵制的事：简介那一边不许收画面（`canCollapsePlayer` 按
`settledPage` 分方向）；简介正文被逐出到面板；找相关 sheet 的高度靠 `onGloballyPositioned`
一次性抓投币行的窗口坐标；队列在自己的视口里滚并自抄一份居中逻辑；评论区下拉刷新与画面
展开态耦合。`Dimens.EmbeddedQueueHeight` 两个常量已无人引用。

**两版实现都被否，代码已回退到原布局（2026-09-13）。** 第一版：队列做成常驻底部 sheet，
简介列自己滚，找相关在列尾就地展开 —— 否决理由是 sheet 角色混杂，且找相关不生卡片时简介
tab 空出大半屏。第二版：页头提到 tab 栏上面并随滚动先于画面收起，两个 tab 各是一个长列表
（切集清单 / 评论），找相关开 modal —— 真机上效果不好，同样否决。播放页面板该放什么仍待
从头讨论，这一节下面保留最初提案作为记录，不作为方向。

原建议：**队列做成标准底部 sheet**，和听视频、全屏两种形态对齐（风格指南 §4.3b 的表里
那两处本来就是 sheet 与侧面板），三种形态收成两种。

- 现有的 `BottomSheetScaffold` 就是这个 sheet：把手常驻，peek 露出「正在播 / 接下来」一行，
  拉起是完整队列。头部带来源标题、顺序 / 随机、缓存、找相关；找相关的结果作为同一个 sheet
  顶部的一节，不再是第二个容器。sheet 的高度规则由它自己定（窗口高减画面高，画面高本页
  自己就有），不再从子组件回报坐标。
- 简介页回到普通的 `LazyColumn`：标题、计数行、UP 行、动作栏、分 P chip、简介正文就地
  展开（`animateContentSize`）、标签。正文回到页内之后，控件和效果挨着，§4.3d 那段争论不
  再存在。
- 画面在两个 tab 上一样收，`canCollapsePlayer` 只剩 `!playerPinned`。快捷播放条不动。
- 平板双栏先维持 sheet；队列进右栏是后一步。

代价是队列默认只露一行，DESIGN 2.4b 写的「放在官方放相关推荐的位置」从整块变成常驻把手，
这是产品形状的改动，要拍板。不换前提的替代方案是把队列行并进简介的 `LazyColumn` 当条目，
但「前后各 25 条并居中」需要自己的视口，并进去之后要么页头随之滚走，要么放弃前 25 条，
两者都比 sheet 差。

依据：`components/bottom-sheets.md` 标准 sheet 的定义是与主内容并存的补充内容；
`styles/motion/transitions.md` 的 container transform（sheet 拉起）。

### 4.2 动态：被转发的那条整体不可点，渲染器分叉

`DynamicCardView(nested = true)` 只有内部的视频 / 专栏块可点，卡片本身没有落点；
`DynamicAction.OpenDynamic` 已存在且导航层已接（`MainActivity.kt:2088`），缺的只是
嵌套层在作者行与正文上挂 `OpenDynamic(forwarded.id)`。规则与外层一致：正文与作者行进
这条动态，内容块进内容，两者不叠（`components/cards.md`：动作不放在可动作的表面上）。

「封面 + 标题 + 一行元信息」的横排卡片现在至少四份私有实现：`SideBySideCard`
（动态）、`TraceCard`（助理）、`ArticleLinkCard`（专栏）、`CompactVideoRow`（队列）。
收成 `components/` 里一个 `MediaTile`，形状、内边距、点击态只写一次；动态卡片的
`BlockStyle` 作为它的一档参数。

### 4.3 楼中楼：预览与展开是两个组件，切换即重排

`SubReplyPreviewRow`（无头像、名字接正文、两行截断）与 `SubReplyRow`（头像、全文、
动作行）是两棵子树，点展开时整组替换，头像列从无到有把正文整体右推。「楼中楼不给头像」
在指南里已被推翻，预览层无头像是那次决定的残留。

改成一个 `SubReplyRow(form = Preview | Full)`：头像槽两态都在（预览也画头像），正文
`maxLines` 从 2 到不限并套 `animateContentSize`，动作行 `AnimatedVisibility`，条目以
`rpid` 为 key 保持身份。展开后位置不变，只有高度长出来。依据：`components/lists.md`
expressive 的 expand 交互。

### 4.4 空间页搜索：入口与效果隔着整个页头

顶栏图标只在投稿 tab 出现（切 tab 时顶栏动作集合变化），点下去在内容区排序行下面冒出
一个输入框并强制弹键盘，收起即清空并重拉；展开态存在 screen 里，转屏即丢。

建议去掉顶栏图标，输入框常驻在投稿 tab 的排序行上方，随页头一起收起。它是这一页的
一等功能（DESIGN 2.4 明写投稿支持空间内搜索），不该藏在图标后面；关键词与展开态都不再
需要。若要保留图标入口，M3 的做法是顶栏本身变形为搜索栏（`components/search.md` 的
docked search view），而不是在内容区另开一个框。

## 5. 附录：旧审计剩余项

- 播放器五组手势零提示：首次全屏一次性 rich tooltip。
- 图片查看器双击缩放无提示。
- 听视频页三处 `outline` 当文字色。
- LLM 冒烟测试与更新检查两条原始错误文案。
- 空间页耳机按钮无可见文字。
- 深色模式三档、手动种子色，需新开「外观」一节。
- 又一批 48dp 以下可点区域六处；六个未引用 import。

## 5b. 进度（2026-09-13）

第一、二批与第三批的大部分已实现并通过编译与单元测试，真机验收未跑。已落地：1.1 全部、1.4、
2.1 两条、2.2 的播放键与顺序 / 随机、2.3 除简介正文富文本之外的全部、2.4 除弹幕列表
`SnapshotStateList` 之外的全部、2.5 除平板三条与队列居中之外的全部、3.1 除页面标题外的全部、
3.2 除菜单统一与平板次区之外的全部、3.3 全部、4.2 的可点（`MediaTile` 收拢未做）、4.3、4.4、
附录里的三处 `outline`、触摸目标、未引用 import。未做：1.2、1.3、4.1（待重新讨论）、
简介正文富文本、菜单统一、平板三条、深色模式与种子色、手势引导、图片查看器提示、
LLM 冒烟测试与更新检查的错误文案（那一行有意保留原文）。

## 6. 执行顺序

四批，每批独立提交，批内按文件归属并行：

1. **动效基础**：1.1 全部、1.4、2.3 里不需拍板的行、附录的机械项。纯机械，可以直接开工。
2. **反馈链与直播间**：2.3 其余、2.4、2.5、4.2、4.3、4.4。
3. **排版与容器**：2.1、3.1、3.2、3.3。
4. **播放页重排、容器变换与自绘动画**：4.1、1.2、1.3。放最后是因为它们依赖前三批把结构理顺，且观感要在真机上看。

## 7. 需要拍板的清单

1. 「我的」与空间页换 `MediumFlexibleTopAppBar`。
2. 列表条目标题 14sp → 16sp。
3. 听视频转场的持续元素取画面容器。
4. 动作栏定五格并改文档。
5. 顺序 / 随机的按钮文案：写动作还是写状态。
6. 助理段首标点在切块处理还是渲染处理。
7. 错误文案三档的措辞。
8. 竖屏视频的内嵌容器要不要跟着画面比例变高（现在写死 16:9，竖屏视频是黑底里一条窄画面）。
9. 直播间三条（输入栏常驻、断线系统行、醒目留言常驻栏）。
10. 设置页 contained 分组。
11. 自绘动画先做唱片页与三连两处。
12. 播放页面板的内容与形状（两版尝试均已回退，见 4.1）。
13. 空间页搜索框常驻，去掉顶栏图标。
