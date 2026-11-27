# RYSM JS 示例模型

此模型展示组效果、物理链、帧动画、按键控制和多人同步。脚本带有中文注释，可作为自己的模型脚本模板。

## 在游戏中使用

从以下两种方式中选择一种。

| 方式 | 操作 |
| --- | --- |
| 使用导出模型 | 将 `export/rysmjs-all-features.ysm` 和 `export/rysmjs-all-features.rysm` 一起复制到游戏的 `config/yes_steve_model/custom/` |
| 使用源码模型 | 将完整的 `source/all-features/` 文件夹复制到 `config/yes_steve_model/custom/` |

进入世界后，在模型选择界面选择示例模型。Reimagined YSM 使用 `.rysm` 中的脚本。官方 YSM 2.6.5 使用 `.ysm` 中保存的基础模型与默认数值。

## 阅读和修改脚本

[manifest.json](source/all-features/rysm/manifest.json) 决定加载顺序。

| 文件 | 内容 |
| --- | --- |
| [effects.js](source/all-features/rysm/scripts/effects.js) | 星空资源、物理链、同组效果叠加和参数合并 |
| [algorithms.js](source/all-features/rysm/scripts/algorithms.js) | 血量比例、带参数的函数、帧宏和同步数值 |
| [events.js](source/all-features/rysm/scripts/events.js) | 加载日志、按键开关、鼠标计数和姿态变化 |

将脚本用于自己的模型时，按以下顺序修改：

1. 将组名改为自己模型中的组名。
2. 将星空贴图放入对应的 `rysm/cosmic/效果名/` 目录。
3. 将脚本路径加入自己的 `rysm/manifest.json`。
4. 重新加载模型，查看效果。

入口统一使用 `Rysm.model('事件名', callback)`。`molang` 和 `cosmic` 事件内使用 `event.register`。`tick` 负责更新状态，`pose` 负责调整组姿态。

例如，为同一组叠加星空和描边：

```js
Rysm.model('group_modifier', event => {
  event.modify('panel')
    .cosmic({sc: '3388FF', sa: 25})
    .outline({width: 2, color: '33AAFF'});
});
```

更多参数和 `game` 字段见 [主 README](../../README.md#game-速查)。

## 查看模型效果

| 模型部件 | 操作与效果 |
| --- | --- |
| `cape`、`cape_end`、`collider` | 移动角色，查看披风物理与描边 |
| `tail`、`tail_end` | 水平移动后停下，查看尾巴摆动与回弹 |
| 头顶 `ornament` | 转动视角，查看面向摄像机、发光、旋转与上下浮动 |
| 上方三块 `sky` | 查看默认星空、蓝色 `crosses` 星空与描边、`pulses` 动画星空 |
| 左侧 `frame_panel` | 查看每 6 tick 切换的两帧 UV，以及叠加的星空和描边 |
| 右侧 `macro_panel` | 帧宏每秒产生 `1/2/3/4`；两帧模型在数值超过 `2` 后保持末帧 |
| 最左 `paper_item` | 查看单面贴图生成的厚度与发光 |
| 第一排五个指示灯 | 从左到右对应 ESC 开关、物品栏、精妙背包、鼠标左键按住、鼠标左键释放次数奇偶；开启时上移 |
| 第二排四个指示器 | 从左到右显示血量缩放、前后速度、相机相对角度和 `sum(15,30)` 旋转 |

按 ESC 切换开关，聊天栏显示当前开关值，游戏菜单也会正常打开。按物品栏键或精妙背包键时，对应指示灯上移。快捷键跟随游戏中的重绑定设置。通过 `/reimaginedysm keys` 查询模组的快捷键名称。

五个输入指示灯使用 `sync: true`。在支持同步的服务器上，其他玩家可看到相同状态。

## 在 Blockbench 中预览

1. 安装 [分组编辑器](../../blockbench/README.md)。
2. 导入 `source/all-features/models/main.json`。
3. 将项目保存为同目录中的 `main.bbmodel`。
4. 执行「导入模型脚本属性」。
5. 打开「JavaScript 编辑与预览」。
6. 在默认模拟数据中添加 `molang` 查询值，再点击「运行预览」。

以下 JSON 可直接用作模拟数据：

```json
{
  "entity": {"local": true, "health": 10, "maxHealth": 20, "yaw": 0},
  "camera": {"yaw": 30, "pitch": 10},
  "molang": {"rysm.longitudinal_speed": 1},
  "keys": [],
  "bindings": [],
  "remote": {}
}
```

运行后，在 `keys` 中添加 `"key.keyboard.escape"`，可切换 ESC 指示灯。在 `bindings` 中添加 `"key.inventory"`，可点亮物品栏指示灯。删除标识可模拟松开。

预览远端状态时，将 `entity.local` 改为 `false`，并填写：

```json
{
  "escape_toggle": 1,
  "inventory": 0,
  "backpack": 0,
  "mouse": 0,
  "releases": 1
}
```

将上面的对象放入 `remote` 字段。视口预览显示组姿态和星空动画，物理运动在游戏中查看。

## 开发检查

在仓库根目录执行以下命令，检查脚本并重新生成导出双包：

```powershell
./gradlew.bat :common:scriptChecks -PscriptExamples
```

检查自定义星空纹理上传时，添加 `-PscriptGpu`。构建 Forge 和 Fabric 版时执行：

```powershell
./gradlew.bat :forge:build :fabric:build
```

其他检查命令：

```powershell
./gradlew.bat :common:modelLoadingChecks
node blockbench/reimagined_ysm_physics_groups.test.cjs
```

## 资源许可

此示例的几何、普通贴图和自定义星空贴图使用 CC0-1.0。
