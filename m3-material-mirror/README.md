# m3.material.io mirror

这是给 Agent 使用的 Material 3 资料镜像。主要语料位于 `pages/`，已经从站点的页面 JSON 清洗成 Markdown；正文中的图片优先改写为 `assets/` 下的相对路径。

## 使用方式

从仓库根目录执行：

```powershell
python m3-material-mirror\refresh.py --workers 10
```

首次运行会下载页面、组件状态数据以及图片和字体。后续只重建 Markdown、不重新下载已有资源时：

```powershell
python m3-material-mirror\refresh.py --workers 10 --no-assets
```

需要重新抓取全部资源时，加上 `--refresh`。低层级的组件 Token 表默认不进入 Agent 语料；确实需要时使用 `--include-token-tables`。

## 目录

```text
pages/       Agent 主要读取的 Markdown 页面
assets/      Markdown 引用的本地图片和字体
metadata/    抓取版本、路由、资源清单和站点快照
shell/       Angular 应用壳的静态脚本和样式
refresh.py   刷新脚本，默认把输出写回当前目录
manifest.json
```

原始页面 JSON 默认不保留。需要调试数据映射时，可以加 `--keep-json`，原始数据会写入 `metadata/raw-pages/` 和 `metadata/raw-resources/`。

## 当前范围

镜像包含首页以及 `foundations`、`styles`、`components`、`develop` 下的主要页面，不包含搜索结果、归档页、旧博客和分析脚本。抓取范围遵守站点的 `robots.txt`。

资源可能因源站删除而返回 404；这类图片不会被伪造替换，Markdown 会保留原始 URL，错误记录在 `metadata/assets.json`。

生成内容通过仓库本地的 `.git/info/exclude` 排除；`README.md` 和 `refresh.py` 可以提交到 Git，方便共享刷新方式。重新生成时脚本会复用已有文件，并以当前站点版本更新 `manifest.json`。
