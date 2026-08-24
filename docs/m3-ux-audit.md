# Bilby × M3 规范 UX 审计报告

日期:2026-08-12
规范依据:仓库内 M3 官方规范镜像 `m3-material-mirror/pages/`(87 页,2026-08-09 抓取)
审计范围:`app/src/main/kotlin/dev/bilby/ui/` 全部页面与共享组件

---

## 一、系统性问题(全 app 多处出现,优先修)

### 1. `outline` 被当作次级文字色大面积误用 —— 高

规范里 `outline` 是描边/边界角色(`styles/color/roles.md`),只保证约 3:1 对比;文字的低强调角色是 `onSurfaceVariant`。实测浅色主题下 `outline #757680` on `surface #FAF8FF` ≈ **4.3:1**,不达小字 4.5:1 要求;深色主题反而达标——所以这是"浅色主题专属看不清"。

涉及至少 14 处:

- `ui/components/Stats.kt:45`、`VideoRow.kt:123,165`、`SortRow.kt:79-83`
- `ui/comment/CommentSection.kt:325,415,642`、`ui/video/VideoTabs.kt:632,706`
- `ui/message/MessageScreen.kt:156,211,219,278`、`ui/live/LiveRoomScreen.kt:509,646`、`ui/space/SpaceScreen.kt:879`、`ui/message/WhisperScreen.kt:220,231`

最伤的一处是 `MessageScreen.kt:211`:通知的**行为者名字**(主要身份信息)用了最弱的描边色,层级也错了。

**修法**:文字统一改 `onSurfaceVariant`,`outline` 只留描边用途。一处改动同时修好角色误用和对比度。

### 2. 自绘控件普遍只做视觉尺寸、没做触控热区 —— 高

项目自己知道 48dp 规则(`SortRow` 注释写明并做到了),但一批自绘控件漏了。规范依据:`foundations/interaction/states.md`(交互目标 48dp)、`components/icon-buttons.md`、`components/lists.md:271`、`components/chips.md:841`(芯片也要 48dp)。

| 位置 | 现状 |
|---|---|
| `ui/components/SeekBar.kt:31` | 进度条触控高度 **24dp**,且完全没有 `semantics`/`progressBarRangeInfo`,TalkBack 用户无法感知进度也无法 seek(另违反 `sliders.md` 无障碍条款) |
| `ui/components/BadgedAvatar.kt:69` | 联合投稿唯一的关注入口,角标触控区 **14dp**(`VideoTabs.kt:605` 调用) |
| `ui/player/PlayerControls.kt:200` | 倍速/画质/字幕/弹幕四个 `ControlButton` 宽约 34dp,挤在一行易误触 |
| `ui/comment/CommentSection.kt:303,311` | 36dp 头像、约 20dp 高的昵称行可点 |
| `ui/follow/FollowingsScreen.kt:331` | `FilterChip` 触控高度 32dp |
| `ui/search/SearchChatScreen.kt:485` | 用户 chip 约 36–40dp |
| `ui/components/Bars.kt:99`、`AgentTurnView.kt:113` | 可点标题行约 38/44dp |

**修法**统一且便宜:视觉尺寸不变,套 `minimumInteractiveComponentSize()` 或 `heightIn/widthIn(min = 48.dp)`。

### 3. 菜单选中标记用文本「·」—— 中

`ui/components/SubtitleTrackMenu.kt:47`、`ui/video/BilbyPlayer.kt:557`、`ui/live/LiveRoomScreen.kt:791` 三处。`components/menus.md` 要求选中项用 checkmark 等明确视觉线索 + 色彩双通道;小圆点既不像选中态,读屏还会把它当标点节点。

**修法**:换 `Icons.Filled.Check`,必要时给菜单项补 `Modifier.semantics { selected = true }`。

### 4. 自制"snackbar 式"提示不守 snackbar 规则 —— 中

- `ui/video/SponsorSkip.kt:107` —— 跳过提示 **2.5 秒**消失,低于 snackbar 4–10 秒下限,读不完
- `ui/MainActivity.kt:258` —— 导航层用系统 `Toast`,不参与主题、样式与 app 脱节

### 5. 长按是若干功能的唯一入口,零可发现性 —— 中

- `ui/feed/FeedScreen.kt:450` —— 「不再显示这个 UP」(永久改变首页内容)只能长按触发,无行尾菜单、无撤销;且 `interaction/gestures.md` 里长按的规范语义是"选中项",不是唤起隐藏菜单
- `ui/offline/OfflineScreen.kt:129` —— 多选模式只能长按进入(进入后的 contextual app bar 做得很好,缺的是入口)
- `ui/player/PlayerShell.kt:438-544` —— 双击 seek、长按倍速、纵划亮度音量五组手势全部零提示
- `ui/components/ImageViewer.kt:188` —— 双击缩放无提示、无替代按钮

**修法**:行尾 `MoreVert`/可见入口 + 破坏性动作配 Snackbar 撤销;播放器首次全屏给一次性手势引导(coach mark / rich tooltip)。

## 二、单点严重问题

### 6. 删除评论:不可逆、无确认、无撤销、失败不可恢复 —— 高

`ui/comment/CommentSection.kt:529` 一键即删;`CommentViewModel.kt:302` 乐观移除后 API 失败也不加回,用户看到的就是"评论没了"。同 app 给投币写了确认对话框(`VideoTabs.kt:965` 注释明说投币不可逆),标准不一致。

规范依据:`dialogs.md`(high-risk actions 需确认)、`snackbar.md`(Undo action)。

### 7. 稍后再看的删除链路同样裸奔 —— 高

`ui/toview/ToViewScreen.kt:161` 单条删除无反馈;`ui/MainActivity.kt:1214` 顶栏"清空已看完"一键清空无确认。对比 `ui/offline/OfflineScreen.kt:171` 删除缓存有确认对话框——同为不可逆远程删除,待遇不一致。

### 8. 重按当前 tab 不回滚到顶部 —— 中

`ui/MainActivity.kt:665`。`navigation-bar.md` 明文:"re-selecting the currently active destination should reset the scroll position to the top"。动态流是翻不到底的时间序流,这条规范行为恰好是它最需要的。

**修法**:`onClick` 里判断 `selected == tab` 时触发对应 pane 的 `listState.animateScrollToItem(0)`。

### 9. 原始异常消息和错误码直接上屏 —— 中

`ui/login/TvLoginScreen.kt:234,264`("网络错误: Unable to resolve host…"、"轮询失败(-404)")、`ui/search/SearchChatViewModel.kt:280,305`、`ui/settings/SettingsViewModel.kt:118`。`foundations/content-design/style-guide.md` 要求报错给用户可行动的文案;登录是新手必经路径,最伤。

**修法**:在仓库/ViewModel 层把异常归类成少数几种用户可行动的文案(网络不通 / 服务拒绝 / 已过期),错误码放次要位置或日志。

## 三、搜索体验

- `ui/search/SearchChatScreen.kt:575` —— 搜索框 `placeholder = ""` 且无 `semantics` 标签,读屏面对一个无标签裸输入框;hint 文本同时也是规范要求的无障碍标签
- `ui/search/SearchChatViewModel.kt:304` —— `userError` 是死状态:UP 主搜索失败静默降级,"搜不到人"与"接口坏了"不可区分

## 四、低严重度打磨项

- `ui/MainActivity.kt:694` —— NavigationRail 目的地顶对齐,`navigation-rail.md` 建议平板上居中对齐便于触达
- `ui/components/Bars.kt:48` —— 顶栏不接 `scrollBehavior`,滚动时无变色分隔(`app-bars.md` 的 on-scroll fill)
- `ui/settings/SettingsScreen.kt:711` —— 所有可点行一律 chevron,跳转 / 弹对话框 / 直接执行三者语义不分
- `ui/settings/SettingsScreen.kt:792` —— LLM/服务器地址对话框无校验无错误态,后者只有 placeholder 没有持久 label(`text-fields.md`)
- `ui/settings/SettingsScreen.kt:659` —— APK 下载只有百分比文字,没有 determinate `LinearProgressIndicator`
- `ui/profile/ProfileScreen.kt:675`、`ui/components/States.kt:209` 等 —— 16dp 圆形 spinner 低于 24dp 下限;按钮内 spinner 未去 track、未用内容色(`progress-indicators.md`)
- `ui/components/LevelBadge.kt:44` —— 纯 Canvas 无语义,评论区等级对读屏完全丢失(加一行 `contentDescription` 解决;浅底色对比度属品牌取舍可不动)
- `ui/video/DanmakuInput.kt:125` —— 100 字上限静默截断,无计数器(`text-fields.md` 的字符限制模式)
- `ui/space/SpaceScreen.kt:962` —— 耳机图标="听投稿",语义不可猜,建议带文字标签
- `ui/message/MessageScreen.kt` —— 五栏均不可下拉刷新,与其他列表页不一致
- `ui/components/BilbyIcons.kt:41` —— 自绘弹幕图标用描边风格,与全 app 填充系图标混排(`styles/icons.md`: avoid mixing styles)
- `ui/components/Media.kt:146,163` —— 角标裸 dp 字面量,违反项目自己"间距 token 化"的约定(`styles/spacing.md`)

## 五、待做的功能(非审计项)

以下两条不是这次审计发现的问题,是记在这里免得散落。

### 深色模式

现在只跟随系统,没有应用内的三档选择(跟随系统 / 始终浅色 / 始终深色)。两处要注意:

- 主题两份配色都已经在 `ui/theme/`,缺的是入口和持久化,不是配色本身。
- **两个主题都没设 `LocalContentColor`**(CLAUDE.md 记着这条),所以新页面必须套 `Surface`,否则深色下是黑底黑字。加了手动切换之后这类问题会更容易在浅色下漏测。

### 主题色配置

现在是 Material You 动态取色,取自壁纸。缺的是手动指定一个种子色,以及「动态取色 / 手动」的开关。

放在设置的哪一节要先定:它既不属于「播放」也不属于「隐私与屏蔽」,大概率要新开一节「外观」,而那一节正好也是深色模式该去的地方。

## 做得好的方面(无需改动)

- **主题系统**(`ui/theme/`):色板、排版(含 emphasized 档与 CJK 调整)、形状十档、动效 token、减弱动效处理,与规范逐条对得上
- **导航骨架**:3 个导航项、选中/未选中图标区分、compact 用 NavigationBar / medium 起换 NavigationRail、多选时 contextual app bar + BackHandler 处理,均合规
- **状态管理**:`States.kt` 把首屏加载/首屏错误/空态/翻页 footer 收敛成一套,翻页失败不清列表
- **确认对话框**:投币、登出、删除缓存都有规范确认对话框,确认按钮文案是具体动作而非 "OK"
- **开关语义**:总开关用 Switch、多选用 Checkbox,整行 `toggleable` + role
- **登录状态机**:六态都有明确视觉反馈,过期可刷新、失败可重试
- **SortRow / ChoiceRow**:48dp 触控、`selectableGroup` + `Role.RadioButton`,是组件层正面样本

## 总结与优先级建议

主题系统和状态管理是规范落地的正面样本。真正成体系的债务就三类:

1. **`outline` 当文字色**(§一.1)
2. **自绘控件触控热区不达标**(§一.2)
3. **破坏性操作缺确认/撤销且标准不一**——投币有、删评论/稍后再看没有(§二.6、§二.7)

建议修复顺序:§一.1 → §一.2 → §二.6/7 → §二.8(回顶)→ §一.5(长按可发现性)→ 其余抛光项。前四项影响面最大且修复成本低(多为几行 modifier 的改动)。
