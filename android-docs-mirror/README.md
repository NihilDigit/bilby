# developer.android.com mirror

这是给 Agent 使用的 Android 官方文档镜像，与 `m3-material-mirror/` 同类。主要语料位于 `pages/`，已经从 devsite 的服务端渲染 HTML 清洗成 Markdown；正文中的图片改写为 `assets/` 下的相对路径，站内链接改写为镜像内的相对 `.md` 路径。

## 先读这条

**站点文档滞后于实际构件。** 符号是否存在、签名长什么样、参数是否可空，一律以本机 Gradle 缓存里那个钉住版本的 aar/jar 为准，用 `javap` 现查；文档只说明意图和用法。参考页写的是发布时的状态，本项目依赖的版本可能更早也可能更新，两者不一致时构件是事实。

```powershell
# 找到钉住版本的 jar，再看某个类的实际签名
Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\androidx.media3" -Recurse -Filter *.jar
javap -cp <上面找到的 jar> androidx.media3.session.MediaSession
```

## 怎么用

1. 在 `pages/` 里 grep 关键词，或者先读 `pages/INDEX.md` 的主题索引。
2. 文件路径与站点 URL 一一对应：`/media/media3/session/background-playback` 对应 `pages/media/media3/session/background-playback.md`，grep 命中之后可以直接反推来源。
3. 每个文件首行是一条 HTML 注释，记着来源 URL、抓取时间，以及上游的 `Last-Modified`（站点给了才有）。要回原文照这行走。
4. 需要确认符号本身时按上一节的办法查构件。

## 刷新

从仓库根目录执行：

```powershell
python android-docs-mirror\refresh.py --workers 10          # 常规刷新
python android-docs-mirror\refresh.py --refresh --workers 10 # 重新走一遍站点并逐页验证
python android-docs-mirror\refresh.py --check               # 只报告上游变了哪些页，不写文件
python android-docs-mirror\refresh.py --verify --sample 0   # 保真度检查
```

各模式的行为：

- **默认**：从 `metadata/crawl-state.json` 接着上次的进度走。上次已经抓完的页不再请求，只重建 Markdown，通常几十秒完成。抓到一半被打断（Ctrl-C、断网）后再跑一次，会从没抓完的地方接着走，不重抓已完成的页。
- **`--refresh`**：从种子页重新遍历整站，每页带上记下的 `ETag` 与 `Last-Modified` 发条件请求；上游没变返回 304，直接复用缓存，不传正文。这是发现「上游新增了页面」的方式，也是唯一会重新发现新页的模式。
- **`--check`**：只发条件请求，报告哪些页在上游变过，写 `metadata/check.json`，不改 `pages/`。
- **`--verify`**：见下一节。
- **`--render-only`**：只从缓存重建 Markdown，一个请求都不发。

其它参数：`--no-assets` 跳过图片下载，`--only <组名>` 限制在单个分组（可重复），`--max-pages N` 抓够指定页数就停，`--drop-html` 在结束时丢弃原始 HTML 缓存。并发上限为 10。

退出码：`0` 全部成功；`2` 只有上游 404/410 这类永久失效的失败，也就是站点自己目录里的死链；`1` 有其它失败，可能是本地或网络的问题。挂定时刷新时按这三档判断。

原始 HTML 默认保留在 `metadata/raw-pages/`（gzip，约 180 MB），因为它是 `--render-only` 和 `--verify` 的输入，也是重跑不必再下载的原因。`pages/` 约 22 MB，`assets/` 约 190 MB。

## 保真度检查

`--verify` 从源 HTML 和生成的 Markdown 两侧分别数四类结构 —— 代码块、标题、表格、图片 —— 逐页对照，Markdown 少于源就算不通过。**这是这份镜像可信的唯一凭据**：清洗规则如果悄悄漏掉一半代码块，只有这里会发现。报告写在 `metadata/verify.json`。

`--sample N` 控制抽样页数，默认 30，`--sample 0` 检查全部 1733 页（约半分钟）。`--seed` 固定抽样，便于复现同一份报告。

另有一条硬性判据：**任何一页不得出现超过 4000 字符的单行**。这份语料的用法是 grep，命中之后返回的是一整行；超过 4000 字符（约四十行终端宽度）之后，命中就不再是答案而是一堵墙。参考页的「继承来的成员」曾经是一个单元格套一整张表，最长的一行接近 45000 字符，就是这条判据要挡住的东西。

## 抓取范围

按分组划分，与 `manifest.json` 的 `scope` 字段一一对应：

| 分组 | 路径前缀 |
| --- | --- |
| `media3-guide` | `/media/media3/` |
| `media-implement` | `/media/implement/` |
| `media3-reference` | `/reference/androidx/media3/` 下的 `common`、`exoplayer`、`session`、`ui`、`datasource` 五个包 |
| `compose` | `/develop/ui/compose/` |
| `navigation-3` | `/guide/navigation/navigation-3/` |
| `background-work` | `/develop/background-work/`、`/develop/ui/views/notifications/` |

站点没有可用的 `_book.yaml`，`sitemap.xml` 也只收录了很小一部分指南页（`/media/media3/` 仅 14 条），因此页面清单来自广度优先抓取：种子页取各节入口和五个包的 `package-summary`，链接同时从左侧目录树和正文里提取，只保留落在上述前缀内的。参考页的模板与指南页不同，转换时保留了方法签名、`Parameters` 与 `Throws` 表格。

**没有分页截断、没有深度上限、没有跳过任何一节。** 上面六组各自的前缀内，凡是能从种子页顺着链接走到的页都抓了；抓不到的逐条记在 `metadata/failures.json`，数量与原因也写进 `manifest.json`。范围内唯一的主动取舍是带 `?hl=` 的本地化副本不抓。

`/media/media3/` 取的是整棵树，包括 `session`、`exoplayer`、`ui`、`transformer` 四个子目录。

### 覆盖面无法自证完整

目录是顺着链接广度优先走出来的，而站点的 `sitemap.xml` 只收录了范围内很小一部分页面（`/media/media3/` 仅 14 条），没有一份可以对账的权威清单。**因此范围内如果存在一个没有任何入口链接指向的孤儿页，它不会被发现，也不会出现在镜像里，而我们无从知道有没有这种页。**

各组的页数写在 `manifest.json` 的 `scope` 里，旁边就是这条说明和该组的种子页，`truncated` 字段标记这一轮是否跑完。读的时候请把「grep 不到」理解为两种可能：官方没写，或者镜像没抓到。两者的后续动作不同 —— 前者该去查构件或源码，后者该去站点确认一次，必要时把那页的入口加进 `refresh.py` 的 `seeds`，重跑一次即可纳入。

## robots.txt

抓取范围遵守 `https://developer.android.com/robots.txt`，脚本每次运行都会重新读取它，逐条 URL 判断后才发请求，并把副本存到 `metadata/robots.txt`。

`User-agent: *` 下被 disallow 的路径是 `/assets/css/`、`/assets/images/`、`/assets/js/`、`/guide/samples/`、`/images/`、`/partners/`、`/sdk/OLD_RELEASENOTES`、`/sdk/RELEASENOTES`、`/sdk/older_releases`、`/shareables/`，其中 `/images/social/` 和 `/images/cards/distribute/stories/` 被 allow 放行。这些路径与上述六个分组不重叠，因此没有页面因此落空。文档配图位于 `/static/`，不在限制之列。

被 disallow 的资源不会被请求，而是以 `robots-disallowed` 记入 `metadata/assets.json`。

## 清洗时丢弃了什么

正文之外的站点组件不进 Markdown。逐类列出：

- 左侧目录树与站点导航（`<nav class="devsite-book-nav">`）。它只用于发现页面，不进正文。
- 页面标题里的收藏组件（`<devsite-actions>`、`<devsite-bookmark>`、`devsite-feature-tooltip`），否则每页标题后面都会跟一句「Stay organized with collections」。
- 页脚、评分与反馈组件（`devsite-feedback`、`devsite-page-rating`、`devsite-thumb-rating`、`devsite-content-footer`）。
- 页面底部的「Recommended for you」推荐位（`devsite-recommendations`）。
- 带 `data-nosnippet` 标记的元素，以及 `class` 含 `nocontent` 的元素，devsite 用它们标注非正文区域，Kotlin/Java 视图切换器就在其中。
- `<script>`、`<style>`、`<form>`、`<button>`、`<iframe>`、内联 `<svg>`。

保留但改了形态的两处：代码示例里 devsite 挂的 GitHub 链接从代码块内部移出来，变成代码块下面的一行 `*Source: ...*`；Kotlin/Java/Groovy 标签页的标签从标题降为加粗行，避免污染标题层级。

## 上游文件

`upstream/` 存放 androidx/media 仓库 `release` 分支上的两个文件：

- `RELEASENOTES.md`
- `media-api.txt`，来自仓库根目录的 `api.txt`。`libraries/session/api/current.txt` 并不存在，整个仓库只有根目录这一份签名文件，覆盖全部 `androidx.media3` 包，包括 session。

## 失败账本

失败的页面和图片不会被静默跳过。`metadata/failures.json` 逐条记 URL、分组、失败原因、HTTP 状态码、重试次数和最后一次尝试的时间，原因分 `robots-disallowed`、`fetch`、`not-found`、`no-article-body`、`render` 五类。图片失败记在 `metadata/assets.json` 的 `status` 字段里；下载不到的图片保留原始 URL，不用占位图顶替。各类计数汇总在 `manifest.json`，收尾报告的数字从这里出。

## Git

生成内容通过仓库本地的 `.git/info/exclude` 排除；`README.md` 和 `refresh.py` 可以提交，方便共享刷新方式。
