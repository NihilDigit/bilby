# Bilby

一个自用的 Android bilibili 第三方客户端。

它不是一个"功能更全"的客户端，恰恰相反：**它靠结构而不是靠自制力来阻止无限刷。**
被拿掉的东西和留下的东西一样重要——

- **没有推荐流。** 首页只有关注的 UP 的动态，看完就是看完了，列表有底。
- **没有相关推荐、没有自动连播。** 播放结束就是播放结束，不会有下一个自动接上。
  播放页里原本放相关推荐的位置，放的是当前视频所属的合集和这位 UP 的其它投稿——
  一个由用户显式选定的、有限的集合。
- **想看别的要自己说出来。** 找视频走搜索，或者走内置的搜索助理：你描述想看什么，
  它去搜索、看简介和评论，然后给你几个带理由的结果。它拿不到你的观看历史，
  也不会拿到——它的上下文里只有你这次说的话。

对 bilibili 官方数据不做任何对抗：历史记录、心跳、投币、收藏、点赞都如实上报。
拿掉的只是**推给你的那一部分**。

## 功能

- 关注动态、搜索、稍后再看、UP 空间
- 播放：全屏、清晰度切换、倍速、长按快进、拖动进度、锁定、双击暂停、分 P
- 听视频：同一个播放器换一层 UI，息屏后台播放、播放队列、顺序/随机、定时关闭
- 评论：查看、排序、展开楼中楼、发送、点赞、删除
- SponsorBlock 自动跳过赞助片段（默认开启）
- 搜索助理：LLM 驱动的多轮会话式找片

## 构建

```
./gradlew installDebug     # 装 dev.bilby.debug
./gradlew assembleRelease  # 走 R8，装 dev.bilby，两者可共存
```

debug 版的 LLM 凭据从 `local.properties` 注入（不进 git）：

```properties
LLM_BASE_URL=https://.../v1
LLM_API_KEY=sk-...
```

release 版拿到的是空串；运行期的真相来源是应用内设置，随时可改。

登录走 **TV 端扫码**。网页端 cookie 在写操作上会被风控挡掉（点赞收藏投币一律
`-403 账号异常`），换成 TV 端 access_key 之后才能正常写入——这条路是照
PiliPlus 的实际做法走的。

- compileSdk / targetSdk 37，minSdk 29
- Compose + Material 3、Navigation 3、Media3、Room、Ktor 3、kotlinx.serialization

## 许可

GPL-3.0-or-later，见 [LICENSE](LICENSE)。

**这是被要求的，不是选的**：与 bilibili 接口打交道的部分——WBI 签名、AppSign、
设备指纹、TV 扫码登录、playurl 参数、上报与写操作——大量参照并移植自
[PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus)，而 PiliPlus 是 GPL-3.0。
这些接口的公开文档常年落后于线上实际行为，成熟客户端里跑通的做法才是可靠依据。
