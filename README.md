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

Bilby 是一款面向 Android 与 Windows（x64 与 ARM64）的 bilibili 客户端。

基于 Kotlin Multiplatform 与 Compose Multiplatform 构建，两端共用界面与业务代码，遵循 Material 3 Expressive 设计规范与五档窗口尺寸自适应布局：窄窗口采用底部导航栏与模态底部面板，中宽及以上窗口切换为精简侧边导航栏、桌面圆角内容岛，以及可调宽或双栏侧边面板。

- **Android**：界面基于 Jetpack Compose，后台播放服务基于 Media3，接入系统媒体控制、全面屏边到边显示与预测式返回。
- **Windows**：运行于 Azul Zulu 25，通过 D3D11→D3D12 共享纹理环实现视频画面零拷贝合成与显卡重置恢复；集成原生 Win32 标题栏与贴靠布局、SMTC 系统媒体控制、`F11` / `Alt+Enter` 全屏与小窗播放，以及纯 Java 实现的 zstd 差分与全量应用内更新。

界面注重减少干扰，内容来自关注与主动检索，不设推荐流、常驻相关视频与未读红点。

## 订阅与媒体库

首页按时间展示已关注 UP 主的视频投稿、图文动态与专栏，顶部列出正在直播的关注对象，不提供推荐流。「媒体库」（窄窗口下为「我的」）以横向轮播归集历史记录、稍后再看、收藏夹与离线缓存；夜间模式在设定时段内仅展示白名单 UP 主投稿与离线内容。

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

支持接入兼容 OpenAI 协议的端点。助理在全站执行多步检索，结合视频简介与热评筛选候选内容并附带出处；请求上下文仅包含当前提问，不注入观看历史。宽窗口下助理可作为常驻侧边面板与常规搜索结果并排对照。

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

播放页将合集、多 P 选集、稍后再看与手动编排草稿收归统一的有界队列，支持弹幕与 SponsorBlock 片段跳过。不设常驻相关视频栏；「找相关」由用户显式点按发起，基于当前视频单次检索候选，在宽窗口停靠为右侧分栏，在窄窗口呈现为模态底部面板。

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

听视频作为播放页内状态直接切换，无需重新加载，保持当前进度与播放队列不断开；在 Android 上接入系统媒体控制（支持息屏后台、锁屏与耳机线控），在 Windows 上接入 SMTC 系统媒体控制。字幕逐句同步跟随并支持点句跳转，提供定时关闭。

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

前往 [Releases](https://github.com/NihilDigit/bilby/releases/latest) 下载构建产物。所有二进制均由 GitHub Actions 从仓库源码构建。

- **Android**：需要 Android 10 或更高版本。请根据设备架构选择对应 APK，无法确认时选 `universal`。
- **Windows**：需要 64 位 Windows 10 或更高版本。x64 与 ARM64 各有安装包，骁龙等 ARM 处理器的设备选 `arm64`。
  - `.msi`：安装至当前用户目录，不需要管理员权限，支持后续应用内更新。
  - `.zip`：便携版，解压后运行 `Bilby.exe`。
- **Agent 配置（可选）**：Agent 检索依赖兼容 OpenAI 协议的端点。在应用「设置」→「助理」中填入 API 地址与 Key 即可使用；不配置不影响其他功能。

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
