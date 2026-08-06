# Bilby

Android bilibili 第三方客户端，单用户自用。实现依据是仓库根目录的 `DESIGN.md`
（**本地文件，不进 git**）——动结构之前先读它。

## 产品约束（不是可以商量的实现细节）

反成瘾靠**结构**实现，不靠提示词、不靠计时器、不靠劝阻文案。以下几条一旦被绕过，
这个 app 就没有存在意义了：

- 不做推荐流，不做相关推荐，不做自动连播。列表必须有底。
- 播放页里原本放相关推荐的位置放的是**合集 / 该 UP 投稿**——一个用户显式选定的
  有限集合。播放队列因此不是"听视频专用"的东西，别给它加特殊处理。
- 队列播完即停，不回绕、不循环、不从推荐池续接。允许连播的前提就是集合有限，
  一旦回绕，"播完"这个决策点就被永久取消了。
- 搜索助理的上下文里**只有用户这次说的话**，绝不注入观看历史。
- 对官方数据如实上报（历史、心跳、投币、收藏、点赞），对内不做个性化。

## 硬约定

**接口以 PiliPlus 为准。** 源码在 `PiliPlus/`（本地，已 gitignore）。公开文档常年
落后于线上行为，凡是文档和 PiliPlus 打架，照 PiliPlus 做。已经交过学费的三例：
bili_ticket 的参数必须放 query 不能放 body；网页端 cookie 在写操作上被风控挡死，
必须走 TV 端 access_key；收藏 `batch-deal` 必须同时带 `add_media_ids` 和
`del_media_ids`（没有就传空串），`resources` 形如 `aid:2`。

`notes/auth-model.md` 里明确写了 cookie→access_key 这条路今天返回 `-101`，
**不要重新实现它**。

**日志纪律。** 每一处被 `runCatching` 吞掉的失败都要经 `BiliLog` 记下
path + code + message。**凭据永远不进日志**——SESSDATA、bili_jct、access_key、
LLM key、cookie 的值一律不记，cookie 只能记 key 名。

**唯一 API 出口是 `api/BiliClient.kt`。** 五条路径各有用途，别绕过它发请求：
`rawGet` / `rawPostForm`（自动带 csrf）/ `rawPostQuery`（passport 系只吃 query）/
`appPostForm`（access_key + AppSign，不带 cookie，app UA）/ `appPostQuery`（TV 登录）。

**乐观更新与重拉互斥。** 点赞投币收藏做乐观增减，不重新拉取——热门视频的数字
会闪两次。

## 架构里容易搞错的地方

- **播放器只有一个**，归 `player/AudioPlaybackService`（`MediaSessionService`）所有。
  UI 通过 `MediaController` 控制；画面必须接在同进程直接拿到的 `currentPlayer` 上，
  因为 `MediaController` 没有 `COMMAND_SET_VIDEO_SURFACE`。页面离开只断开连接，
  **不要 release 播放器**。
- **听视频是播放页内的一个状态，和全屏同构**，不是导航目的地。页面不离开组合、
  播放器不换、进度不交接，所以没有任何生命周期要处理。这一条返工过三次：
  服务上的 listening 标志、导航层的"弹出还是被覆盖"判断，全都是错的。
- **分 P 和合集是两个东西**，别混。
- 随机播放只改播放顺序，**不重排显示的列表**——列表跟着高亮滚动就行。
- Navigation 3 的 backstack 就是一个 `SnapshotStateList<NavKey>`，没有独立图定义；
  `entry` 是 `EntryProviderScope` 的成员扩展（不需要 import），`onBack: () -> Unit`。

## 工具链

AGP 9 内置 Kotlin 支持，**`org.jetbrains.kotlin.android` 插件会直接报错**；KGP / KSP
版本在根 `build.gradle.kts` 的 `buildscript` classpath 里覆盖（那里用不了 version
catalog 访问器），改 `libs.versions.toml` 要同步改那边。

M3 Expressive 已并入 material3 主线，但 `MaterialExpressiveTheme` 是 `internal`，
用 `MaterialTheme`。`MaterialTheme` 不设 `LocalContentColor`，内容要包在 `Surface` 里，
否则深色下黑底黑字。

Coil 3 必须显式注册 `OkHttpNetworkFetcherFactory`，否则静默不加载；B 站封面走 `http://`
会被明文策略挡掉，映射时转成 https（不要开 `usesCleartextTraffic`）。

kotlinx.serialization 默认不输出等于默认值的字段，发给 LLM 的 tool schema 需要
`encodeDefaults = true`。

`app/proguard-rules.pro` 故意很短：会被 R8 破坏的依赖都自带 consumer rules，
本项目也没有按名字反射的代码。**新增按名字反射的代码时，规则要连同那段代码一起加。**

## 构建与验证

```
./gradlew installDebug          # dev.bilby.debug
./gradlew assembleRelease       # dev.bilby，走 R8
./gradlew testDebugUnitTest
```

改动要在真机上冒烟跑一遍，看端到端行为。**熄屏时 `adb shell input tap` 是空操作**，
会被误判成"按钮没反应"，先 `input keyevent KEYCODE_WAKEUP`；`adb shell input text`
会被拼音输入法吞掉。

多个 subagent 并行时，**Gradle 编译是独占资源**——同时编译会把 Kotlin daemon 撞崩。

测试只在能抓到问题时写。队列逻辑（`player/PlaybackQueue.kt`）、WBI 签名、流选择、
agent 循环的协议正确性值得测；UI 和网络胶水不值得。
