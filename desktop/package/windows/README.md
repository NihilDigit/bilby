# Windows 打包资源

- `icon.ico`：`desktop/build.gradle.kts` 的 `nativeDistributions.windows.iconFile`。`Bilby.exe`、
  开始菜单快捷方式与「添加或删除程序」条目都用它。
- `transactional-upgrade.ps1`：`packageReleaseMsi` 打完 MSI 后自动执行，把卸载旧版挪进安装事务，
  理由见脚本开头。

## 图标

`icon.ico` 与窗口图标 `desktop/src/main/resources/app-icon.png` 都由仓库的 `docs/icon.svg` 渲染。
该 SVG 与 Android 启动器前景层 `app/src/main/res/drawable/ic_launcher_foreground.xml` 同一套路径，
自带圆角底色。改了 SVG 后在仓库根目录重跑（需 ImageMagick）：

```powershell
magick -background none -density 384 docs/icon.svg `
  -define icon:auto-resize=256,128,64,48,32,16 `
  desktop/package/windows/icon.ico
magick -background none -density 384 docs/icon.svg -resize 256x256 `
  desktop/src/main/resources/app-icon.png
```

`-background none` 必须放在输入文件之前，否则圆角外的透明区会铺成白色。`-density 384` 让 SVG
先按 384px 栅格化再缩小；缺省的 96 dpi 只有 108px，放大到 256 会发虚。
