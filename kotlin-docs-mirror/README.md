# kotlinlang.org mirror

给 Agent 使用的 Kotlin 官方文档镜像。语料位于 `pages/`，已从站点 HTML 清洗成 Markdown，正文图片改写为
`assets/` 下的相对路径。对应 Kotlin 2.4.10。

## 使用方式

先在 `pages/` 下 grep 关键词，命中后读该文件头部的来源 URL 回到官方页面核对：

```powershell
rg "SharingStarted" kotlin-docs-mirror\pages
```

每页第一行记录来源 URL、抓取时间和上游 `Last-Modified`。`pages/INDEX.md` 按语言参考、协程、标准库主题、
API 参考四组列出主题到文件的映射，是进入这份语料的入口。

**文档描述的是语言与库的意图。某个 API 在本项目钉住的版本里是否存在、签名是什么，以本机 Gradle 缓存里的
构件为准。** 镜像对应上游当前的发布版本，与 `gradle/libs.versions.toml` 钉住的版本未必一致，Flow 与
Channel 一带的签名尤其常动。

## 刷新

从仓库根目录执行：

```powershell
python kotlin-docs-mirror\refresh.py --workers 10
```

| 参数 | 作用 |
| --- | --- |
| `--workers N` | 并发请求数，上限 10 |
| `--max-age H` | 镜像在 H 小时内视为新鲜，跳过请求，默认 24；`0` 表示每页都发条件请求 |
| `--refresh` | 忽略缓存重新下载全部内容 |
| `--no-assets` | 不下载正文图片 |
| `--no-api` | 跳过 Dokka API 参考，只刷新 `/docs/` 下的指南 |
| `--full-api` | API 参考连成员页一并抓取，见下文范围一节 |
| `--check` | 只发条件请求，报告上游哪些页变过，不写文件 |
| `--verify` | 抽样对照源 HTML，输出保真度报告 |

全部成功退出码为 0，有任何页面或图片失败为非 0。失败写入 `metadata/failures.json`，含 URL、状态码、累计重试
次数和最后一次时间；同一 URL 后续成功时该条目被移除。

刷新策略分三层。**新鲜窗口**：`--max-age` 之内的页面不发请求，一次完整刷新之后的重跑因此在一秒内结束。
**条件请求**：窗口过期后带 `If-None-Match` 与 `If-Modified-Since` 请求，304 保留本地 Markdown 不重新渲染。
**断点续跑**：每页落盘时追加一行到 `metadata/progress.jsonl`，中途被打断后重跑会把它折回 `cache.json`，已完成
的部分不再下载。

范围收窄或上游删页之后，`pages/` 下不再被认领的 Markdown 会被删除。留着它们与仍然有效的页面无从分辨。

## 目录

```text
pages/       Agent 主要读取的 Markdown，INDEX.md 是入口
assets/      正文引用的图片，按原 URL 路径存放
metadata/    HelpTOC、config、sitemap 快照，抓取缓存、进度与失败账本
manifest.json
refresh.py
```

## 范围

`https://kotlinlang.org/robots.txt` 没有任何 `Disallow` 规则，只声明 sitemap，抓取范围不受它限制。范围由下面
四组划定，其余一律不抓。

**语言参考（47 页）**、**协程（16 页）**、**标准库主题（23 页）** 三组取自 `/docs/` 下的
`HelpTOC.json`，分别对应导航里的 Language guide（去掉 Concurrency）、Language guide > Concurrency 加
Library guides > Coroutines、Library guides > Standard library。按导航节点而非 URL 前缀取，页面改名不会
悄悄掉出范围，章节改名则直接报错。

**API 参考（648 页）** 取自 Dokka 站点 `/api/core/kotlin-stdlib/` 与 `/api/kotlinx.coroutines/`，两处都是静态
HTML，可以直接抓。默认只抓包索引页与类型索引页：类型页已经列出每个成员的完整签名与一句说明，而单独的成员页
另有 3431 个。需要成员页的长篇 KDoc 时加 `--full-api`。

以下不在范围内。**缺失不代表官方没写**，需要时到 kotlinlang.org 上查：

- Kotlin Multiplatform、Native、JS、Wasm 的全部文档
- Compose Multiplatform 与 Ktor
- kotlinx.serialization、Lincheck、kotlin-metadata-jvm 三套库指南
- Gradle 与 Maven 构建、编译器与编译器插件、KSP
- 教程、Koans、书籍、社区、基金会等学习与社区页
- 版本发布公告与兼容性归档
- `api/core` 下的 kotlin-test 与 kotlin-reflect
- kotlin-stdlib 里 `kotlin.js`、`kotlin.native`、`kotlin.wasm`、`kotlinx.cinterop`、`org.w3c` 各包。这些包与
  common 和 JVM 的 API 混在同一个 Dokka 站点里，共 279 页，是 grep 结果中最大的一类干扰

## 清洗规则

`/docs/` 的正文取 `<article class="article">`，API 参考取 `<div class="main-content">`，两侧共同丢弃脚本、样式、
`<nav>`、`<header>`、`<footer>`、按钮与表单。此外：

- `/docs/` 丢弃页尾的最后修改日期、上一页与下一页导航、反馈组件、评论容器和视频播放器。
- API 参考丢弃面包屑、复制按钮、锚点图标、源码链接、源集切换标签和站点导航。
- **API 参考按源集去重。** Dokka 把同一个声明按 common、jvm、js、native、wasm 各渲染一遍，镜像只保留一份。
  去重按渲染后的文本比对，同一段内容再次出现才丢弃，因此不会丢掉任何独有内容。**不要改回“保留第一份”那种
  按位置去重**：Dokka 用同一层包装裹住整张成员表，按位置去重会连带吃掉只有某个源集声明的成员，
  `kotlin.collections/-list/` 曾因此少 10 个成员，且从产物上看不出来。

保留的部分：代码块原样保留并带上语言标记，表格转成 Markdown 表格，`aside` 提示转成引用块，列表按层级缩进，
代码块嵌在列表项里时随该项缩进。

## 保真度

```powershell
python kotlin-docs-mirror\refresh.py --verify --sample 16
```

按四组分层抽样，重新取源 HTML 逐页对照，逐页打印 `产物/源 HTML`，末尾给出分组汇总、缺口最大的页面和通过与否，
完整数据写入 `metadata/verify.json`。

两类页面的口径不同，因为它们不可比。**指南页**比代码块、标题、表格三项计数，要求逐项相等。**API 页**比声明与
小节是否齐全：取源页每个成员行的链接目标，逐个确认出现在 Markdown 里，再确认每个小节标题都在。API 页不数签名
块，Dokka 一个声明按源集渲染多遍而镜像只留一份，数签名块会在每一页上报出恒定的缺口，这样的报告比没有报告更
糟。

**判据是零缺口**：任何一页在自己那套口径下少一项即为未通过，退出码非 0。改动清洗规则之后重跑一次，这是这份
镜像可信的凭据。

## 与 Git 的关系

`pages/`、`assets/`、`metadata/`、`manifest.json` 由仓库本地的 `.git/info/exclude` 排除。`README.md` 与
`refresh.py` 可以提交，方便共享刷新方式。脚本只用 Python 标准库，没有额外依赖。
