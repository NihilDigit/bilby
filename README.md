<img src="docs/icon.png" alt="" width="88" align="right">

# Bilby

### 没有推荐，只有选择

[English](README.en.md)

Bilby 是一个 bilibili 第三方 Android 客户端。首页只有关注 UP 主的更新，按时间倒序，
向下滑动只会到达更早的内容。没有推荐流、相关视频栏与自动连播。

> **本项目仍在开发中。** 界面与接口层均在持续改动，不保证稳定性与兼容性。

<table>
<tr>
<td><img src="docs/screenshots/feed.png" width="165"></td>
<td><img src="docs/screenshots/video.png" width="165"></td>
<td><img src="docs/screenshots/agent-running.png" width="165"></td>
<td><img src="docs/screenshots/agent-answer.png" width="165"></td>
<td><img src="docs/screenshots/listen.png" width="165"></td>
</tr>
<tr>
<td align="center">关注动态</td>
<td align="center">播放页与队列</td>
<td align="center">助理检索中</td>
<td align="center">助理的回答</td>
<td align="center">听视频</td>
</tr>
</table>

播放页上，官方客户端放置推荐视频的位置，改为展示该视频所属的合集与 UP 主的其他投稿。
该集合在打开视频时即已确定，播放完毕即停止。

查找其他内容需主动发起：可直接搜索，也可向助理描述需求。助理会检索视频、阅读简介与
热评，返回若干候选并分别说明推荐理由。

## 功能

已完成：

- [x] 关注动态、搜索、稍后再看、UP 主空间
- [x] 播放：全屏、清晰度、倍速、长按快进、拖动进度、锁定、双击暂停、多 P
- [x] 听视频：与看视频共用同一播放器，息屏后台，通知栏、锁屏与耳机线控可控，定时关闭
- [x] 评论：浏览、排序、展开楼中楼、发布、点赞、删除
- [x] 点赞、投币、收藏、关注
- [x] SponsorBlock 片段默认跳过
- [x] 助理搜索：检索、读简介与热评，返回若干视频并分别说明理由

计划中：

- [ ] 弹幕
- [ ] CI 回归测试
- [ ] 界面与动效打磨
- [ ] Agent harness 优化
- [ ] 直播
- [ ] 小窗播放
- [ ] 关注列表
- [ ] 解析 bilibili 链接与分享
- [ ] 过滤低质量评论

## 安装与登录

```
./gradlew installDebug
```

用官方客户端扫码登录，支持单账号，登录一次即可。

助理需要一个 OpenAI 兼容的接口，在设置页的「助理」一节填入地址和 key 即可使用。

需要 Android 10 或更高版本。

## 贡献

修复 bug、报告崩溃、补充文档与小规模更正，可直接提交 PR。

新增功能或破坏性改动，建议先提交 RFC issue，说明需求、应用目前的处理方式与预期设计。
上文所述行为（无推荐、有限队列、助理不保留对话信息）属于项目的固定约束，改动这些
约束的 PR 通常不会被合并；事先提交 issue，可在实现之前确认方向。

允许使用 LLM 辅助编码，但需满足两个条件：一是理解所提交代码的行为，能说明其可行性
与影响范围；二是提交前在真机上验证实际表现。

首次修改前，建议阅读 `CLAUDE.md` 中记录的工作约定。

## 许可

GPL-3.0-or-later，见 [LICENSE](LICENSE)。与 bilibili 交互所需的实现（WBI 签名、
AppSign、设备指纹、TV 扫码登录、playurl 参数、数据上报与写操作）移植自
[PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus)（GPL-3.0）。
