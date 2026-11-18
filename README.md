<p align="center"><img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/icon.png" alt="Bilby" width="96"></p>

<h1 align="center">Bilby</h1>

<p align="center"><b>简体中文</b> | <a href="README.en.md">English</a></p>

<p align="center">
<a href="#安装"><img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-4A5C92?style=flat-square&logo=android&logoColor=white"></a>
<a href="#安装"><img alt="Windows 10+ x64 / ARM64" src="https://img.shields.io/badge/Windows-10%2B%20x64%20%2F%20ARM64-4A5C92?style=flat-square&logo=data:image/svg%2bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0yIDJoOS41djkuNUgyek0xMi41IDJIMjJ2OS41aC05LjV6TTIgMTIuNWg5LjVWMjJIMnpNMTIuNSAxMi41SDIyVjIyaC05LjV6Ii8+PC9zdmc+"></a>
<a href="https://github.com/NihilDigit/bilby/attestations"><img alt="SLSA Build L3" src="https://raw.githubusercontent.com/NihilDigit/bilby/badges/slsa-l3.svg"></a>
<a href="LICENSE"><img alt="GPL-3.0" src="https://img.shields.io/github/license/NihilDigit/bilby?style=flat-square&color=4A5C92&logo=gnu&logoColor=white"></a>
<br>
<img alt="Kotlin Multiplatform" src="https://img.shields.io/badge/Kotlin%20Multiplatform-4A5C92?style=flat-square&logo=kotlin&logoColor=white">
<img alt="Compose Multiplatform" src="https://img.shields.io/badge/Compose%20Multiplatform-4A5C92?style=flat-square&logo=jetpackcompose&logoColor=white">
<img alt="Material 3 Expressive" src="https://img.shields.io/badge/Material%203%20Expressive-4A5C92?style=flat-square&logo=materialdesign&logoColor=white">
</p>

Bilby 是一款轻量、高性能的 bilibili 客户端，支持 Android 与 Windows（x64 与 ARM64）。

遵循 Material Design 设计规范，采用响应式布局，兼顾桌面键鼠与移动端触控体验。不设推荐流、常驻相关推荐与未读红点，内容仅来自关注与主动检索。

## 订阅与媒体库

按时间展示已关注 UP 主的视频、动态与专栏，不提供推荐流。媒体库集中收纳历史记录、稍后再看、收藏夹与离线缓存，并支持夜间白名单模式。

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/feed-wide-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/feed-wide-light.avif">
    <img src="docs/screenshots/feed-wide-light.avif" width="760" alt="宽窗口首页：关注订阅网格与精简侧边导航栏">
  </picture>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/library-wide-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/library-wide-light.avif">
    <img src="docs/screenshots/library-wide-light.avif" width="536" alt="宽窗口媒体库：历史记录、稍后再看与收藏夹轮播">
  </picture>
  &nbsp;&nbsp;
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/feed-compact-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/feed-compact-light.avif">
    <img src="docs/screenshots/feed-compact-light.avif" width="176" alt="窄窗口首页：单列关注流与底部导航栏">
  </picture>
</p>

## Agent 检索

接入兼容 OpenAI 协议的端点后，助理可在全站检索并结合简介与热评筛选候选视频；宽屏下支持与常规搜索结果并排对照。

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/agent-wide-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/agent-wide-light.avif">
    <img src="docs/screenshots/agent-wide-light.avif" width="760" alt="宽窗口搜索：常规搜索结果与常驻助理侧边面板">
  </picture>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/agent-running-compact-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/agent-running-compact-light.avif">
    <img src="docs/screenshots/agent-running-compact-light.avif" width="260" alt="窄窗口：Agent 多步检索过程">
  </picture>
  &nbsp;&nbsp;
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/agent-answer-compact-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/agent-answer-compact-light.avif">
    <img src="docs/screenshots/agent-answer-compact-light.avif" width="260" alt="窄窗口：Agent 返回的整理结论与候选视频">
  </picture>
</p>

## 播放与找相关

合集、多 P 与稍后再看统一收归播放队列，支持弹幕与片段跳过。播放页不设常驻相关推荐，点按「找相关」可基于当前视频单次检索候选内容。

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/video-wide-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/video-wide-light.avif">
    <img src="docs/screenshots/video-wide-light.avif" width="760" alt="宽窗口播放页：双栏播放画面与队列">
  </picture>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/in-video-wide-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/in-video-wide-light.avif">
    <img src="docs/screenshots/in-video-wide-light.avif" width="536" alt="宽窗口找相关：右侧分栏检索结果">
  </picture>
  &nbsp;&nbsp;
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/in-video-compact-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/in-video-compact-light.avif">
    <img src="docs/screenshots/in-video-compact-light.avif" width="176" alt="窄窗口找相关：模态底部面板检索结果">
  </picture>
</p>

## 听视频

看视频与听视频无缝切换，无需重新加载，支持后台播放与系统媒体控制；字幕逐句跟随，点句即可跳转，并提供定时关闭。

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/listen-wide-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/listen-wide-light.avif">
    <img src="docs/screenshots/listen-wide-light.avif" width="760" alt="宽窗口听视频：双栏唱盘控制与播放队列">
  </picture>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/lyrics-compact-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/lyrics-compact-light.avif">
    <img src="docs/screenshots/lyrics-compact-light.avif" width="260" alt="窄窗口听视频：逐句同步字幕">
  </picture>
  &nbsp;&nbsp;
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/sleep-timer-compact-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/sleep-timer-compact-light.avif">
    <img src="docs/screenshots/sleep-timer-compact-light.avif" width="260" alt="窄窗口听视频：定时关闭">
  </picture>
</p>

## 计划中

- [ ] Windows：高级弹幕渲染性能优化
- [ ] 过滤低质量评论和动态
- [ ] 架构整理、数据流精简与注释清理
- [ ] CI 实机验证与 Agent 执行框架优化

## 安装

前往 [Releases](https://github.com/NihilDigit/bilby/releases/latest) 下载。安装包均由 GitHub Actions 从源码构建。

- **Android**：Android 10 及以上，按设备架构选择 APK，不确定时选 `universal`。
- **Windows**：64 位 Windows 10 及以上，分 `x64` 与 `ARM64` 构建（骁龙等 ARM 设备选 `arm64`）。
  - `.msi`：安装至当前用户目录，无需管理员权限，支持应用内更新。
  - `.zip`：便携版，解压后运行 `Bilby.exe`。
- **Agent 配置（可选）**：在「设置 → 助理」中填入兼容 OpenAI 协议的 API 地址与 Key；不配置不影响其他功能。

## 贡献

欢迎提交 Issue 与 PR。小的 Bug 修复、崩溃排查或文档补充可直接提交。

若计划新增功能或进行架构调整，请先提交 RFC Issue，说明需求场景、现有处理方式与拟定方案，避免因目标不一致而产生无意义的返工。

以下内容不在项目范围内：

- **破解与特权**：不绕过会员画质或功能限制，不动计费与授权，数据正常回传。
- **非 UGC 内容**：仅解析普通投稿视频，不处理番剧、影视及课程。
- **抢夺注意力的内容**：不做推荐流与常驻相关推荐，应用仅执行中立操作。

如果使用 LLM 辅助编写代码，请务必理解新增代码的逻辑并在实机上验证。

## 许可与致谢

- 遵循 [GPL-3.0-or-later](LICENSE) 开源。
- 客户端底层接口交互（WBI 签名、AppSign、设备指纹、TV 扫码登录、playurl 参数、数据上报与写操作）移植自 [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus)（GPL-3.0），感谢该项目的开发者。

## 友情链接

- [LINUX DO](https://linux.do/)
