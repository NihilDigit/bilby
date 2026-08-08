# Bilby 界面风格指南

写给往这个仓库里加界面的人。只写**我们做过的判断**:token 取什么值、组件选哪个、
以及本项目故意偏离 M3 的地方和理由。M3 文档已经说清楚的东西不复述,需要时去
[m3.material.io](https://m3.material.io) 读原文。

---

## 0. 依赖走的是 alpha 线,expressive 可用

`gradle/libs.versions.toml` 里 pin 的是 **`androidx.compose:compose-bom-alpha:2026.07.01`**,
解析出来是 **material3 1.5.0-alpha25** + ui/foundation 1.12.0-rc01。
稳定线的 BOM(2026.06.01)锁的是 material3 1.4.0,**Expressive 那一批在 1.4.0 上一个都用不了**。

这不是可有可无的差别,1.4.0 上实测:`MaterialExpressiveTheme` / `MotionScheme` /
`expressiveLightColorScheme` 全是 `internal`;`ButtonGroup`、`ToggleButton`、`SplitButton`、
`LoadingIndicator`、`WavyProgressIndicator`、`MaterialShapes`、FAB menu、Floating toolbar
**类根本不存在**;`Shapes` 只有五个槽;`Typography` 只有 15 档基线。

### 我们依赖了 alpha 的哪些 API

升级或降级依赖时,先看这张表 —— 退回稳定线的话下面每一条都要改写:

| API | 用在 | 退回 1.4.0 的后果 |
|---|---|---|
| `MaterialExpressiveTheme` | `Theme.kt` 主题入口 | internal,只能退回 `MaterialTheme`,组件拿不到 expressive 默认值 |
| `MotionScheme.expressive()` / `MaterialTheme.motionScheme` | 全局动效唯一来源 | internal,动效要退回各处手写 `tween`/`spring` |
| `Shapes` 的 `largeIncreased` / `extraLargeIncreased` / `extraExtraLarge` | `Shape.kt` 十档刻度 | 只有五档,中间档要在组件处写死 |
| `Typography` 的 15 档 `*Emphasized` | `Type.kt` | 不存在,强调只能靠 `FontWeight` 手改 |
| `ButtonGroup` + `toggleableItem`/`clickableItem` | 播放页动作栏 | 不存在,要退回手排 Row + 自己维护选中态配色和触摸目标 |
| `LoadingIndicator` | `FullScreenLoading` | 不存在,退回 `CircularProgressIndicator` |

**全部需要 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`。**

### 探针在哪、怎么用

`app/src/test/kotlin/dev/bilby/ui/M3ApiProbe.kt`,用
`./gradlew :app:compileDebugUnitTestKotlin` 单独编译。**升级依赖后重跑它。**

两条工作方式上的规矩,都是踩出来的:

- **探针放 test source set,不放 main。** 探针的用途只是"这个 API 在不在",不需要进产物;
  放 main 里意味着任何一个中间态的编译错误都会让同一棵树上的其他人停工。发生过一次。
- **去 aar 里确认签名,不要照文档假设 API 存在。** 官网讲的是设计系统的现行版本,
  不是当前依赖的 API 面。没有 sources jar 时(alpha 版常见)用
  `javap -classpath <解开的 classes.jar> androidx.compose.material3.XxxKt` 看真实签名。

已经这么撞过的两次:

- `ShortNavigationBarItem` 必须传 `label`。这不是障碍,是规范本身要求 ——
  M3 的 short navigation bar 要求可见标签,icon-only 是 navigation rail 那边的形态。
- `Typography` 的默认字体参数**不叫** `defaultFontFamily`,是第一个位置参数。

---

## 1. Token

### 1.1 颜色

**基线色板由种子色 `#4A5C92` 生成**(`ui/theme/Color.kt`)。这个种子不是新挑的:
`res/values/colors.xml` 里的 `ic_launcher_background` 本来就是它,`values-night` 的启动
背景 `#121318` 正是它在 2021 版规范下算出的 dark surface。补全成完整 role 表是为了让
**启动窗口、应用图标、Compose 主题真的是同一套色**,而不是三处各写各的。

重新生成的方法(改种子时用):

```bash
uv run --with materialyoucolor python - <<'PY'
from materialyoucolor.scheme.scheme_tonal_spot import SchemeTonalSpot
from materialyoucolor.dynamiccolor.material_dynamic_colors import MaterialDynamicColors as M
from materialyoucolor.hct import Hct
sch = SchemeTonalSpot(Hct.from_int(0xFF4A5C92), False, 0.0, spec_version="2021")
print("%06X" % (M.primary.get_hct(sch).to_int() & 0xFFFFFF))
PY
```

`spec_version` **必须是 2021**。material3 1.4.0 实现的是 2021 版规范,用 2025 版生成的表
会和同一台机器上 `dynamicLightColorScheme()` 的观感对不上 —— 深浅、彩度都差一档。

**动态取色默认开着**,`BilbyTheme(dynamicColor = …)` 可关。判据:单用户自用的应用,
系统色就是用户已经选过的审美,没有品牌一致性需要压过它;基线色板的作用是 Android 12
以下和用户关掉时的兜底,不是"我们的品牌色"。

**角色怎么选**,只有两条本项目的补充:

- **容器填充一律用 `surfaceContainer*` 这一族,不要用 `surfaceVariant`。**
  `surfaceVariant` 现在主要是给它的 `on` 色(`onSurfaceVariant`,低强调文字)留位置的。
  拿它当底,深色主题下会比周围的 `surface` 亮出一大截 —— 楼中楼和助理结果卡踩过。
- **层次靠 `surfaceContainer` 的色阶差表达,不要靠 `tonalElevation` 和阴影。**
  M3 的说法是"less is more",阴影留给真正浮起来的东西(弹窗、sheet)。
  输入栏、顶栏这些就是换一档 container 色。

### 1.2 不跟主题的固定色(`FixedColors`)

判据只有一条:**这个颜色是画给机器看的,还是压在一张我们控制不了的图片上的**。
两种情况下"随主题反色"会直接坏掉功能,而不只是难看一点。

| Token | 用在哪 | 为什么固定 |
|---|---|---|
| `QrBackground` | 登录二维码的底 | 见 §4,最硬的一条 |
| `ScrimOnMedia` `0x8C000000` | 封面上的时长角标、进度条底槽 | 封面是 UP 主上传的任意图片,亮的暗的都有。`0.55` 的 alpha 是算出来的:最坏情况(纯白封面)下白字对比度 4.81:1,刚过 4.5。再低在雪景/白墙封面上会散,再高遮罩本身变成一块黑斑 |
| `OnMedia` | 压在封面、播放画面上的文字与图标 | 跟主题走会在深色下变成深灰,压在黑边上看不见 |
| `PlayerControlScrim` | 播放器控件底色 | 全屏时画面就是背景,不存在 surface 可言 |
| `MentionLight` / `MentionDark` | 评论里 @提及 与链接 | 见下 |

**@提及色是一对值,不是一个。** 它标的是"这段是可点的",要跨动态取色保持稳定 ——
跟着 `primary` 走的话,换张壁纸同一段文字的含义就得重新学。但**不跟动态取色 ≠ 不跟深浅色**:
B 站原色 `#00A1D6` 在浅色 surface 上只有 2.82:1,而任何在浅色下达标的深蓝到了深色底
就掉到 2 以下。所以沿 HCT 的 tone 轴取两档(45 / 65),色相 233、彩度 51 保持不动 ——
**携带语义的是色相,承担可读性的是明度**。M3 对 `error` 也是这么处理的:动态取色下保持
静态,但仍然分深浅色两套。

### 1.3 字号(`ui/theme/Type.kt`)

只动行高和字距,**不动字号**。字号是组件排版的输入(按钮高度、列表行高、TabRow 的最小
宽度都由它推),改了会让一堆 material3 组件重新流一遍布局。M3 的排版页也是这么建议的。

两处偏离基线,都是中文特有的:

- **字距归零。** 基线给正文留了 +0.25 ~ +0.5sp,那是给 Roboto 小写拉丁字母调的。
  汉字本来就是等宽满格的方块,再撑开就散成一个个孤立的字。`label` 档保留正字距 ——
  那一档实际承载的多是数字和短英文(时长、倍速、`1080P`)。
- **小字号行高加高 2sp。** 14/20 在拉丁文下够,汉字墨迹几乎占满 em 框,同样行距看上去挤得多。
  `bodyMedium` 20→22,`bodySmall` 16→18,`titleSmall` 20→22。

**15 档 `*Emphasized` 也一并定义了**(1.5.0 才有)。它们和基线同字号、同行高,只抬一级字重——
M3 的强调靠字重和字宽,不靠放大,放大会把行高一起改掉,列表行就跳了。同一套 CJK 调整必须
应用到这 15 档上,否则一强调就退回 Roboto 的字距,中文会突然散开。

另外全表统一 `includeFontPadding = false` + 行高按字形居中:字体自带的上下 padding 是按
拉丁文 ascent/descent 算的,中文字形在其中偏上,不处理的话每个 `Text` 的视觉中心都比容器
中心高一点,"图标 + 文字"一行里最明显。

### 1.3b 封面的比例与圆角

**封面是 16:10,不是 16:9。** B 站上传的封面原图就是 16:10(1146×717 一类),按 16:9 摆
再 `ContentScale.Crop` 等于把上下各切掉一条 —— 而封面顶部常常正好是标题文字。PiliPlus 的
`common/style.dart` 里 `Style.aspectRatio` 同样是 16/10,它另外留了一个
`aspectRatio16x9` 只给播放画面的容器用。这两个比例在本项目的分工一样:
`VideoCover` 默认 16:10,`VideoScreen` 里包播放器的那个 `Box` 是 16:9。

**封面圆角 10dp,不走 `shapes` 主题槽**(`components/Media.kt` 的 `CoverCornerRadius`)。
它是图片本身的规格,不是容器的信息密度问题;取 10 是照 PiliPlus 的 `Style.imgRadius`,
比周围容器的 8dp 圆一点点看得出来,又不至于圆到像头像。

### 1.4 形状(`ui/theme/Shape.kt`)

值直接用 M3 圆角刻度,没有自作主张的数。**用哪一档由信息密度决定,不是由组件大小决定**
(这是 M3 从 M2 换掉的判据)。Bilby 到处是信息密集的列表,所以主力是 `small`/`medium`:

| 档 | 值 | 用在 |
|---|---|---|
| `extraSmall` | 4dp | 角标、小标签 |
| `small` | 8dp | 列表封面缩略图、队列条目 |
| `medium` | 12dp | 卡片、输入框、二维码占位 |
| `large` | 16dp | 弹窗、底部 sheet、输入框(圆一点更像"能输入") |
| `extraLarge` | 28dp | 只给听视频页那张大封面 —— 整屏唯一的主角 |

另外三档来自 1.5.0 补齐的刻度:`largeIncreased` 20dp(输入框,比 large 再圆一档,
让"这里能打字"更明显)、`extraLargeIncreased` 32dp、`extraExtraLarge` 48dp(听视频页那张大封面)。
在 1.4.0 上这三档没有主题槽,只能在组件处写死。

**嵌套时套用 optical roundness:外圆角 − 内边距 = 内圆角。** 两层用同一个半径,内层的角
看上去反而更方。队列条目外层 8dp、内边距 8dp,所以里面的缩略图用 4dp,不是 8dp。

### 1.5 间距(`ui/theme/Dimens.kt`)

M3 的间距系统是 8dp 起步的线性刻度,4dp 及以下算"嵌套单位"。这里按**用途**命名而不是按
倍数命名(`space150` 那种):倍数名在设计工具里有用,在代码里读起来只是把 `12` 换了个写法,
不解释任何东西。

`Hair 4` / `Tight 8` / `Cozy 12` / `Comfortable 16` / `Loose 24` / `Spacious 32`。

**全项目不出现裸 dp 字面量**,例外只有描边宽度和图标尺寸这类由组件规格定死的数。

`Dimens` 里另放反复出现的版式决定。最重要的一条是 `ListCoverWidth = 128dp`,它必须和
**列表标题的字号一起**算:

| 标题字号 | 封面宽 | 留给文字 | 一行汉字 |
|---|---|---|---|
| `bodyLarge` 16sp(旧) | 140dp | 360 − 32 − 140 − 12 = 176dp | 11 字 |
| `bodyMedium` 14sp(现) | 128dp | 360 − 32 − 128 − 12 = 188dp | 13 字 |

标题跟 PiliPlus 的卡片一样降到 14sp 之后,封面收窄到 128dp 反而更宽松。
"128dp 换不来一行字"那条旧结论只在 16sp 下成立,现在已经不适用 ——
再改这个数,把字号一起代进这道算术重做一遍。

---

## 2. 组件选型

### 2.1 四个"选一个"的控件,分工是这样的

这几个长得像,选错了用户学不会规律。判据来自 M3,但落到本项目的具体位置值得记下来:

| 控件 | 什么时候用 | 本项目用在 |
|---|---|---|
| **Segmented button** | 固定的几个选项,切换**视图或模式**,数量不随内容变 | 搜索的 普通/助理 |
| **SortRow**(`ui/components/SortRow.kt`) | **排序**:低强调、选项数可能随接口变、需要可横滚 | 评论的 最热/最新、空间投稿的 最新/最多播放、搜索结果的 综合/最多播放/最新发布 |
| **Chip** | 动态的、上下文相关的一组,可横滚,是"当前任务的分支路径" | 分 P 那一排(条数从 1 到几百都有) |
| **Tabs** | 同层级的内容分区 | 空间页三标签、播放页 简介/评论 |

**segmented button 与 SortRow 的判据**:排序永远是"换一种看同一批内容的顺序",不改变
内容本身,强调该低 —— SortRow 照 PiliPlus 的 `pages/search_panel/video/view.dart`(`SearchText`),
透明底、无描边、无容器,选中的一项只换字重(`labelLargeEmphasized`)和颜色,不放大不加框。
segmented button 是"选一种视图/模式",两个选项都需要同等的可见度,才配得上它的描边容器。
以前评论区、空间投稿页两处排序都用了 segmented button,视觉重量和"综合/普通搜索"这种真正
切模式的控件长得一样,读不出"这是排序,可以随便点着比较"的轻量感,这一轮改掉了。

M3 还有两条硬规矩:chip **不能单独出现一个**(必须成组),按钮一处**不超过 3 个**。

### 2.2 Primary tabs 还是 secondary tabs

- **Primary**:贴在 app bar 之下,代表页面的主内容目的地 → **空间页**的 投稿/动态/合集。
- **Secondary**:在内容区域内部再分一层 → **播放页**的 简介/评论。它们上面顶着播放器,
  不是顶栏。

这一条以前是错的(播放页也用了 `PrimaryTabRow`),两处指示条粗细一样,层级读不出来。

**播放页那条 secondary 指示条做了连续跟手**:用
`pagerState.currentPage + pagerState.currentPageOffsetFraction` 在相邻两个 Tab 的位置之间
插值,不是只认 `selectedTabIndex` 这个整数 —— 只认整数的话,滑动 `HorizontalPager` 时指示条
不跟手,要等翻页判定过了 50% 才追上去。宽度、高度、形状全部用 `SecondaryIndicator` 的默认值,
**通长铺满整格没有改**:试过取 `TabPosition.contentWidth` 让它贴合文字并居中,真机上位置偏了,
而且窄指示条本来就更像 primary 的做派。实现在 `ui/video/VideoTabs.kt` 的 `VideoTabs` 里。

### 2.3 动作栏是图标叠计数的等宽四格,**不用 ButtonGroup**

播放页的 点赞/投币/收藏/稍后再看:一行四格等宽,图标在上、计数在下,未选中 `outline`、
选中 `primary`。照 PiliPlus 的 `pages/video/introduction/ugc/widgets/action_item.dart`。

**这一条推翻了上一轮的 `ButtonGroup` 方案**,原因是量出来的,不是审美:

`ButtonGroup` 把四个动作排成横向药丸,一项分到 360 ÷ 4 ≈ 85dp,而 `"赞 12.3万"` 这样的
标签在 `labelLarge` 下要 90dp 往上,于是它一直处在溢出态,投币和收藏被收进一个 `⋮` 菜单里。

**溢出本身不是组件的毛病,是它被设计成这样的。** M3 button groups 页写得很清楚:

> Buttons at the trailing edge of the button group can be customized to collapse into an
> overflow menu **at smaller breakpoints**, and become visible again at larger sizes.

问题在于这句话里的 "smaller breakpoints" 对 Bilby 是**常态而不是边界**:手机竖屏
(compact,< 600dp)就是主力窗口,永远达不到"larger sizes"。所以在这里选 `ButtonGroup`
等于选了"投币和收藏永远藏在菜单里",而它们是这一行的主要动作,不是 trailing 的次要项。
图标叠计数把同样的信息压到 55dp 宽,四格全部露出,每格仍占满 90dp × 48dp 的触摸区。

代价是 `ButtonGroup` 自带的两件事要自己写,都在 `VideoTabs.ActionItem` 里:
选中态配色(三态:禁用 `outlineVariant` / 选中 `primary` / 常态 `outline`)、
触摸目标(`weight(1f)` + `heightIn(min = 48.dp)`,不是靠 `IconButton` 的默认尺寸 ——
那样计数文字会掉到触摸区外面)。

判据因此不是"`ButtonGroup` 好不好",是:**在 compact 断点上量一下最长的那个标签;
如果结果是关键动作会被折进溢出菜单,就别用它。** 标签短、项数少的场合它仍然合适。

### 2.3b 计数用图标,不拼成中文串

播放量/弹幕数走 `components/StatRow`(图标 14dp + `labelSmall`,色取 `outline`),
不再拼 `"12.3万播放 · 888弹幕 · 3小时前"`。理由是宽度:"播放""弹幕"各两个汉字约 24dp,
而列表行里一整行元信息只有一百多 dp;图标 14dp 说完同一件事,省下的宽度让日期不再被挤掉。
图标本身就是分隔,`·` 那一串在窄屏上会先于内容换行。

`outline` 而不是 `onSurfaceVariant`:这一行是列表里优先级最低的信息,和它上面的
UP 主名再拉开一档,扫列表时视线不会被数字勾住。PiliPlus 的 `StatWidget` 同样取 outline。

列表行的三行分工也照 PiliPlus 的 `video_card_h.dart`:
**标题(2 行)/ 发布时间 + UP 名(1 行)/ 计数图标(1 行)**。时间和人名合成一行,
是因为两者都是"这条是谁什么时候发的";拆成两行会把三行文字撑到四行,而封面高度固定,
多出来的那行只能让行距变松、看起来更空。

**稍后再看是只进不出的**。取消勾选不做任何事 —— 没有便宜的办法知道当前视频在不在列表里
(要判断就得拉整个列表),而移除本来就该在稍后再看页面做,那里是个列表,划掉一条是自然动作。
它走乐观更新:点了立刻切成已加入态,失败回滚并经 `BiliLog` 留一行能定位的日志。

### 2.4 按钮

一屏**只留一个** filled 或 tonal 按钮 —— M3 的原话是"要提升某个动作的可见度就换成
filled/tonal,不要放多个"。本项目的分配:

- 播放页:`听视频`(tonal)。播放本身由播放器承担,不占按钮名额。
- 搜索页:`发送`(`FilledIconButton`)。
- 播放页简介末尾:`找相关`(tonal)。它是整页唯一的关联入口,而且必须由用户显式发起,
  所以给它一个真正的按钮,不是一行看起来像链接的字。
- 重试、取消、清空这类:一律 `TextButton`。**一次失败不该被渲染成一个需要下决心的按钮。**

### 2.3c 分区:contained list 还是分割线

播放页简介里三块内容依次是 动作栏 → 播放队列 → 找相关。两种分法都用上了,**判据直接来自
M3 的 lists 与 divider 两页,不是自己拟的**:

**M3 lists → Gaps & dividers** 给的是一条有方向的规则,不是二选一:

> Use gaps for **contained** lists. Gaps leverage expressive shape and containment tactics.
> Limit dividers to **uncontained or complex** lists, only when a stronger visual separation is necessary.
> (Caution: Limit the use of dividers to uncontained lists.)

也就是说 **M3 Expressive 的默认答案是 containment,分割线是列表没有容器时的退路。**
播放队列因此装进 `Surface(surfaceContainer, shapes.large)`,条目之间只留 gap、不画线。
PiliPlus 的 `introduction/ugc/widgets/season.dart` 是同一个做法
(`Material(color: onInverseSurface, borderRadius: 6)`),两边对上了。

**它是 contained list,不是 card。** 这个区别要守住:M3 cards 页把 card 定义为
"content and actions on **a single topic**"、"entry points into deeper levels of detail or
navigation",并且明确写着

> **Don't force content into cards when spacing, headlines, or dividers would create a simpler visual hierarchy.**

队列不是一个可以点进去的主题卡片,它就是一组条目。所以实现用 `Surface`,**不要换成 `Card`** ——
换过去会带上 elevated/filled/outlined 三种变体的语义和整块可点的预期,都不是我们要的。

**「找相关」保留 `HorizontalDivider`**,依据是 divider 页对 full-width 的两条定义:
"separate larger sections of **unrelated** content",以及 "separate **interactive** areas from
**non-interactive** areas"。找相关是一句说明加一个按钮,和上面那组条目既不同类也不同交互性质,
正好落在这两条上。同一页只有这一条 full-width divider,也满足文档那句
"Use full-width dividers **sparingly**"。

三条附带的坑:

- **底色不要用 `surfaceContainerLow`。** 浅色主题下它和页面的 `surface` 只差一点,
  真机上那圈边界几乎看不出来,等于白做了一个容器。用 `surfaceContainer`。
- **容器内的列表行底色必须透明。** `CompactVideoRow` 以前未选中态画死 `surface`,
  放进 `surfaceContainer` 的容器里,每一条未选中的行都会变成一块比容器亮的补丁,
  几十条排下来就是一条条横杠。现在是 `Color.Transparent`,由所在容器决定。
- **不要往队列条目之间加分割线。** 上面那条 Caution 就是说这个;而且 divider 页还补了一句
  "List items with repetitive formats may not require an inset divider, in which using only the
  margin between items is acceptable" —— 队列条目正是重复版式。

圆角照 optical roundness:外 `shapes.large` 16dp − 内边距 8dp = 内层条目的 `shapes.small` 8dp。

### 2.4b 输入框:`SearchField`,既不是 `OutlinedTextField` 也不是 `SearchBar`

**不用 `OutlinedTextField`**,三条具体理由:

- 它是给表单里一列字段用的,56dp + 一圈描边,一屏一个的搜索框顶着这个规格,
  视觉重量比它旁边的内容还大。
- **回车不搜索。** `singleLine = true` 只是不换行,不设 `ImeAction`,键盘上那个键什么都不做。
  而 DESIGN 2.2 快路的原话就是"输入直接回车 = 原始 B 站搜索" —— 这条以前根本没实现,
  真机上按搜索键没有任何反应。
- 没有清空按钮,改一次搜索词要按十几下退格。

**不用 material3 1.5.0 转正的 slot-based `SearchBar` / `SearchBarState`**(这一轮对着 M3
search 页重新核过,结论不变,而且理由比原来硬)。文档对这三个入口的定义是按**位置**给的:

> **Search bar** — a persistent and prominent search field **at the top of the screen**.
> Use to search contents in a specific view.
> **Search app bar** — use this app bar variant when search is the primary, global function.
> **Search icon button** — use when search is a secondary action or not the main focus.

Bilby 的搜索框**固定在屏幕底部**(对话式:输入在下,一轮轮结果在上面滚),三个入口一个都不是。
再加上 `SearchBar` 自带"聚焦即展开成全屏建议列表"的行为,而 DESIGN 2.2 的结果页只有结果、
没有建议也没有热搜。用它等于把这个 app 定死的形态换掉,而不是换一个控件。
**这是形态问题,不是组件新旧问题,新版本转正不构成重新考虑的理由。**

取的是 PiliPlus 搜索页(`pages/search/view.dart`,`border: InputBorder.none` 的裸
TextField 挂在顶栏上)与 M3 填充式输入的折中:填充 `surfaceContainerHigh` +
`largeIncreased` 圆角、无描边、48dp 高、带清空、`ImeAction.Search`。
Bilby 的输入框不在顶栏里,所以需要一个能被认出来的容器,不能像 PiliPlus 那样完全裸着。

**这一条说的是全局搜索入口**(搜索 tab)。页面内部的次要搜索(比如空间页只搜这位 UP 主
自己的投稿)不适用同一形态:那不是"搜索"这个 tab 要做的事,只是当前页面内容的一个过滤
动作。M3 对这种情况给的正好是 **search icon button**——收起时只是顶栏上一个图标,点一下
才展开成输入框(展开后复用同一份 `SearchField` 组件,回车才发请求)。空间页投稿 tab
是这个用法的例子,见 `ui/space/SpaceScreen.kt` 的 `BilbyTopBar` actions。

### 2.5 顶栏

只用 small 这一档。`medium/large flexible` 是给"标题本身是内容"的页面用的(相册、文章),
Bilby 每一页的标题都只是个路牌,给它三行高度是浪费首屏。

顶栏统一在 `RootTabs` 那一层给,不由各页自己贴 `systemBars` padding —— 以前三个页面三种
写法、三种留白,滚动时内容还会压到状态栏文字上。`TopAppBar` 自带 insets 处理。

三个 tab 的顶栏动作:动态是 `设置`、搜索是 `新会话`、稍后再看是 `清空已看完`。

动态页**没有内容相关的动作**(这一页能做的只有往下看,放刷新按钮等于把下拉刷新那套仪式
换个位置摆回来);那里的齿轮是设置页的入口。设置**不进底部导航**:底部三格是"我要去哪",
设置不是目的地,只能挂在某个顶栏上,而动态页是启动后的第一屏。

顶栏标题是**路牌,不是内容**。空间页因此固定写"个人空间",UP 的名字归下面的头部区 ——
两处都印名字会在同屏出现两遍,而顶栏那一份还会被截断得更早。

### 2.6 导航栏

3 格,正好落在 M3 的 3–5 个目的地的下限上。两条要守住:

- **选中用实心图标、未选中用线性图标**,不只是变色。图标形态本身就是一路状态指示,
  只靠颜色的话色觉障碍用户看不出当前在哪一格。
- **标签不能省。** M3 明确列为 don't。

### 2.7 共享组件在 `ui/components/`

新写页面前先看这里有没有现成的。已有:

- `VideoRow` / `VideoRowUi` —— **一条视频在列表里的样子,只有这一份**。
  动态、搜索、空间、稍后再看、助理答案以前各写了一份几乎一样的 Row,封面宽度、行距、
  截断行数各差一点点,滑过去能看出接缝。参数是扁平的展示字段而不是某个 data 层模型:
  五个调用方的模型各不相同,让 UI 组件认识其中任何一个都会把 data 层的形状焊进视图层。
- `CompactVideoRow` —— 队列、合集分集用的矮行。出现在已经有主内容的页面里,不该和主列表
  抢视觉重量。
- `ListCover` / `VideoCover` / `SquareCover` / `Avatar` / `MediaBadge` ——
  **所有 B 站图片都必须走这里**:图床有防盗链,不带 `Referer` 一律 403,部分接口还会返回
  `//` 开头的协议相对地址。这两件事以前散在七八个 `AsyncImage` 调用点上,漏一个就是一张裂图。
- `StatRow` —— 播放量/弹幕数/日期那一行,见 §2.3b。
- `SeekBar` —— **播放进度条只有这一份**,播放页和听视频页共用,只换两个颜色。
  手写而不是 `Slider`:`Slider` 的滑块是 20dp 见方的实心块,压在画面上像个跑进视频里的
  表单控件。槽 3dp、滑块 5dp(拖动时 8dp),但整条的可点高度是 24dp ——
  **视觉高度和触摸高度必须分开**,3dp 高的东西在手机上按不中。

  **它故意不满足 M3 sliders 的一条硬性要求,别照文档把它"修"回去。** 原文是:

  > Changes made with sliders must **take effect immediately**, so people can understand the
  > effects of their selection as they're moving the slider.

  拖动进度条时我们**不**逐帧 `seek`,只更新本地位置,松手才真跳一次 —— 每帧 seek 会让播放器
  不停丢缓冲重新起播,表现是拖不动。这条要求成立的前提是"应用这个值很便宜",而视频寻址不是。
  M3 真正要的是**即时反馈**,这一点我们照做了:拖动中播放器上浮出 `位置 / 总长` 的覆盖层、
  听视频页左侧的时间读数实时跟着走。**这也正是它不该叫 `Slider` 的原因**——
  它不是一个滑块控件,是一条视频进度轨。
- `SearchField` —— **搜索输入框只有这一份**,搜索页底栏和空间页内搜索共用。见 §2.4b。
- `SortRow` —— **排序控件只有这一份**,评论区、空间投稿、搜索结果共用。见 §2.1。
- `LevelBadge` —— B 站 LV 徽章,手绘 `Canvas`,照 PiliPlus 的 `common/widgets/svg/level_icon.dart`
  逐坐标移植。评论区名字后面、空间页头部共用。底色是 B 站等级色,不跟主题,见 §4.2。
- `FullScreenLoading` / `FullScreenError` / `EmptyState` / `ListFooter` / `InlineProgress`
- `BilbyTopBar` / `SectionHeader`

loading 的规矩:**首屏和翻页用不同的粗细** —— 首屏占整屏,翻页只占一行高度。

---

### 2.7b 评论区

照 PiliPlus 的 `pages/video/reply/widgets/reply_item_grpc.dart` 重做过一轮。四条是**可读性
的实际杠杆**,不是风格偏好:

- **行高,不是字号。** 正文用 14sp / **24sp 行高**(PiliPlus 是 `height: 1.75, fontSize: 14`)。
  汉字墨迹几乎占满 em 框,`bodyMedium` 那档 14/22 在一条五六行的长评论里会糊成一片。
  **不要去改 `Typography.bodyMedium`**:那一档还给列表标题、队列条目用着,它们要的是紧凑。
  行高按**这段文字有多长**定,不按字号定。
- **用户名用 `outline`,正文才是满对比度。** 反过来的话,一屏几十条评论里视线全被每条开头
  的名字拽住。PiliPlus 也是把 `member.name` 画成 outline 的。
- **表情内联进文字流**,走 `InlineTextContent`。以前是正文里留着 `[doge]` 三个字、底下另起
  一行摆一排图标 —— 读者得自己对应回去,而且同一个表情出现两次时下面那排根本对不上。
- **楼中楼是一个容器装一组,不是一条一张卡片。** 每条各套一个 `Surface` 的话,三条回复就是
  三块圆角色块摞着,比主楼还抢眼。

主楼之间画 **inset 分割线**(左端对齐正文、即头像后缘)。依据是 M3 divider 页:inset 用于
"分隔一个区块内部的相关内容"并要求对齐头像这类锚定元素,评论列表正是那一页举的"一列邮件"
的例子;full-width 留给不相关的大段内容。这也是 §2.3c 那条"contained 用 gap、uncontained
用线"的另一半 —— 评论主列表是 uncontained 的。

**所有 B 站图片必须走 `components/Media.kt`**(`BiliAsyncImage` / `ListCover` / `Avatar`)。
评论区的配图和表情曾经直接写 `AsyncImage(model = url)`,**漏了 Referer,图床防盗链一律 403**,
表现是评论里该有图的地方一片空白。§2.7 那条"所有 B 站图片都必须走这里"就是为这个立的,
这次是它第二次被踩。

**配图点得开、能放大**(`components/ImageViewer.kt`,对应 PiliPlus 的 `GalleryViewer`)。
两个真机上踩到的点:

- `Dialog` 光给 `usePlatformDefaultWidth = false` 不够,还要 **`decorFitsSystemWindows = false`**,
  否则窗口避开系统栏,黑底铺不满,顶端会露出底层的评论列表。铺满之后关闭按钮要自己
  `windowInsetsPadding(systemBars)`。
- 缩放/位移状态**按页存并在翻页时归位**。不归位的话放大着翻到下一张,下一张继承上一张的
  缩放和位移,看起来就是"图开在了屏幕外面"。

**接口层缺的一块**:`ReplyPictureDto` 只解析了 `img_src`,没有 `img_width`/`img_height`,
所以单张配图只能给一个固定 4:3 的格子裁着显示,没法像 PiliPlus 那样按原图比例定尺寸。
补这两个字段要动 `api/dto`。

### 2.8 设置页:一条判据,不是一张清单

**设置只调整"怎么做",不调整"做不做"**(DESIGN 2 节)。往设置页加一项之前先过这一条:

- 编解码策略、SponsorBlock 的类别与服务器、助理的 base URL / key / model —— 都是"怎么做",可以放。
- 推荐流、相关推荐、自动续接 —— **一个开关都不给**。它们能被开关掉的那一刻,
  DESIGN 1.3 的结构约束就退化成了自制力工具。
- 画质、倍速、连播、顺序/随机 —— 留在播放页。**在那里改即是改全局默认**
  (`playback_auto_next`/`playback_shuffled`/`player_default_quality` 都在写 DataStore),
  所以设置页不重复放一份,也就不存在"两处能改同一件事"。
- 定时关闭没有值得记住的默认值,不做。
- **不给 WSOLA 开关**:它默认开且有透明回退,开关能解决的问题已经被回退覆盖。
  设置页只**显示**当前生效的算法和回退原因。

另外三条这一页特有的:

- **诊断信息显示真值,不显示配置值。** 解码器名(`c2.qti.avc.decoder`)和倍速算法都从
  `PlayerFactory.techInfo` 这个 StateFlow 读,它跟着流走 —— 只在进页面时取一次快照,
  回退发生后显示的就是错的。
- **只列本机真支持的选项。** 编解码那一节按 `player/DeviceCodecs.hardwareDecodableCodecIds`
  过滤,列一个选了也只能软解的选项等于让用户自己给自己挑一条掉帧的路。
- **改动的生效时机要写在副标题里。** 编解码偏好影响 playurl 的 `codecid`,改完只对下一个
  视频生效 —— 不为一个设置项打断正在看的东西,但也不能让用户以为没生效。

**凭据不进 UI 也不进日志。** API key 的行永远只显示"已配置 / 未配置",不显示遮蔽后的原文:
遮蔽只挡眼睛,截图和录屏挡不住。编辑框里默认 `PasswordVisualTransformation`,给一个显形
按钮(长串 key 手输时看不见没法核对),但默认态必须是遮住的。`SettingsViewModel` 全文
没有一处 `BiliLog`,新增分支时也不要加。

**设置的落盘一律 `viewModelScope.launch(NonCancellable)`。** 这一页的每一次改动都可能紧跟着
一次返回,而返回会清掉 ViewModel、连带取消 `viewModelScope`;DataStore 的 `edit` 是挂起函数,
取消在它完成之前到达就是**改动被丢掉**。真机上复现过:勾一个类别立刻返回,再进来还是原样。
同一条适用于播放页的"顺序/随机"和画质落盘。

## 3. 无障碍

- **触摸目标 48dp 见方是下限。** 踩过两处:播放页的 点赞/投币/收藏 以前是裸的
  `Column.clickable`,横向不到 40dp;评论区的 点赞/回复/删除 三个按钮都是 32dp 见方,
  在正文旁边一行密排着,而"删除"就在里面。现在前者用 `sizeIn(min = 48.dp)` 撑开,
  后者用默认尺寸的 `IconButton`(自带 48dp 触摸区),视觉上仍靠 16dp 的图标保持轻。
- **整行可选的控件用 `Modifier.selectable` / `toggleable` + `role`**,不要给 `RadioButton`
  单独挂 `onClick`。前者让整行成为一个可选中的语义节点(读屏念"单选按钮,已选中,2 枚"),
  后者会让读屏把控件和文字当成两件无关的东西,而且只有那个小圆点能点。
- **`contentDescription` 的判据是"读屏会不会念重复"。** 封面、头像、导航栏图标一律传
  `null` —— 它们旁边就是同一条信息的文字,再念一遍等于每条读两遍。真正需要描述的是
  没有可见文字的按钮:`"移出稍后再看:${title}"`、`"取消点赞"`。
- **返回箭头用 `Icons.AutoMirrored`。** manifest 里 `supportsRtl="true"`,RTL 下必须翻过来。
- **不要用 `color.copy(alpha = 0.15f)` 兑容器底色。** 兑出来的对比度取决于底下是什么,
  深色主题里经常糊成一团。容器色和文字色成对取自同一组 role(`primaryContainer` /
  `onPrimaryContainer`)。

---

## 4. 本项目特有的例外

### 4.1 机器可读的图形永远不跟主题 —— 登录二维码

**这是全项目最硬的一条,踩过一次。**

扫码器普遍只认"深码块 + 浅底"这一种极性。深色主题下如果取 `onSurface`/`surface`,
得到的是浅码块画在深底上,B 站客户端直接报"未成功解析到二维码"。所以:

- 码块固定纯黑,底固定纯白(`FixedColors.QrBackground` / `QrCodeImage.kt` 里的前景色)。
- **静区(四周 4 个模块宽的浅色边)是识别的一部分,不是留白。** 少了扫描器定位不到三个角。
- 二维码永远画在一块白色 `Surface` 上,而不是直接落在主题背景上。位图自带静区,但静区
  之外还是主题色;深色主题下那圈白边和黑底的硬边界会让部分扫码器把边界当成定位图形。
- 绘制时关掉双线性插值、按整数倍缩放。模块边缘被平滑成灰阶会掉识别率。

推广成一条规矩:**只要一个图形的消费者是机器而不是人,它就不进主题系统。**
以后加条码、NFC 提示图之类同理。

### 4.1b 播放器控件的底是渐变,不是一条半透明黑

`FixedColors.PlayerControlScrim` 铺成一整条会把画面横着切一刀 —— 硬边落在画面中间,
比控件本身更显眼。改成自下而上的三档渐变(透明 → `PlayerControlScrim` → `0xB3000000`),
只在最需要对比度的地方(文字所在的下缘)压到最暗。B 站与 PiliPlus 的播放器都是这个做法。

全屏另有一条顶栏(返回 + 标题),同样是渐变,方向相反。它只在全屏时出现:全屏下系统栏
是隐藏的,没有别的东西说明"在看什么"和"怎么退出";竖屏不显示,那里标题就在播放器下面第一行。

### 4.2 反成瘾靠结构,所以有些"体验优化"故意不做

DESIGN 1.1 的四个机制在界面层的落点。这些不是没想到,是**想过并且否决了**:

- **下拉刷新只在助理模式下拒绝。** 该项曾整体否决,理由是"下拉刷新是变比率奖励的标准交互"。
  该理由对本项目不成立:变比率奖励要求每次操作都可能产生新结果,而此处刷新返回的只有关注
  UP 主在此期间的实际投稿。助理模式例外 —— 一次下拉对应一次真实的模型请求。
- **没有骨架屏。** 骨架屏是在假装内容马上就到,把等待包装成期待。首屏就是一个转圈。
- **空态不给"去逛逛"。** 空态只说事实。任何把人推回内容池的引导都是在造推送式入口。
- **助理答完就退场,不留"再找一批"。** 那正是变比率奖励。
- **没有红点、没有未读计数、没有徽章。** `Badge` 组件存在,不用。
  **这条针对的是制造时效焦虑的注意力标记**——红点和未读数的作用是"有新东西,你该回来看",
  是变比率奖励的另一种形状。**LV 徽章(`ui/components/LevelBadge.kt`)不是这一类**:它标的
  是用户的身份属性,跟评论区/空间页有没有新内容无关,不制造"回来看看"的诱因,也不会随时间
  自己变化去吸引重复查看。同一条判据适用于以后任何类似的"标记":问的是它有没有在暗示
  "现在有新东西",不是它长得像不像徽章。

### 4.3 两条不许重画的结构

只重画,不改结构:

- **听视频是播放页内的一个状态,和全屏同构**,不是导航目的地。它不离开组合、播放器不换、
  进度不交接。
- **播放器归 `AudioPlaybackService` 所有**,页面只连上去。UI 只读状态、发命令,
  不 `prepare`、不 `release`。

这两条是反复返工才定下来的。改播放页布局时不要顺手把 `ListenScreen` 提成一个 NavKey。

**播放器三态(内嵌 / 全屏 / 听视频)都是同一个播放器的封装壳,播放器控件归控件层,
不归页面 chrome。** 字幕轨切换第一版被放进了 `BilbyTopBar` 的 actions,理由是"顶栏有地方
放"——但顶栏是页面装饰,三态不共享它;字幕轨和倍速、进度条是同一类东西,该跟着播放器控件
的生命周期走。改成了浮在封面/画面右上角:听视频模式下封面占的正是播放器画面的位置,浮在
它上面的按钮就等价于全屏时浮在画面上的控件(`BilbyPlayer.kt` 的控制条同样有一份)。
判据是问一句:这个控件属于"这个播放器现在在做什么",还是"这个页面长什么样"——前者跟播放器
走,后者才能放进顶栏。将来控件自动隐藏时,字幕按钮要跟着控件层一起隐藏,不是跟着顶栏。

---

## 5. 已知待办

- **`res/values/colors.xml` 的 `launch_background` 和 `ui/theme/Color.kt` 的 surface
  必须手工保持一致。** XML 主题读不到 Compose 的 `ColorScheme`,改一处要改另一处。
  现在是 `#FAF8FF` / 夜间 `#121318`。
- **空间页没有头图。** PiliPlus 的 `pages/member/widget/user_info_card.dart` 顶上有一张
  banner,Bilby 的 `data/SpaceProfile` 目前不带 `top_photo`,补它要动 `api/dto` 和
  `SpaceRepository`。头部区的布局已经按"将来会有一张头图"留好了结构(整块是一个 Column)。
- **列表行的封面下方有一小段空白。** 封面固定 128dp 宽(高 80dp),而右边三行文字在
  fontScale > 1 的机器上会高过它,行高由文字决定,封面底下就空出十几 dp。
  PiliPlus 的做法是让封面 `fillMaxHeight` 跟着内容走(它的卡片宽度因此是变的)——
  但那样"找相关"那种带多行推荐理由的行会让封面变得很大,所以这一轮没有照做。
  真要修,得先把带 `note` 的行拆成另一个组件。
- **动效目前只是"有了唯一来源",还没有逐处替换。** `MotionScheme` 已经挂进主题,
  但 `AnimatedVisibility(fadeIn()/fadeOut())` 这类手写 spec 还散在播放器控件和
  SponsorBlock 提示里。下一轮应当统一改成
  `MaterialTheme.motionScheme.defaultEffectsSpec()`。
