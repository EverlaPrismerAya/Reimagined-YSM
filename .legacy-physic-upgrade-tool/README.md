# YSM 旧组名升级工具

用于将旧版的组名驱动的RYSM模型更新到新版的JS驱动版本。

安装包含 Tkinter 的 Python 3.12 或更高版本（Windows 官方 Python 安装程序默认包含），双击 `start.bat`，或运行：

```console
python upgrade.py
```

选择**单个模型根文件夹**（含 `ysm.json`，或同时含 `main.json` 和 `arm.json`），指定不存在的新输出文件夹，点击“生成升级副本”。原件保留，失败不会发布半成品。输出可作为普通文件夹模型放入游戏 `config/yes_steve_model/custom/`。

转换 `ysmPhys`、`ysmTail`、`ysmPhysEnd`、`ysmPhysC`、`ysmCosmic`、`ysmOutline`、`FrameAnimation`、`FacingCamera`、`ysmItem` 为 `rysm/scripts/prefix-migrated.js` 的 `Rysm.model("group_modifier", ...)` 注册。保留官方 `ysmGlow`。同步替换 JSON 中的完整组名、动画键、父组引用及带引号的 Molang 引用。组名冲突或存在不明确引用时保留原名，仍生成 JS 属性，并写入 `rysm/migration-report.txt`。主模型、手臂、载具和投射物分别使用对应作用域。

仅支持文件夹；不解包 ZIP，不读取二进制 `.ysm` / `.rysm`，不处理模型集合目录、符号链接、目录联接或已有 `rysm/manifest.json` 的模型。旧 JS API 需手动改为 `Rysm.model`。输入 JSON 必须为合法 UTF-8 JSON（支持 BOM）。工具不调用游戏解析器，生成后仍需在游戏内检查动画、物理和材质效果。
