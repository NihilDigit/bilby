# Bilby 界面风格指南

写给往这个仓库里加界面的人。只写**已经落地的判断**：token 取什么值、组件选哪个、多端窗口怎么排、动效与无障碍怎么接，以及本项目故意偏离 M3 的地方和理由。M3 通用规范不复述，需要时查阅本地镜像 `m3-material-mirror/` 或 [m3.material.io](https://m3.material.io)。

> **优先级：解析出来的构件（`javap`） > 本地文档镜像 / `rtx` > 本文档。**
>
> 某个符号在当前版本里存不存在、签名是什么，以 Gradle 缓存里的 aar/jar 字节码为准（见 §0）；界面与本文档不一致时，先确认是不是新改动推翻了旧结论，并把本文档同步改掉。

---

## 0. M3 Expressive 与双端统一编译基线

Android 与桌面端已统一到同一代 Compose 与 Material 3 API 表面（移除旧 `M3Compat` 分支）：

- **Android**：`androidx.compose:compose-bom-beta:2026.11.00`，解析为 `ui/foundation 1.13.0-rc01` + **material3 `1.5.0-rc01`** + `navigation3 1.2.0` + `adaptive 1.3.0`。
- **Desktop（Windows x64 / ARM64）**：**Compose Multiplatform `1.13.0-rc01`**（Skiko `0.156.1`），同样解析为 **material3 `1.5.0-rc01`** + `navigation3 1.2.0` + `adaptive 1.3.0`。
- **编译器稳定性**：`compose_stability.conf` 为不可变领域模型、`java.time.*`、`kotlinx.coroutines.flow.*`、`StringResource` 与只读 `List`/`Set`/`Map` 快照提供稳定标记。

`commonMain` 里的每一处 UI 代码都必须同时通过 `:shared:compileAndroidMain` 与 `:shared:compileKotlinDesktop`。

### 核心 Expressive API 依赖表

| API | 用途与落点 | 双端兼容与动效注意 |
|---|---|---|
| `MaterialExpressiveTheme` | `Theme.kt`（`BilbyTheme`、`BilbyNightSanctuaryTheme`、`PlayerTheme`） | 置上 `LocalUsingExpressiveTheme` 并注入 `MotionScheme` |
| `MotionScheme.expressive()` / `standard()` | 触控端用 `expressive()`，桌面端（`platformUsesStandardMotion`）与播放器内层用 `standard()` | 桌面入口配 `AppMotionScale` 跟随系统「减弱动效」 |
| `Shapes` 十档圆角刻度 | `Shape.kt`（含 `largeIncreased` 20dp、`extraLargeIncreased` 32dp、`extraExtraLarge` 48dp） | 收回主题槽，组件侧不写裸圆角字面量 |
| `Typography` 15 档 `*Emphasized` | `Type.kt` 中文层级字重表（`SemiBold` / `Medium`）与 `.tabular` 等宽数字扩展 | 必须与基线 15 档同步套用中文行高与零字距 |
| `ButtonGroup` + `ToggleButton` | 2–5 项单选切换（`SortRow`、`CommentSection`、播放/听视频/直播分栏、个人页四段动作条、设置选项） | 配 `ButtonGroupDefaults.connected*ButtonShapes()` |
| `SplitButtonLayout` | 「听全部」主动作 + 队列加入选项菜单（`CollectionScreen`、`OfflineScreen`、`SpaceScreen`） | `TonalLeadingButton` + `TonalTrailingButton` |
| `HorizontalFloatingToolbar` | 多选批量操作底栏（历史/稍后看/离线/收藏夹）、`ArticleScreen` 阅读工具条、`DraftQueueBar` | 居底悬浮，避免挤占顶栏 |
| `HorizontalMultiBrowseCarousel` | `BilbyCarousel` / `ProfileCarousel`（个人页历史、稍后看、收藏夹、离线书架） | `rememberReducedMotion()` 时退回 `HorizontalUncontainedCarousel` |
| `LoadingIndicator` | `States.kt`（`FullScreenLoading`、`LoadingSpinner`、`InlineProgress`） | 减弱动效下退回静止 `StaticLoadingToken`（`Cookie9Sided`） |
| `PullToRefreshBox` + `PullToRefreshDefaults.LoadingIndicator` | `RefreshBox` 与评论区下拉刷新 | 仅触控/触控笔启用手势，与手势共享同一个 `PullToRefreshState` |
| `SegmentedListItem` + `ListItemDefaults.segmented*` | `SettingsRows.kt`、内联播放队列（`EpisodeList`）、`LiveNowSheet`、助理编号引用卡 | 双端 API 一致，使用 `SegmentedGap` |

### API 探针与字节码核验

- 探针位于 `shared/src/androidHostTest/kotlin/dev/bilby/ui/M3ApiProbe.kt`（放 test source set，绝不放 `main`），升级依赖后跑 `:shared:compileAndroidHostTest` 与 `:shared:compileKotlinDesktop`。
- 不要凭记忆猜签名：解开缓存里的 `classes.jar` 用 `javap` 查真实方法签名与实验注解。

---

## 1. 设计 Token

### 1.1 颜色、纯黑阶梯与夜间静息主题（`ui/theme/Color.kt`、`Theme.kt`）

- **基线色板由种子色 `#4A5C92` 按 `spec_version="2021"`（Tonal Spot）生成**，与 Android 启动窗口背景（浅色 `#FAF8FF`、深色 `#121318`，见 `res/values/colors.xml`）严格同源。
- **动态取色与内置传统色板**：Android 12+ 默认开启壁纸动态取色，也可在「设置 → 外观」切换内置传统配色（Bilby、紅、山吹、若竹、浅葱、桔梗、萱草、萌黄、牡丹）。
- **容器色阶规则**：
  - 容器填充一律使用 `surfaceContainerLowest` ～ `surfaceContainerHighest` 族，**严禁用 `surfaceVariant` 当容器底色**（它是给低强调文字色 `onSurfaceVariant` 配对留的，深色下当底色会突兀发亮）。
  - 层次靠 `surfaceContainer*` 的色阶差表达，不用 `tonalElevation` 染色阴影，**全应用零分割线**（见 §2.3c）。
  - 不要用 `color.copy(alpha = 0.15f)` 兑容器底色；徽章与提示块一律成对取 `primaryContainer` / `onPrimaryContainer`、`secondaryContainer` / `onSecondaryContainer` 等角色（直播间醒目留言与大航海三档走 `FixedColors.superChatPalette` / `guardPalette` 的 HCT 成对色阶）。
- **Oklab 纯黑容器阶梯（`ColorScheme.toPureBlack()`）**：
  - 开启「纯黑背景」时，`background` / `surface` / `surfaceContainerLowest` 压至 `#000000`。
  - 四档容器色（`Low` / `Container` / `High` / `Highest`）在 Oklab 空间插值重排，保留原色板色相并拉开均匀明度跨度，避免简单槽位顺移导致 `surfaceContainerLow` 陷进纯黑消失。
- **夜间静息专属主题（`BilbyNightSanctuaryTheme` / `NightSanctuaryColorScheme`）**：
  - `NightModeScreen` 固定包裹在 `BilbyNightSanctuaryTheme` 中，与日间外观设置解耦：即便用户白天固定使用浅色主题，进入夜间模式后始终呈现低唤醒深板岩暗房配色（`surface = #0C0E14`、`surfaceContainer = #161A24`、`surfaceContainerHigh = #1F2432`、暖石板蓝 `primary = #B8C4E8`、`onSurface = #DCE1EE`）。
- **播放器局部主题（`PlayerTheme`）**：画面与黑底之上固定取外层配色的深色方案，且动效切回 `MotionScheme.standard()`，避免控件显隐在视频画面上回弹抢戏。

### 1.2 不随主题反色的固定色（`FixedColors`）

判据只有一条：**这个颜色是画给机器识别的，还是压在一张不可控的媒体画面上的**。

| Token | 用途 | 固定理由 |
|---|---|---|
| `QrBackground` (`#FFFFFF`) | 登录二维码白底衬垫 | 扫码器要求深码块配浅底加 4 模块静区，随深色反色会直接扫不出（§4.1） |
| `ScrimOnMedia` (`0x8C000000`) | 封面时长角标、进度条底槽 | 0.55 遮罩保证纯白封面上白字对比度仍 > 4.5:1 |
| `ScrimOnLyrics` (`0xB8000000`) | 听视频歌词页背景遮罩 | 整屏多行歌词对花封面的压暗需求高于角落角标，兼容无模糊的旧系统 |
| `VinylBody` / `VinylGroove` / `VinylHole` | 听视频黑胶唱盘 | 唱盘是实物写照，同心纹路承担旋转可见性 |
| `OnMedia` (`#FFFFFF`) / `PlayerControlScrim` | 播放画面与封面上的图标文字及渐变底 | 全屏画面即背景，不存在 `surface` |
| `MentionLight` / `MentionDark` | 正文 `@提及`、站内链接、可跳时间戳 | 跨动态取色保持稳定的站内蓝语义，按深浅底分取 HCT tone 45 / 65 保证对比度 |
| `levelBackground(level)` | `LevelBadge` 等级底色 | B 站固定身份色标 |

### 1.3 中文 Expressive 排版与等宽数字（`ui/theme/Type.kt`）

不动 M3 基线字号（避免破坏组件高度与最小宽度推算），针对中文与数字做四项定制：

1. **中文零字距与小字号行高补偿**：正文与标题字距归零（仅 `label*` 保留正字距承载数字与短英文）；`bodyMedium`（14/22）、`bodySmall`（12/18）、`titleSmall`（14/22）各加 2sp 行高；全表关闭 `includeFontPadding` 并设 `LineHeightStyle.Alignment.Center`，消除图标与汉字同排时的基线偏高。
2. **激活 15 档 `*Emphasized` 层级字重**：
   - `display*Emphasized`、`headline*Emphasized`、`title*Emphasized`、`label*Emphasized` 取 `FontWeight.SemiBold`（600）；
   - `body*Emphasized` 取 `FontWeight.Medium`（500），既让列表主标题压住次级 `onSurfaceVariant` 元信息，又避免 CJK 合成粗体糊笔。
   - 典型锚点：专栏标题用 `headlineMediumEmphasized`（28/36）；订阅页顶栏标题、空间页 UP 名、个人页昵称、听视频曲目名、扫码欢迎卡标题用 `headlineSmallEmphasized`（24/32）；播放页主标题用 `titleLargeEmphasized`（22/28）；二级面板标题、分组卡头与空间合集卡标题用 `titleMediumEmphasized`（16/24）；视频行与网格卡标题统一用 `bodyLargeEmphasized`（16/24）；评论昵称用 `labelLargeEmphasized`。
3. **等宽数字扩展 `TextStyle.tabular`（`fontFeatureSettings = "tnum"`）**：
   - 凡是封面时长、播放/弹幕/点赞计数、`X / N` 队列位置、BV/AV/UID 标识胶囊、硬币流水、全屏时钟与电量百分比、倒计时读数，一律追加 `.tabular`，杜绝跳字与多行数字对不齐。
4. **长文段落行高按篇幅单独拉宽**：不污染全局 `Typography`，评论正文取 14/24sp、动态正文取 16/26sp、专栏正文取 16/28sp。

### 1.3b 封面比例、圆角与防盗链入口（`ui/components/Media.kt`）

- **封面统一 16:10，播放画面容器 16:9**。B 站封面原图为 16:10，裁成 16:9 会切掉封面顶部的标题字。
- 列表横排封面圆角 `CoverCornerRadius = 10.dp`，宽屏纵向网格卡封面圆角 `Dimens.CoverCornerGrid = 14.dp`。
- **所有 B 站图片（封面、头像、动态与评论配图、专栏插图、行内表情、空间头图）必须走 `ui/components/Media.kt`**（`BiliAsyncImage` / `VideoCover` / `ListCover` / `SquareCover` / `Avatar`）：统一补齐防盗链 `Referer` 并把 `http://` 与 `//` 协议相对地址规范化为 `https://`。手写裸 `AsyncImage` 必出 403 裂图。
- **头像一律走 `Avatar`（自带圆形裁切与 `ContentScale.Crop`）**，按语义取五档固定尺寸：`AvatarNested` 24dp（楼中楼）、`AvatarRow` 36dp（列表行/评论/作者栏）、`AvatarStack` 48dp（首页常访/直播环）、`AvatarHeader` 56dp、`AvatarHero` 72dp（空间头图与个人页身份卡）。

### 1.4 形状刻度与悬停微交互（`ui/theme/Shape.kt`、`Motion.kt`）

- 圆角按容器信息密度取 M3 十档刻度：`extraSmall` 4dp（角标、ID 胶囊）、`small` 8dp（紧凑缩略图、内嵌子块）、`medium` 12dp（代码块、引用块、动态内层附件块）、`large` 16dp（桌面内容岛、模态侧边面板、楼中楼块、分组摘要头卡、纵向网格卡悬停底）、`largeIncreased` 20dp（动态外卡、侧栏 `panelCard`、胶囊多行输入框、助理回答卡）、`extraLarge` 28dp（底部面板、评论输入浮层顶角、个人页身份卡、轮播卡片、扫码登录卡）、`extraExtraLarge` 48dp。
- 嵌套圆角遵守 optical roundness（外圆角 − 内边距 ≈ 内圆角，最小保底 `4.dp`）。
- **`Modifier.interactiveCard(shape, baseColor, hoverColor, enableScale)`**：在 `PointerEventPass.Final` 被动监听指针进入/离开与主键按下，**绝不消费事件**。桌面悬停平滑过渡底色，按下提供 `0.985f` 空间弹簧微缩（系统开启「减弱动效」时自动关闭缩放），与外层 `ContextMenuBox` 及内层 `clickable` / `combinedClickable` 无缝共存。

### 1.5 间距与元信息分隔符（`ui/theme/Dimens.kt`）

- 间距按语义取六档：`Hair 4.dp`、`Tight 8.dp`、`Cozy 12.dp`、`Comfortable 16.dp`、`Loose 24.dp`、`Spacious 32.dp`。除描边与固定组件几何外不写裸 `dp`。
- **严禁用中点 `·` 或项目符号 `•` 连接元信息**。一律使用 `MetaSeparator = "  "`（两个空格，定义于 `ui/components/VideoRow.kt`）：末段文字截断或窄屏折行时不会留下悬空的孤点。队列来源前缀（`合集  …`、`收藏夹  …`、`夜间模式  …`）同样遵守此规矩。

---

## 2. 组件选型与核心界面形态

### 2.1 排序、筛选、分裂按钮与浮动工具条

- **视图/模式切换**（固定 2–5 项）：使用 `ButtonGroup` +相连的 `ToggleButton` / `FilledTonalToggleButton`（配 `ButtonGroupDefaults.connected*ButtonShapes()` 与 `ConnectedSpaceBetween`，不用已废弃的 M2 `SegmentedButton`）。
- **排序选择**：使用 `SortRow`；选项 ≤ 3 档且调用处传 `preferButtonGroup = true` 时直接展开为 1-tap `ButtonGroup`，超过 3 档或在紧凑顶栏内收成 `SortMenu`。
- **「听全部」复合入口**：`CollectionScreen`、`OfflineScreen`、`SpaceScreen` 使用 `SplitButtonLayout`（`TonalLeadingButton` 直接启动听全部，`TonalTrailingButton` 展开加入队列选项菜单）。
- **二级合集/列表页统一摘要控制卡**：`CollectionScreen`、`FavFolderScreen`、`ToViewScreen`、`OfflineScreen` 顶部统一使用 `surfaceContainer` + `shapes.large` 头卡，将 `.tabular` 容量/条数统计、操作钮与排序收拢在同一容器内。
- **底部浮动工具条（`HorizontalFloatingToolbar`）**：用于历史/稍后再看/离线/收藏夹多选批量操作底栏、`ArticleScreen` 阅读底栏（大纲/回顶/浏览器打开）与 `DraftQueueBar`。多选进入后顶栏只保留退出与计数，批量动作统一落到底部拇指区。
- **动态横向筛选带**：分 P 条、视频标签（`TagToken`）、夜间模式 UP 主筛选（`NightUpFilterBar`）统一使用无描边 `CircleShape` tonal 胶囊。

### 2.2 Tabs 分工

- **Primary Tabs**：页面顶栏之下的主内容切换（如空间页「投稿 / 动态 / 合集」），使用 `PrimaryTabRow` / `PrimaryScrollableTabRow` 且关掉默认底线（`divider = {}`）。
- **Secondary Tabs**：页面局部区域内的平级分页（如播放页「简介 / 评论」），使用 `SecondaryTabRow`，指示条通过 `currentPage + currentPageOffsetFraction` 与 `HorizontalPager` 连续跟手插值。

### 2.3 播放页五格分段动作栏（`ActionButtonsRow`）

- 播放页 `UpRow`（包在 `surfaceContainer` + `shapes.large` 容器卡内，与上下区块严格对齐 `16.dp` 外边距）下方为五格等宽动作栏：**点赞（长按 1500ms 触发一键三连进度填充与回弹） / 投币 / 收藏 / 稍后看 / 听视频**。
- 选中态切换实心图标与强调容器色，计数统一用 `labelSmallEmphasized.tabular`，点击与三连完成带空间弹簧缩放反馈。
- 点赞、投币、收藏、稍后再看全部采用**本地乐观更新**，绝不因写操作重拉整页详情导致数字二次闪烁。

### 2.3b 视频条目体系、无描边三层连发叠卡与分段列表项

1. **窄屏横排 `VideoRow` vs 宽屏纵向 `VideoGridCard`**：
   - 单列窄屏（`Compact` 或窄分栏）渲染拇指友好的横排 `VideoRow`（16:10 封面 `128..176dp`、`bodyLargeEmphasized` 两行标题、发布时间 + UP 名单行、`StatRow` 图标化计数）。
   - 多列宽屏（`AdaptiveVideoGridContent` / `rememberVideoGridColumnCount` 下的 `Medium+`）按「设置 → 外观 → 每行视频数」（默认 5，3–7 可调）结合显示器全宽比例折算列数并渲染纵向 16:10 `VideoGridCard`（`14.dp` 封面圆角、`minLines = 2` 固定双行标题对齐同行基线、底栏 `20.dp` 头像 + 可点 UP 名 + `MetaSeparator` + 时间 + `StatRow` + `interactiveCard` 悬停底色）。
   - 窄屏行尾保留 `⋮` 溢出按钮；宽屏隐藏 `⋮`，统一由右键/长按 `ContextMenuBox` 弹出 `VideoCommonMenuItems`。
2. **有界队列与编号引用统一走 `SegmentedListItem`**：
   - 播放队列（`EpisodeList`）、`LiveNowSheet`、设置子组（`SettingsGroup`）与搜索助理出处列表（`AgentAnswerView`）统一采用 `SegmentedListItem`（`surfaceContainer`，项间 `SegmentedGap`），当前播放项以 `secondaryContainer` 高亮配 `bodyMediumEmphasized` 标题。
3. **订阅流无描边三层色阶连发叠卡（`BurstStackedItem`）**：
   - 同一 UP 主连续投稿折叠为三层纯色阶叠卡：保持与普通视频行/卡片一致的紧凑高度不另占底部横条，以 `surfaceContainerLow` / `surfaceContainerHigh` / `surfaceContainerHighest` 轻错位叠放（零描边），封面角标标明「连发 N 条」（保留充电角标），点击直接就地展开，展开后末尾配同色阶收起卡片/条。「上次看到这里」分界条沿用满宽 `surfaceContainer` `shapes.medium` 提示条配主色向上箭头。
4. **层叠文件夹封面（空间合集与收藏夹）**：
   - `CollectionsTab` 与 `FavFolderCover` 在 16:10 封面上方叠出阶梯状 `surfaceContainerHigh` 文件夹唇边，右下角配 `.tabular` 条数胶囊。

### 2.3c 分区原则：Containment 优先，全项目零分割线

全项目不使用 `HorizontalDivider`、`VerticalDivider`、Tab 默认底线或引用块左侧竖条。视觉分区只靠三样手段：

1. **留白**：重复版式的列表项（评论主楼、视频行、消息行）靠间距和行首头像/封面自然分界。
2. **容器色阶（Containment）**：按嵌套深度逐级递进（例如侧栏 `SidePanel.panelCard()` 用 `surfaceContainer` + `shapes.largeIncreased` 20dp，内部子块升至 `surfaceContainerHigh`）。
3. **滚动触发底色**：子页面 `BilbyTopBar` 统一接 `TopAppBarDefaults.pinnedScrollBehavior()`，未滚时与页面同色，滚过首行后切换容器色形成无痕边界。

### 2.4 输入框与零重排评论输入浮层

- **搜索框（`SearchField`）**：对话式搜索页固定在屏幕底部（`surfaceContainerHigh` + `largeIncreased`、无描边、带一键清空与 `ImeAction.Search`），宽屏空闲态居中展示编辑感 Search Hero；助理回合（`AgentTurnView`）装入 `surfaceContainer` `largeIncreased` 卡片，检索过程折叠为紧凑 `surfaceContainerHigh`「已检索 N 步」胶囊。空间内搜索、收藏夹内搜索则作为顶栏折叠图标展开复用 `SearchField`。
- **多场景胶囊输入（`PillInputField`）**：私信、直播间弹幕、评论编辑器共用同一套填充胶囊语言（聚焦升至 `surfaceContainerHighest`，无字时收起发送键，发送失败就地保留草稿并在输入框上方给出错误与重试）。
- **评论区触发条与独立模态浮层（`CommentPromptBar` + `CommentComposerDialog`）**：
  - 列表底部常驻 `CircleShape` `surfaceContainerHigh` 胶囊触发条（`CommentPromptBar`），**不订阅 `WindowInsets.ime`**，软键盘升降期间底层几百条富文本评论零重新测量。
  - 点击后打开独立窗口 `CommentComposerDialog`（`surfaceContainerLow` 底色、`shapes.extraLarge` 28dp 顶角、`widthIn(max = 640.dp)`），由 `SettledKeyboardHeightTracker` 1:1 锁定软键盘与表情面板高度，切换无跳闪；草稿按回复对象 `rpid` 独立保存。

### 2.5 顶栏、空间头图 Hero 与个人页书架

- **常规页面**：使用紧凑单行 `BilbyTopBar`（路牌定位，配 `pinnedScrollBehavior`）。首页顶栏标题「订阅」采用 `headlineSmallEmphasized`，右侧动作区提供「推送」「关注动态」入口（键鼠模式旁置刷新钮）；常访横条左侧 `LiveNowSlot` 采用 `2.dp` `primary` 直播光环胶囊加底部重叠 `primaryContainer`「N 直播」胶囊。
- **个人空间全幅头图 Hero（`SpaceScreen`）**：
  - 顶部铺开 `148.dp`（窄屏）/ `188.dp`（宽屏）`top_photo` 横幅（上覆顶栏可读性暗角，下接向 `surface` 过渡的渐变；无头图时退回柔和主题色阶渐变）。
  - `72.dp` `AvatarHero` 带 `3.dp` `surface` 描边跨压横幅下沿，右侧基线对齐关注按钮；下方展示 `headlineSmallEmphasized` 昵称、`LevelBadge`、单击即复制的 `UID` 胶囊、`.tabular` 粉丝/投稿计数、点击就地展开的个人签名，以及 `primaryContainer` 直播间入口卡。
  - `SpaceTopBar` 与滚动联动：滚过头图姓名后平滑淡入迷你头像与 UP 名。宽屏下投稿页签工具条与标签行并排（`showControls = !wide`），绝不出现双份「听全部 / 排序」按钮。
- **「我的 / 媒体库」页身份卡与 M3E 书架旋转木马（`ProfileScreen`）**：
  - `< 600dp` 底栏形态保留「我的」顶栏、身份卡（`56.dp` 头像、`titleLargeEmphasized` 昵称、`LevelBadge`、可复制 `UID` 胶囊）与四段式 `ButtonGroup`（「关注 / 硬币 / 消息 / 设置」——**「消息」严禁画任何未读数或红点**）；`>= 600dp` 精简侧边导航栏常驻账号头像与底部四项快捷入口，本页改称「媒体库」并直接从四节书架起排。
  - 下方历史、稍后再看、收藏夹、离线缓存书架统一采用 **`ProfileCarousel`（基于 `HorizontalMultiBrowseCarousel` + `shapes.largeIncreased` 20dp `maskClip` 与竖向滚轮横向浏览驱动）**，减弱动效时退回等宽 `HorizontalUncontainedCarousel`。

### 2.6 统一二级面板契约（`ui/components/PaneSheet.kt` & `PlayerSidePanel.kt`）

**全应用所有二级面板一律走 `PaneSheet`，严禁直接调 `ModalBottomSheet`。** `PaneSheet` 按所在上下文自动呈现三种形态，并统一渲染标题栏（`title` + `headerTrailing`）、固定副头栏（`subheader`）、弹性主体（`Modifier.weight(1f, fill = false)`）与粘性底栏（`footer`）：

1. **双栏右栏内嵌面板（`SidePaneLayer`，`inPane == true`）**：播放页、直播间双栏布局下从右栏滑入，顶栏为 `[返回箭头] [title] [headerTrailing]`（并自动承接桌面窗口拖拽与右上角控制键）；左栏画面加透明 `SidePaneDismissLayer` 拦截点击收起面板，不压暗视频画面（`persistent = true` 的常驻面板除外）。
2. **宽屏与紧凑高度横屏悬浮模态侧边面板（`rememberBilbyWindowSize().useTwoPaneMediaLayout`，即 `Expanded+` 或 `Medium` 且高度 `< 480dp`，`ModalSideSheet`）**：`surfaceContainerLow` 底色、`shapes.large`（16dp）圆角、最大宽 `400.dp`、四周 `16.dp` 悬浮边距、背景 `0.32f` 遮罩、自动避让 `barsAndCutout.union(ime)`，单行 `56.dp` 顶栏统一渲染 `[title] [headerTrailing] [Close]`，内部禁止出现横向滚动条（如缓存画质用 `FlowRow` 折行）。
3. **其余窄屏模态底部面板（`ModalBottomSheet`）**：`surfaceContainerLow` 底色、`shapes.extraLarge`（28dp）顶角、高度上限 `maxHeightFraction`（默认 `0.88f`），内置 `WheelStopsAtSheet` 防止鼠标滚轮把面板拖停在半路。

**两条面板铁律**：
- **带确认/保存按钮、输入框或短内容的面板必须 `skipPartiallyExpanded = true`（默认值）**，并将底部按钮或输入条放进 `footer = { ... }`（`SheetFooterSlot` 用 `surfaceContainer` 色阶承托并自动处理 `navigationBarsPadding()` 与 `imePadding()`），绝不允许停在 50% 半开态把操作按钮截出屏幕外。
- **全屏播放器内侧滑面板走 `PlayerSidePanel` + `PlayerSidePanelHeader`**（`surfaceContainerLow`、左侧 `16.dp` 圆角、最大宽 `380.dp`、带 `.tabular` 计数徽章与关闭键）。

### 2.7 全应用双模态复制与文本选择规范

Compose 的 `SelectionContainer` 若直接套在可点击卡片外层或跨 `LazyColumn` 回收行包裹，会抢走点击/长按手势，并在滚出屏幕节点销毁时引发崩溃。全应用统一实行**指针与触屏双模态复制契约**：

1. **非点击阅读块**（展开后的视频标题与简介、`DynamicDetailScreen` 顶卡正文、`ArticleScreen` 标题与段落/列表、`AgentAnswerView` 回答与提问）：
   - 桌面指针环境（`!LocalPointerSource.current.isTouchLike`）外包 `SelectionContainer`，提供原生 I-beam 光标划选，同时保持 `BiliRichText` 内的 `LinkAnnotation`（站内链接、`@提及`、时间戳）可点；
   - 双端同时外包 `ContextMenuBox`（桌面右键 / `Shift+F10`、触屏长按），提供一键「复制…」与「选择文本」（打开带滚动上限的 `SelectableTextDialog` 供自由选词复制原文）。
2. **可点击列表项、卡片与胶囊**（全站 `VideoRow` / `VideoGridCard` 的 `VideoCommonMenuItems`、`CommentRow`、流内 `DynamicCardView`、`WhisperScreen` 的 `TextBubble`、`TagToken`、`UpRow`、直播间顶栏与弹幕行）：
   - 统一通过 `ContextMenuBox` 提供结构化复制项（复制标题、复制 BV/AV 号、复制昵称、复制 UID、复制评论、复制动态正文/转发正文/动态链接、复制消息、复制弹幕）与 `SelectableTextDialog`。
3. **高频标识与代码一键复制**：
   - 视频页 `BV` / `AV` 号、空间页与个人页 `UID` 使用独立消费点击的 `CopyableIdPill`（折叠态与展开态均可一键复制，绝不误触外层折叠开关）。
   - 专栏代码块（`ArticleBlock.Code`）顶部固定带语言名与一键「复制代码」图标按钮的 `surfaceContainerHigh` 工具条，下方等宽代码区支持横向滚动与直接框选。
4. **回执去重**：统一经 `rememberCopyAction()` / `LocalCopyNotice` 触发；仅在桌面端与 Android 12L 及以下弹应用内提示（`NeedsCopyNotice`），Android 13+ 交给系统剪贴板预览浮层，不叠双份提示。

### 2.7b 评论、动态、专栏、状态与破坏性确认细则

- **评论区（`CommentSection.kt`）**：`NameLine` 取 `labelLargeEmphasized`（`onSurfaceVariant`），正文 14/24sp，楼中楼整组收进 `surfaceContainer` + `shapes.large`（16dp）单容器（24dp 头像、14/22sp），「查看全部 N 条回复」打开带粘性 `footer` 的 `CommentThreadSheet`。
- **动态卡片（`DynamicCardView.kt`）**：外卡 `surfaceContainer` + `shapes.largeIncreased`（20dp，可打开态配 `interactiveCard` 悬停反馈）；内嵌视频（`DynamicContent.Video`）、直播、番剧、预约、投票与转发卡统一收进 `blockStyle`（外层内块 `surfaceContainerHigh` + `shapes.medium`，转发内层子块 `surfaceContainerHighest` + `shapes.small`）。整卡只有「直播中」与摘要「阅读全文 / 展开」两处可点入口使用 `primary`。
- **专栏阅读（`ArticleScreen.kt`）**：`headlineMediumEmphasized` 标题、16/28sp 宽松正文行高；正文插图一律单张整宽按原比例顺序排列（长图按 16:10 裁引子，点开进 `ImageViewer` 通篇左右翻看），不压成动态九宫格。
- **等待指示与静止空态（`States.kt`）**：200ms 内不闪指示器；不确定进度统一用 M3E `LoadingIndicator`（`FullScreenLoading` 48dp、`LoadingSpinner` 24dp、`InlineProgress`、`ListFooter`，减弱动效退回静止 `StaticLoadingToken`）与 `RefreshBox`；确定百分比用 `LinearProgressIndicator`；列表首载用同构脉冲骨架屏（`Skeleton.kt`）。空态与错误态采用完全静止的 `StateBackdropIcon`（`Cookie9Sided` 色块底托），禁止任何空闲呼吸动画。
- **不可逆破坏性确认（`DestructiveConfirmButton`）**：删除缓存/历史/评论、清空列表或移出分组等不可逆操作确认钮统一使用 `DestructiveConfirmButton`（`colorScheme.error` / `onError`），不用低辨识度纯文字按钮。
- **设置页（`SettingsScreen.kt`、`SettingsPages.kt`）**：「设置只调怎么做，不给违背产品边界的功能开关」。一级分类配 `36.dp` 圆角方形图标底托（双栏选中态切 `primaryContainer`）；各子页统一收进 `SettingsGroup`（`SegmentedListItem`）；检查更新行有新版本时升为实心 `Button`，**严禁挂 `Badge()` 红点**；API key 默认遮蔽且绝不进任何日志。

---

## 3. 无障碍与界面语体

- **最小触控目标 `48.dp × 48.dp`**：紧凑图标按钮视觉尺寸可收至 16–32dp，点击热区必须补足 48dp（或整行合并为单一可点节点）。
- **整行语义合并**：单选/多选/开关行使用 `Modifier.selectable` / `toggleable` + `Role`，不给行尾 `RadioButton` / `Switch` 单独挂二次点击。
- **不念重复的 `contentDescription`**：旁边紧挨着同名可见文字的封面、头像、导航图标一律传 `null`；无文字纯图标按钮必须给出明确动作描述；方向性图标（返回、发送、打开）使用 `Icons.AutoMirrored`。
- **书面语，零口语闲聊**：界面文案与确认框直陈动作本身（如「取消关注」「删除这条评论?」），不用「刷视频」「删掉」「瞟了一眼」等口语腔；助理检索过程行只写对象名，动作交由行首图标表达。

---

## 4. 本项目特有的结构与体验守则

### 4.1 机器可读图形不随主题：扫码登录欢迎卡（`TvLoginScreen.kt`）

扫码登录页居中呈现 `surfaceContainer` + `shapes.extraLarge`（28dp，最大宽 `440.dp`）欢迎卡片（桌面端自带顶栏拖拽与窗口控制键），内部二维码固定画在纯白 `FixedColors.QrBackground` 衬垫（`16.dp` 圆角）上：纯黑码块、四周 4 模块宽白静区、关闭双线性插值按整数倍缩放，保证深浅色与任意缩放比下一次扫出。

### 4.1b–e 播放器 HUD 微高光描边与全屏系统状态胶囊（`PlayerStatusCluster`）

- **渐变遮罩与常驻内嵌返回键**：底栏与全屏顶栏使用平滑渐变遮罩；内嵌态左上角 `MediaBackButton` 独立常驻，不随播放控制层自动隐藏；全屏与看图器的刘海区延伸仅在各自 `FullscreenEffect` / Dialog 窗口级切 `SHORT_EDGES`，绝不写进全局主题。
- **屏幕常亮判据**：`keepScreenOn = isPlaying && attached`，纯音频直播与听视频模式正常允许息屏。
- **HUD 内侧微高光描边**：浮在视频画面上的深色半透明胶囊（手势倍速/进退/音量浮层、云端续播提示、跳过提示、全屏状态胶囊）统一加 `1.dp` `Color.White.copy(alpha = 0.08f)` 内侧高光边，保证在纯白雪景/高光画面上边界依然清晰。
- **全屏右上角系统状态胶囊（`ui/player/PlayerSystemStatus.kt`）**：
  - 仅在 `isFullscreen && !pip` 时激活监听与采样，内嵌态与小窗态**零后台广播、零轮询开销**。
  - 胶囊内以 `8.dp` 间距（无中点）展示三项 `.tabular` 读数：**网络类型**（`Wi-Fi` / `5G` / `LTE` / `移动网络` / `以太网` / `离线`，Android 端不申请 `READ_PHONE_STATE` 权限，Windows 端通过 JDK 25 FFM 查 `iphlpapi` 路由表且对 VPN/TUN 虚拟网卡自动回退物理网卡）、**电池图标 + 电量百分比**（台式机无电池时自动隐藏整块电量指示）、以及整分唤醒对齐的 **`HH:mm` 当前时间**。

### 4.2 反成瘾靠结构，全应用零红点

- 无推荐流、无相关视频侧栏；「找相关」仅在配置好助理模型时显示，且必须由用户显式点击发起，答完即止，不留「再找一批」。
- 空态与错误态只陈述事实（静态 `StateBackdropIcon` 配 `Inbox` / `ErrorOutline` 区分「本来为空」与「加载失败」），不给「去逛逛」导流入口。
- **全应用严禁出现任何红点、未读数字或提醒类 `Badge`**：`LevelBadge`、「置顶」、「充电专属」、「转载」属于静态属性标签，不随时间制造「有新内容快回来看」的焦虑；凡属未读提醒类的标记一律不做。
- **夜间静息模式（`NightModeScreen`）**：支持定时自动生效与首页顶栏/设置手动进入（进场通过 `SystemActions.showToast` 发轻量系统级提示，桌面端为无焦点穿透胶囊浮层），期间仅展示白名单 UP 主投稿与离线缓存，头卡光环保持完全静止；临时退出或缩减时段须经 30 秒按住确认（100% 圆形 `NightExitHoldSheet` / `NightHoldDial`）。

### 4.3 播放页三态同构、听视频唱盘与快捷播放条

- **听视频（`ListenScreen`）是播放页内部的状态分支，与全屏同构，绝不拆成独立导航目的地**；全局唯一播放器归 `AudioPlaybackService`（Android）与 `DesktopPlaybackHost`（桌面）所有。
  - `DiscView` 保持 **100% `CircleShape` 真黑胶唱片**（静态径向光晕，禁止改为多边形或空闲呼吸动画）；转动惯性仅在播放或减速收尾时驱动，暂停落稳后立即停止帧回调。
  - 双栏模式且右侧队列可见时，左侧 `DiscView` 隐藏重复来源胶囊（`showSourcePill = !(expandedWindow && hasQueue)`）。
- **暂停下翻评论时的快捷播放条（`QuickPlayBar.kt`）**：画面仅在暂停且浏览评论区时随上滑收起，顶部留驻 `64.dp` 高的 `surfaceContainer` 快捷条（`44×28.dp` 圆角封面缩略图、`titleSmallEmphasized` 单行标题、胶囊播放键、底沿 `3.dp` 进度条）；点横条本身只展开画面，点播放键才恢复出声。
- **`IntroTab` 单列表连续自然滚动与就地平滑展开（`VideoTabs.kt`）**：
  - 简介页自上而下是**同一条连续滚动的 `LazyColumn`**：`TitleBlock` → `UpRow` 容器卡 → 五格 `ActionButtonsRow` → 分 P 横条 → 内联播放队列预览。
  - **展开简介时绝不隐藏或卸载下方的播放队列**。视频标签在进页加载详情时即并发预热（`loadVideoTags()`），避免展开后二次撑高跳动；点击 `TitleBlock` 时，完整精确发布时刻（`yyyy-MM-dd HH:mm` `.tabular`）、禁止转载声明、富文本简介与标签行通过 `AnimatedVisibility`（空间弹簧 `expandVertically` + `fadeIn`）**就地单段平滑展开**，自然把下方卡片往下推。
  - 桌面端或非触屏指针模式下发表评论采用右栏底部就地停靠的 `InlineCommentComposer` 编辑卡，不再弹出居中白底模态框。

---

## 5. 动效分工与因果约束（`ui/theme/Motion.kt`）

- **整屏转场用缓动与时长（`Motion` tween）**：压栈/返回走五分之一屏滑动加淡入淡出（触控端 `Medium4` 400ms `EmphasizedDecelerate` / `EmphasizedAccelerate`；桌面指针端收紧为 `DesktopEnter` 220ms 进入 / `Short3` 150ms 退出）；预测式返回通过 `SeekableTransitionState` 跟手映射 `StandardDecelerate`；根目的地切换先快速淡出再淡入（`Short3` 150ms + `Medium1`/`DesktopEnter`，不横滑）。开启系统「减弱动效」时转场自动退化为纯淡入淡出。
- **组件内交互用物理弹簧（`MaterialTheme.motionScheme`）**：面板滑入、简介展开、按钮形变、卡片悬停按压（`interactiveCard`）统一取主题 `MotionScheme` 的 spatial / effects spring，不在业务代码里散写裸 `tween(300)`。触控端取 `MotionScheme.expressive()`，桌面端取 `MotionScheme.standard()`（并与 `Main.kt` 的 `AppMotionScale` 联动跟随 Windows 系统动画开关）。
- **严格禁止空闲/环境呼吸循环**：连续循环动画（`rememberInfiniteTransition` / `infiniteRepeatable`）只允许用于**真实活跃态**——加载中的 `SkeletonPulse`、播放中/直播中的 `PlayingIndicator`、播放中的 `SeekBar` 波浪相位——且**全部必须以 `!rememberReducedMotion()` 门控**，静止或暂停后必须撤出组合，不得让桌面端持续按刷新率空转。空态、夜间头卡、静态标识或暂停唱盘严禁挂载空闲呼吸循环。

---

## 6. 响应式窗口与桌面悬浮岛架构

布局只看窗口实时尺寸（`rememberBilbyWindowSize()` / `rememberIsHeightCompact()` 读取 `LocalWindowInfo.containerSize`），绝不按设备类型硬编码分支：

| 窗口档位 | 断点条件 | 布局行为 |
|---|---|---|
| `Compact` | `< 600dp` | 底部三格导航栏（「订阅 / 搜索 / 我的」），列表采用单列 `VideoRow`，二级面板走模态底部 `PaneSheet` |
| `Medium` | `600dp .. < 840dp` | 左侧切换为不可展开的精简 `80.dp` 侧边导航栏（顶部头像锚点 / 居中「订阅 · 搜索 · 媒体库」/ 底部「关注 · 硬币 · 消息 · 设置」快捷图标），`AdaptiveVideoGridContent` 切入多列纵向 `VideoGridCard` 网格；当高度 `< 480dp`（`useTwoPaneMediaLayout`）时，播放页/直播间/听视频切入双栏，二级面板升为 `ModalSideSheet` |
| `Expanded+` | `≥ 840dp` | 沿用同一套精简 `80.dp` 侧边导航栏（无展开抽屉与切换钮），播放页/直播间/听视频拆左右双栏（右栏托管内嵌 `PaneSheet`），空间页动态可开持久化可调宽侧栏（`SidePanelLayout`），无宿主二级面板走悬浮 `ModalSideSheet`，设置与消息中心启用双栏主从场景 |

- **阅读限宽 vs 列表网格**：`AdaptiveContent`（上限 `ReadableWidth = 1040.dp`）仅用于长文阅读与对话页（专栏、动态详情、评论楼、搜索对话）；视频与合集网格页一律用 `AdaptiveVideoGridContent` / `rememberVideoGridColumnCount`（按显示器全宽设定列数比例折算）；非视频列表页走 `AdaptiveListContent`；动态流宽屏走最小列宽 `DynamicColumnMinWidth = 400.dp` 的瀑布流。修饰符限宽牢记先写 `.widthIn(max = …)` 再写 `.fillMaxWidth()`。
- **双栏与页面零冗余双入口**：双栏场景下左栏与右栏不重复放置同一功能的入口或胶囊（例如 `ListenScreen` 双栏隐藏左侧重复来源胶囊、`NightModeScreen` 空态不重复放置顶栏设置图标、`SpaceScreen` 宽屏将排序/听全部收归标签行、`BilbyApp` 设置/消息双栏自动保持唯一焦点）。
- **桌面端统一悬浮岛框架与双栏分岛（`DesktopIslandFrame` & `ListDetailSceneStrategy`）**：
  - 在桌面窗口（`LocalWindowCaption.current != null`）下，除全屏沉浸态外，根标签页、压栈子页面以及双栏播放页（`VideoScreen`）与直播间（`LiveRoomScreen`）统一走内容岛架构：外框铺 `surfaceContainer` 底托与 `8.dp` 顶栏拖拽边及四周间隙；双栏播放页、直播间与 `ListDetailSceneStrategy` 左右两栏分别收在独立的 `shapes.large`（16dp）`DesktopIslandSurface` 圆角岛内并留出 `8.dp` 分栏间隙（`WindowsCaption.kt` / `WindowFrame.kt` 关闭键悬停采用 Fluent 2 语义红 `#C42B1C`，切页全程窗口按钮坐标纹丝不动）。
  - 在移动端（`LocalWindowCaption.current == null`）退回标准 M3 全出血 `surface` 页面与 `0.dp` 分栏间距；横屏双栏播放页与直播间由右侧详情/聊天面板的 `surface` 底色托底并裁去左侧黑色播放器右缘（`topEnd`/`bottomEnd` `shapes.large` 16dp）。
- **播放器控件与输入模式分化**：触屏模式保留画面正中 `CenterPlayButton`；键鼠模式（`!LocalPointerSource.current.isTouchLike`）隐藏正中暂停按钮以免遮挡视频，改为底部控制栏最左端的 `ControlBarPlayButton`（具备 M3E 圆形↔圆角方块形变与 `primary`/`primaryContainer` 强调色），桌面端弹出菜单采用无过冲的 `snap()` / 短淡入动效主题。
