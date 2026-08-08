<p align="center"><img src="docs/icon.png" alt="" width="96"></p>

# Bilby

### 没有推荐，只有选择

[![English](https://img.shields.io/badge/README-English-4A5C92?style=flat-square)](README.en.md) &ensp; [![APK](https://img.shields.io/endpoint?style=flat-square&url=https%3A%2F%2Fraw.githubusercontent.com%2FNihilDigit%2Fbilby%2Fmain%2F.github%2Fbadges%2Fapk-size.json)](https://github.com/NihilDigit/bilby/releases/latest) &ensp; [![SLSA Build Level 2](https://slsa.dev/images/gh-badge-level2.svg)](https://github.com/NihilDigit/bilby/attestations) &ensp; [![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/NihilDigit/bilby)

Bilby 是一款安卓原生的 bilibili 客户端，信息流由你掌控：内容来自关注的 UP 主，以及主动发起的检索。**应用内没有推荐算法**，不会插入未经你选择的内容。

> **本项目仍在开发中。** 界面与接口层均在持续改动，不保证稳定性与兼容性。

## 重新设计的交互体验

<table align="center">
<tr>
<td><img src="docs/screenshots/feed.png" width="240"></td>
<td><img src="docs/screenshots/video.png" width="240"></td>
<td><img src="docs/screenshots/listen.png" width="240"></td>
</tr>
<tr>
<td align="center">以关注动态组织首页信息</td>
<td align="center">播放队列来自所属合集与 UP 主投稿</td>
<td align="center">听视频：独立界面，与普通播放无缝切换</td>
</tr>
</table>

## 检索由用户发起

<table align="center">
<tr>
<td><img src="docs/screenshots/agent-running.png" width="240"></td>
<td><img src="docs/screenshots/agent-answer.png" width="240"></td>
<td><img src="docs/screenshots/in-video.png" width="240"></td>
</tr>
<tr>
<td align="center">检索过程可见</td>
<td align="center">返回候选并逐条说明理由</td>
<td align="center">可在播放页就当前视频发起</td>
</tr>
</table>

## 功能

已完成：

- [x] 关注动态、搜索、稍后再看、UP 主空间
- [x] 最常访问的 UP 主与完整关注列表
- [x] 播放：全屏、清晰度、倍速、长按快进、拖动进度、锁定、双击暂停、多 P；退到后台时听视频继续，普通播放暂停
- [x] 听视频：与普通播放共用同一播放器，息屏后台，通知栏、锁屏与耳机线控可控，定时关闭
- [x] 弹幕显示：滚动、顶部、底部三类，跟随播放时钟，透明度可调
- [x] AI 字幕：普通播放时在画面底部，听视频时作为逐句文稿
- [x] 评论：浏览、排序、展开楼中楼、发布、点赞、删除，时间戳可点击跳转
- [x] 点赞、投币、收藏、关注；联合投稿逐个署名并可分别关注
- [x] SponsorBlock 片段默认跳过
- [x] 助理搜索：检索、读简介与热评，返回若干视频并分别说明理由；播放页内可就当前视频发起

计划中：

- [ ] 弹幕发送
- [ ] AI 字幕修复
- [ ] 助理回复的富文本渲染
- [ ] CI 回归测试
- [ ] 界面与动效打磨
- [ ] 全面屏适配与状态栏沉浸
- [ ] 搜索优化
- [ ] Agent harness 优化
- [ ] 直播
- [ ] 小窗播放
- [ ] 专栏
- [ ] 解析 bilibili 链接与分享
- [ ] 过滤低质量评论

## 安装与登录

到 [Releases](https://github.com/NihilDigit/bilby/releases/latest) 下载 APK。`universal` 适用于所有设备；按架构分包体积更小，当前设备通常为 `arm64-v8a`。

构建来源可校验：`gh attestation verify <文件> --repo NihilDigit/bilby`。

也可以自行构建：

```
./gradlew installDebug
```

用官方客户端扫码登录，支持单账号，登录一次即可。

助理需要一个 OpenAI 兼容的接口，在设置页的「助理」一节填入地址和 key 即可使用。

需要 Android 10 或更高版本。

## 贡献

修复 bug、报告崩溃、补充文档与小规模更正，可直接提交 PR。

新增功能或破坏性改动，建议先提交 RFC issue，说明需求、应用目前的处理方式与预期设计。不设推荐、播放队列来自打开视频时即已确定的集合、助理不保留对话信息，这些属于项目的固定约束，改动它们的 PR 通常不会被合并；事先提交 issue，可在实现之前确认方向。

允许使用 LLM 辅助编码，但需满足两个条件：一是理解所提交代码的行为，能说明其可行性与影响范围；二是提交前在真机上验证实际表现。

首次修改前，建议阅读 `CLAUDE.md` 中记录的工作约定。

## 许可

GPL-3.0-or-later，见 [LICENSE](LICENSE)。与 bilibili 交互所需的实现（WBI 签名、AppSign、设备指纹、TV 扫码登录、playurl 参数、数据上报与写操作）移植自 [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus)（GPL-3.0）。
