[返回 Wiki 目录](README.md)

# 库内示例模型

仓库提供一套 RYSMJS 综合示例模型，包含可安装文件、模型源码和中文注释脚本。想先体验功能，可以直接安装；想制作模型，可以对照源码修改。

## 安装示例

选择以下一种方式即可：

| 方式 | 库内文件 | 安装方法 |
| --- | --- | --- |
| 导出模型 | [rysmjs-all-features.ysm](../examples/rysmjs/export/rysmjs-all-features.ysm) 和 [rysmjs-all-features.rysm](../examples/rysmjs/export/rysmjs-all-features.rysm) | 两个文件一起放入 `config/yes_steve_model/custom/` |
| 源码模型 | [all-features 文件夹](../examples/rysmjs/source/all-features/) | 将完整文件夹放入 `config/yes_steve_model/custom/` |

只希望自己看到时，改放到 `config/yes_steve_model/client/`。请使用已安装 Reimagined YSM 的游戏体验完整效果。

进入世界后，在模型选择界面选择示例模型。文件应完整下载或复制，不要把网页预览内容另存为模型包。

## 可以观察什么

| 操作 | 观察内容 |
| --- | --- |
| 移动、转向，再停下 | 披风和尾巴的摆动、回弹 |
| 转动视角 | 头顶装饰面向摄像机；上方星空与描边效果 |
| 查看两侧平面 | 按时间切换的帧动画，以及由数值选择的动画帧 |
| 查看最左侧部件 | 单面贴图生成厚度并发光 |
| 按 ESC | 切换开关和对应指示灯；游戏菜单仍会正常打开 |
| 按物品栏键、点击鼠标 | 查看输入指示灯变化 |
| 按 F10 | 在聊天栏查看示例读取的属性值 |

精妙背包指示灯需要对应模组的快捷键。属性示例中的模组属性 ID 需要替换为自己实际使用的值。多人指示灯需要支持同步的服务器。

## 参考脚本

| 文件 | 适合参考的功能 |
| --- | --- |
| [effects.js](../examples/rysmjs/source/all-features/rysm/scripts/effects.js) | 星空、描边、物理链和效果叠加 |
| [algorithms.js](../examples/rysmjs/source/all-features/rysm/scripts/algorithms.js) | 血量比例、动画帧与多人同步 |
| [advanced.js](../examples/rysmjs/source/all-features/rysm/scripts/advanced.js) | 玩家属性、阈值判断、组合条件和缓慢变化的数值 |
| [events.js](../examples/rysmjs/source/all-features/rysm/scripts/events.js) | 按键开关、鼠标输入和装饰移动旋转 |
| [manifest.json](../examples/rysmjs/source/all-features/rysm/manifest.json) | 将脚本加入模型并安排加载顺序 |

复制到自己的模型前，请修改组名、属性 ID 和图片路径。模型效果的基本写法见[模型效果](effects.md)，按键与变量见[脚本说明](scripting.md)。

## 在 Blockbench 中打开

1. 安装[编辑器插件](blockbench.md)。
2. 导入示例的 [models/main.json](../examples/rysmjs/source/all-features/models/main.json)，保存为同目录下的 `main.bbmodel`。
3. 打开 JavaScript 编辑器，查看独立脚本。
4. 如需练习表单设置，选择组并编辑属性。避免与原手写脚本重复设置同类效果。
5. 保存并在游戏中查看结果。

示例使用手写脚本，不会自动将所有效果转换为表单设置。更多部件说明见[示例自述文件](../examples/rysmjs/README.md)。示例几何、普通贴图与自定义星空贴图使用 CC0-1.0。
