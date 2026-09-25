<p align="center"><img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/icon.png" alt="Bilby" width="96"></p>

<h1 align="center">Bilby</h1>

<p align="center"><b>简体中文</b> | <a href="README.en.md">English</a></p>

<p align="center">
<a href="#安装"><img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-4A5C92?style=flat-square&logo=android&logoColor=white"></a>
<a href="#安装"><img alt="Windows 10+ x64" src="https://img.shields.io/badge/Windows-10%2B%20x64-4A5C92?style=flat-square&logo=data:image/svg%2bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0yIDJoOS41djkuNUgyek0xMi41IDJIMjJ2OS41aC05LjV6TTIgMTIuNWg5LjVWMjJIMnpNMTIuNSAxMi41SDIyVjIyaC05LjV6Ii8+PC9zdmc+"></a>
<a href="https://github.com/NihilDigit/bilby/attestations"><img alt="SLSA Build L3" src="https://raw.githubusercontent.com/NihilDigit/bilby/badges/slsa-l3.svg"></a>
<a href="LICENSE"><img alt="GPL-3.0" src="https://img.shields.io/github/license/NihilDigit/bilby?style=flat-square&color=4A5C92&logo=gnu&logoColor=white"></a>
<br>
<img alt="Kotlin Multiplatform" src="https://img.shields.io/badge/Kotlin%20Multiplatform-4A5C92?style=flat-square&logo=kotlin&logoColor=white">
<img alt="Compose Multiplatform" src="https://img.shields.io/badge/Compose%20Multiplatform-4A5C92?style=flat-square&logo=jetpackcompose&logoColor=white">
<img alt="Material 3 Expressive" src="https://img.shields.io/badge/Material%203%20Expressive-4A5C92?style=flat-square&logo=materialdesign&logoColor=white">
</p>

Bilby 是一款面向 Android 与 Windows 的 bilibili 客户端。

基于 Kotlin Multiplatform 构建，两端共用界面与业务代码，界面遵循 Material 3 Expressive 设计规范：
- **Android**：原生高性能实现，界面基于 Jetpack Compose，播放基于 Media3。
- **Windows**：基于 Compose Multiplatform 的 GPU 加速界面，视频画面直通显存，零拷贝合成。

界面注重减少干扰，内容来自关注与主动检索。

> 当前提供实验性的 Windows 支持。

## 动态与播放

首页展示已关注 UP 主的动态，不提供推荐流。播放页提供「找相关」操作，支持基于当前视频单次检索候选内容。

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/feed.jpg" width="240" height="528" alt="首页：关注动态">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/video.jpg" width="240" height="528" alt="播放页与播放队列">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/in-video.jpg" width="240" height="528" alt="点按后 Agent 基于当前视频检索">
</p>

## 听视频

在 Android 上与系统媒体控制自然衔接，支持息屏后台、锁屏与耳机线控。听视频随切随播，无需重新加载；字幕逐句跟随，点句即可跳转。

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/listen.jpg" width="240" height="528" alt="听视频界面">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/lyrics.jpg" width="240" height="528" alt="逐句字幕">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/sleep-timer.jpg" width="240" height="528" alt="定时关闭">
</p>

## Agent 检索

支持接入 OpenAI 兼容端点。Agent 检索全站，结合简介与热评筛选候选。

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/agent-running.jpg" width="240" height="528" alt="Agent 检索过程">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/agent-answer.jpg" width="240" height="528" alt="Agent 返回的候选视频">
</p>

## 计划中

- [ ] 打磨界面细节，优化响应式布局
- [ ] Windows 原生行为优化
- [ ] Windows：高级弹幕渲染性能优化
- [ ] 过滤低质量评论和动态
- [ ] 架构整理、数据流精简与注释清理
- [ ] CI 实机验证与 Agent 执行框架优化

## 安装

前往 [Releases](https://github.com/NihilDigit/bilby/releases/latest) 下载构建产物。所有二进制均由 GitHub Actions 从仓库源码构建。

- **Android**：需要 Android 10 或更高版本。请根据设备架构选择对应 APK，无法确认时选 `universal`。
- **Windows**：需要 64 位 Windows 10 或更高版本。
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
