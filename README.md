<img src="docs/icon.png" alt="" width="88" align="right">

# Bilby

[English](README.en.md)

没有推荐，只有选择。

Bilby 是一个 bilibili Android 客户端。首页按时间倒序展示关注 UP 主的更新，向下滑动
只会到达更早的内容，不会产生新内容。每天的新增更新取决于你所关注的 UP 主，数量是
有限的。应用不提供推荐流、相关视频栏与自动连播。

> **本项目仍在开发中。** 界面与接口层均在持续改动，不保证稳定性与兼容性。

## 使用

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

打开视频后，官方客户端用于展示推荐内容的位置，Bilby 改为展示当前视频所属的合集与该
UP 主的其他投稿。这是一个在打开视频时即已确定的有限集合，播放队列由该集合构成，播
放完毕即停止，过程中不会加入新内容。

查找其他内容需要主动发起。搜索功能可正常使用；也可以交给助理，它会检索、阅读简介与
评论，再给出若干视频并分别说明理由。助理的上下文只有本次会话的内容，不包含你的观看
历史。

账号行为与官方客户端一致：历史记录、投币、收藏、点赞均正常提交至 bilibili。Bilby
移除的仅是平台向用户推送内容的部分。

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

- [ ] 界面与动效打磨
- [ ] Agent harness 优化（工具集与循环）
- [ ] 直播
- [ ] 小窗播放
- [ ] 关注列表
- [ ] 解析 bilibili 链接与分享
- [ ] 过滤低质量评论
- [ ] 弹幕

## 安装与登录

```
./gradlew installDebug
```

通过官方客户端扫码登录，单账号，登录一次即可。

助理需要一个 OpenAI 兼容接口，在设置页的「助理」一节填入地址与 key。

要求 Android 10 及以上。

## 贡献

修复 bug、报告崩溃、补充文档与小规模更正，可直接提交 PR。

新增功能与破坏性改动建议先提交 RFC issue，说明需求、应用当前的处理方式与预期设计。
上文所述行为（无推荐、有限队列、助理不保留对话信息）属于项目的固定约束，改动这些
约束的 PR 通常不会被合并，事先提交 issue 可在实现之前确认方向。

允许使用 LLM 辅助编码，但需满足两个条件：一是理解所提交代码的行为，能说明它为何
可行、会影响哪些部分；二是提交前在真机上验证其实际表现。

首次修改前建议阅读 `CLAUDE.md` 中记录的工作约定。

## 许可

GPL-3.0-or-later，见 [LICENSE](LICENSE)。与 bilibili 交互所需的实现（WBI 签名、
AppSign、设备指纹、TV 扫码登录、playurl 参数、数据上报与写操作）移植自
[PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus)（GPL-3.0）。
