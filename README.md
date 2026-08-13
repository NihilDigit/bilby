<p align="center"><img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/icon.png" alt="Bilby" width="96"></p>

# Bilby

[English](README.en.md)

[![APK](https://raw.githubusercontent.com/NihilDigit/bilby/badges/apk-size.svg)](https://github.com/NihilDigit/bilby/releases/latest) [![Android 10+](https://img.shields.io/badge/Android-10%2B-4A5C92?style=flat-square&logo=android&logoColor=white)](#安装) [![SLSA Build L3](https://raw.githubusercontent.com/NihilDigit/bilby/badges/slsa-l3.svg)](https://github.com/NihilDigit/bilby/attestations) [![GPL-3.0](https://img.shields.io/github/license/NihilDigit/bilby?style=flat-square&color=4A5C92&logo=gnu&logoColor=white)](LICENSE)

![Kotlin](https://img.shields.io/badge/Kotlin-4A5C92?style=flat-square&logo=kotlin&logoColor=white) ![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4A5C92?style=flat-square&logo=jetpackcompose&logoColor=white) ![Material 3 Expressive](https://img.shields.io/badge/Material%203%20Expressive-4A5C92?style=flat-square&logo=materialdesign&logoColor=white)

Bilby 是一款安卓原生 bilibili 客户端，提供无推荐流的订阅式设计、优化的听视频功能和 Agentic 搜索推荐能力。

> **本项目仍在开发中。** 界面与接口层均在持续改动，不保证稳定性与兼容性。

## 订阅式交互体验，远离推荐流打扰

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/feed.png" width="240" height="528" alt="首页：关注动态">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/video.png" width="240" height="528" alt="播放页与播放队列">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/in-video.png" width="240" height="528" alt="点按后 Agent 基于当前视频检索">
</p>
<p align="center">首页只收录关注的 UP 主；播放队列来自所属合集与 UP 主投稿；如有需要，Agent 可以基于当前视频搜索相关内容</p>

## 重新设计的原生听视频能力支持

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/listen.png" width="240" height="528" alt="听视频界面">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/lyrics.png" width="240" height="528" alt="逐句字幕">
</p>
<p align="center">与播放共用同一播放器，随时切换；支持息屏后台、锁屏与线控、定时关闭；字幕逐句跟随，点句跳转</p>

## 用 Agent 搜索沙里淘金

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/agent-running.png" width="240" height="528" alt="Agent 检索过程">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/agent-answer.png" width="240" height="528" alt="Agent 返回的候选视频">
</p>
<p align="center">检索全站，阅读简介与热评，返回候选并逐条说明理由</p>

## 按窗口宽度分栏

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/tablet-feed.png" width="380" height="238" alt="平板上的动态页：左侧时间线，右侧最常访问">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/tablet-video.png" width="380" height="238" alt="平板上的播放页：左侧画面，右侧简介与评论">
</p>
<p align="center">动态页把「最常访问」挪到次栏，播放页左侧画面、右侧简介与评论；手机布局不受影响</p>

## 功能

已完成：

- [x] 信息流：关注动态（可屏蔽 UP 主）、最常访问的 UP 主（正在直播的可直接进直播间）、完整关注列表、稍后再看
- [x] 历史记录：断点续播；可删除单条、多选批量删除、清空已看完或全部；可暂停记录
- [x] 动态：视频、转发、图文、文字、直播、专栏与预约，正文与配图原样展开，表情内联显示，预约可加入日历
- [x] 专栏站内阅读：正文、配图、引用卡片、代码块与公式
- [x] UP 主空间：投稿、动态、合集/系列，支持空间内搜索
- [x] 播放：全屏、清晰度、倍速（WSOLA）、双击快进退与暂停、长按快进、拖动进度、滑动调亮度音量、锁定、多 P 与分 P 续播；播放进度云端同步；默认画质按 WiFi 与计费网络分设
- [x] 听视频：与播放共用同一播放器，息屏后台，通知栏、锁屏与耳机线控可控，定时关闭；队列可随机播放；字幕轨即逐句歌词，点句跳转
- [x] 直播：进直播间观看，弹幕、清晰度、全屏与醒目留言
- [x] 离线缓存：从播放队列勾选、选清晰度、可全选，弹幕一并缓存；并发数可设，下载中显示速度；缓存列表在「我的」，长按可批量删除，播放不走网络，队列即整个缓存库；断网时播放页显示缓存的标题、简介与计数
- [x] 弹幕：滚动、顶部、底部三类，跟随播放时钟，透明度可调；播放页可发送，发出即上屏；直播间在聊天栏下方发送
- [x] 字幕：多轨可选，普通播放时在画面底部，听视频时作为逐句文稿；支持 AI 字幕修复
- [x] 评论：浏览、排序、展开楼中楼、发布与回复、点赞、删除，时间戳可点击跳转，配图可放大并左右翻页
- [x] 互动：点赞、投币、收藏、关注；联合投稿逐个署名并可分别关注；动态可点赞并回复评论
- [x] 收藏夹管理：新建、改名、删除，可改简介与公开性；夹内取消收藏可撤销
- [x] 关注分组与黑名单：分组可增删改，可给单个 UP 主设置；可拉黑与解除拉黑
- [x] Agent：对话式全站搜索与播放页「找相关」，读简介与热评，候选逐条说明理由；支持多轮追问，执行轨迹可见，回复按 Markdown 渲染
- [x] 消息：私信、回复我的、@我的、收到的赞、系统通知五格；私信收发文本，视频与专栏消息可打开
- [x] 分享与链接：接收站内链接与 b23.tv 短链，视频与直播间可分享
- [x] SponsorBlock 片段默认跳过，可换服务器
- [x] 界面：Material 3 Expressive，动效照规范重做；平板双栏布局；全面屏与挖孔适配
- [x] 应用内自更新；Material You 动态取色
- [x] 播放进度模型重做：正确处理分集、本地缓存、云端冲突

计划中：

- [ ] 界面：优化使用体验，完全适配 Material 3 Expressive 规范
- [ ] 订阅动态流重做：数据流重新设计
- [ ] 更好的 Adaptive Layout
- [ ] 小窗播放
- [ ] 播放器行为优化
- [ ] 直播间优化
- [ ] 投币、点赞和其他站内行为优化
- [ ] 过滤低质量评论和动态
- [ ] 性能优化
- [ ] 整理抽象与数据流
- [ ] 清理注释
- [ ] CI 实机验证
- [ ] Agent harness 优化

## 安装

需要 Android 10 或更高版本。

到 [Releases](https://github.com/NihilDigit/bilby/releases/latest) 下载最新版本，二进制由 GitHub Actions 从本仓库源码构建。

**Agent（可选）。** Agent 功能需要一个 OpenAI 兼容接口，在设置页的「助理」一节填入接口地址和 key 即可使用；不配置不影响其他功能。

## 贡献

欢迎贡献。修复 bug、报告崩溃、补充文档等小规模更正，直接提交 issue 或 PR 即可。

新增功能或破坏性改动，建议先提交 RFC issue，说明需求、应用目前的处理方式与预期设计。事先提交 issue，可以避免因双方目标不一致而浪费时间和精力。

以下几类不在项目范围内，相应的 PR 通常不会被合并：

- **任何形式的破解。** 不绕过大会员的画质与功能限制，不动计费与授权；观看与互动数据照常回传。
- **番剧、影视、课堂等非 UGC 内容。** 应用只播用户投稿的视频，这类链接不会被解析。
- **打扰用户或争夺其注意力的设计。** 推荐流、常驻在页面上的相关视频、「猜你喜欢」都在此列；应用只实现中立行为，内容由用户的操作决定。

使用 LLM 辅助编码时，务必理解新增代码的业务逻辑并进行实机验证。

## 许可

GPL-3.0-or-later，见 [LICENSE](LICENSE)。

与 bilibili 交互所需的实现（WBI 签名、AppSign、设备指纹、TV 扫码登录、playurl 参数、数据上报与写操作）移植自 [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus)（GPL-3.0），感谢该项目的开发者。
