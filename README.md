<p align="center"><img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/icon.png" alt="Bilby" width="96"></p>

# Bilby

[![English](https://img.shields.io/badge/README-English-4A5C92?style=flat-square)](README.en.md) [![APK](https://img.shields.io/endpoint?style=flat-square&url=https%3A%2F%2Fraw.githubusercontent.com%2FNihilDigit%2Fbilby%2Fmain%2F.github%2Fbadges%2Fapk-size.json)](https://github.com/NihilDigit/bilby/releases/latest) [![SLSA Build Level 2](https://slsa.dev/images/gh-badge-level2.svg)](https://github.com/NihilDigit/bilby/attestations) [![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/NihilDigit/bilby)

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

## 功能

已完成：

- [x] 信息流：关注动态（可屏蔽 UP 主）、最常访问的 UP 主、完整关注列表、稍后再看、历史记录与断点续播
- [x] UP 主空间：投稿、动态、合集/系列，支持空间内搜索
- [x] 播放：全屏、清晰度、倍速（WSOLA）、双击快进退与暂停、长按快进、拖动进度、滑动调亮度音量、锁定、多 P；播放进度云端同步
- [x] 听视频：与播放共用同一播放器，息屏后台，通知栏、锁屏与耳机线控可控，定时关闭；队列可随机播放；字幕轨即逐句歌词，点句跳转
- [x] 弹幕：滚动、顶部、底部三类，跟随播放时钟，透明度可调
- [x] 字幕：多轨可选，普通播放时在画面底部，听视频时作为逐句文稿；支持 AI 字幕修复
- [x] 评论：浏览、排序、展开楼中楼、发布与回复、点赞、删除，时间戳可点击跳转
- [x] 互动：点赞、投币、收藏、关注；联合投稿逐个署名并可分别关注
- [x] Agent：对话式全站搜索与播放页「找相关」，读简介与热评，候选逐条说明理由；支持多轮追问，执行轨迹可见
- [x] SponsorBlock 片段默认跳过，可换服务器
- [x] 应用内自更新；Material You 动态取色

计划中：

- [ ] 弹幕发送
- [ ] Agent 回复的富文本渲染
- [ ] CI 回归测试
- [ ] 界面和动效符合 Material Design 规范
- [ ] 全面屏适配与状态栏沉浸
- [ ] 响应式布局
- [ ] 重做普通搜索
- [ ] Agent harness 优化
- [ ] 直播
- [ ] 小窗播放
- [ ] 专栏
- [ ] 解析 bilibili 链接与分享
- [ ] 视频分享
- [ ] 视频下载
- [ ] 过滤低质量评论

## 安装

需要 Android 10 或更高版本。

到 [Releases](https://github.com/NihilDigit/bilby/releases/latest) 下载最新版本，二进制由 GitHub Actions 从本仓库源码构建。

**Agent（可选）。** Agent 功能需要一个 OpenAI 兼容接口，在设置页的「助理」一节填入接口地址和 key 即可使用；不配置不影响其他功能。

## 贡献

欢迎贡献。修复 bug、报告崩溃、补充文档等小规模更正，直接提交 issue 或 PR 即可。

新增功能或破坏性改动，建议先提交 RFC issue，说明需求、应用目前的处理方式与预期设计。不引入推荐算法和其他抢夺用户注意力的功能，是项目的固定约束，改动它的 PR 通常不会被合并；事先提交 issue，可以避免因双方目标不一致而浪费时间和精力。

使用 LLM 辅助编码时，务必理解新增代码的业务逻辑并进行实机验证。

## 许可

GPL-3.0-or-later，见 [LICENSE](LICENSE)。

与 bilibili 交互所需的实现（WBI 签名、AppSign、设备指纹、TV 扫码登录、playurl 参数、数据上报与写操作）移植自 [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus)（GPL-3.0），感谢该项目的开发者。
