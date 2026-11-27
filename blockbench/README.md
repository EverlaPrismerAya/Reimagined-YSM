# Reimagined YSM 分组编辑器

此插件用于 Blockbench。它可以编辑组效果、生成 JS，并预览按键动画、骨骼姿态和星空。当前版本为 3.2.0。

## 安装

1. 在 Blockbench 中打开插件界面。
2. 选择「从文件加载插件」。
3. 选择 [reimagined_ysm_physics_groups.js](reimagined_ysm_physics_groups.js)。

更新插件后，重载插件或重启 Blockbench。

## 准备模型

模型目录需要包含 `ysm.json`。将 Blockbench 项目保存为该目录或其子目录中的 `.bbmodel` 文件。例如：

```text
my_model/
├─ ysm.json
├─ models/
│  ├─ main.json
│  └─ main.bbmodel
└─ rysm/
   ├─ manifest.json
   └─ scripts/blockbench.js
```

使用普通组名，例如 `cape`、`ornament` 和 `panel`。组名与脚本中的名称保持一致。

## 编辑组效果

1. 选择一个可导出的组。
2. 打开「编辑脚本组属性」。
3. 设置物理、发光、面向摄像机、单面厚度、星空、描边或帧动画。
4. 执行「写入模型 rysm 文件夹」。

同一组可组合多个效果。表单中的有效修改立即生效，并可撤销。插件将组属性保存在 `.bbmodel` 中，将游戏使用的配置写入 `rysm/scripts/blockbench.js`。

生成的脚本使用事件与链式方法：

```js
Rysm.model('group_modifier', event => {
  event.modify('panel')
    .cosmic({sc: '3388FF', sa: 25})
    .outline({width: 2, color: '33AAFF'});
});
```

星空颜色填写六位 RGB，例如 `3388FF`。透色比例填写 `0–100`。描边颜色使用六位 RGB 或 `auto`，宽度为 `0–64` 渲染像素。

物理链需要物理骨骼和末端。身体碰撞体使用单独的组。单面厚度和帧动画使用一个方块及一个有效 UV 面。参数说明见 [模型配置教程](../README.md#组效果速查)。

## 导入已有配置

### 插件生成或迁移生成的脚本

1. 打开模型的 Blockbench 项目。
2. 执行「导入模型脚本属性」。
3. 在表单中编辑组效果。
4. 执行「写入模型 rysm 文件夹」。

此操作读取 `scripts/blockbench.js` 和 `scripts/migrated.js` 中的生成配置。导入后的属性保存在当前项目中。

### 使用旧组名前缀的模型

1. 打开旧模型。
2. 执行「导入旧组名并生成脚本」。
3. 保存项目并写入模型文件夹。

插件将组名中的效果转换为普通组名和 JS 属性。

## 编写 JS

1. 打开「JavaScript 编辑与预览」。
2. 选择已有文件，或填写新的路径，例如 `scripts/main.js`。
3. 编辑脚本并点击「保存」。

桌面版将脚本写入模型的 `rysm/` 目录，并把新脚本加入清单。网页版将脚本下载为 JS 文件；把文件放入模型的 `rysm/scripts/`，再将路径加入 `rysm/manifest.json`。

各入口的用途如下：

| 事件 | 写法 | 用途 |
| --- | --- | --- |
| `group_modifier` | `event.modify(name)` | 设置组效果 |
| `cosmic` | `event.register(name, options)` | 声明星空贴图 |
| `molang` | `event.register(name, callback, options)` | 注册动画算法 |
| `load` | `event.logKeyBindings()` | 输出游戏快捷键列表 |
| `tick` | `game => {...}` | 更新按键状态和实例数据 |
| `pose` | `game => {...}` | 调整组姿态 |

所有事件使用 `Rysm.model('事件名', callback)`。手写算法使用独立文件，例如 `scripts/main.js`。`blockbench.js` 保存表单配置，`migrated.js` 保存迁移配置。

完整脚本见 [中文注释示例](../examples/rysmjs/README.md#阅读和修改脚本)。

## 运行预览

1. 在「JavaScript 编辑与预览」中填写模拟数据。
2. 点击「运行预览」。
3. 修改模拟按键或实体数据，查看视口和数值输出。
4. 点击「停止」恢复开始预览前的姿态。

| 数据项 | 用途 | 示例 |
| --- | --- | --- |
| `entity` | 模拟血量、位置和玩家朝向 | `{"health":10,"maxHealth":20,"local":true}` |
| `camera` | 模拟摄像机角度 | `{"yaw":30,"pitch":10}` |
| `molang` | 提供脚本查询的 Molang 数值 | `{"rysm.longitudinal_speed":1}` |
| `keys` | 设置当前按住的原始按键 | `["key.keyboard.escape"]` |
| `bindings` | 设置当前按住的游戏快捷键 | `["key.inventory"]` |
| `keyBindings` | 提供快捷键查询数据 | `[{"id":"key.example.open","category":"example","name":"打开界面","key":"key.keyboard.b"}]` |
| `remote` | 提供远端同步值 | `{"toggle":1}` |

在编辑器的默认 JSON 上修改所需字段。运行后向 `keys` 或 `bindings` 添加标识可模拟按下，删除标识可模拟松开。将 `entity.local` 设为 `false`，再填写 `remote`，可预览其他玩家看到的同步动画。

`game.print(...)` 的内容显示在预览输出区。`game.input.bindings(...)` 查询模拟数据中的 `keyBindings` 列表。

视口预览显示组姿态、面向摄像机和星空动画。物理检查器显示骨链、碰撞体和角度范围。打开碰撞块点选后，可在视口选择碰撞方块。物理运动在 Minecraft 中查看。

## 开发检查

在仓库根目录执行：

```powershell
node --check blockbench/reimagined_ysm_physics_groups.js
node blockbench/reimagined_ysm_physics_groups.test.cjs
```

加上 `--browser` 可检查浏览器中的脚本预览和着色器编译：

```powershell
node blockbench/reimagined_ysm_physics_groups.test.cjs --browser
```
