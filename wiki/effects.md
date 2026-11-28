[返回 Wiki 目录](README.md)

# 模型效果

为模型添加发光、星空、描边或翻页动画。初次制作时，建议先用 [Blockbench 编辑器](blockbench.md)填写表单；需要手写时再参考下面的示例。

## 用 RYSMJS 设置模型

在 Blockbench 中使用普通组名，例如 `cape`、`ornament` 和 `panel`。在脚本中按组名设置效果。

### 1. 添加脚本清单

在模型目录创建 `rysm/manifest.json`：

```json
{
  "version": 1,
  "scripts": ["scripts/main.js"]
}
```

清单中的路径相对于 `rysm/`。多个脚本按清单顺序加载。

### 2. 添加组效果

创建 `rysm/scripts/main.js`：

```js
Rysm.model('group_modifier', event => {
  // 为 panel 同时设置星空和描边。
  event.modify('panel')
    .cosmic({sc: '3388FF', sa: 25})
    .outline({width: 2, color: '33AAFF'});

  // 让 ornament 面向摄像机，并启用发光。
  event.modify('ornament').facingCamera().glow();
});
```

将示例中的组名改为模型中的组名。重新加载模型后查看效果。

### 3. 叠加效果

在一次 `modify` 后连续调用效果方法。也可以再次修改同一组。

```js
Rysm.model('group_modifier', event => {
  event.modify('panel').cosmic({sc: '3388FF', sa: 25});
  event.modify('panel').outline({width: 3});

  // 更新 sa，保留已设置的 sc。
  event.modify('panel').cosmic({sa: 15});
});
```

不同效果组合使用。同类效果按字段合并，同名字段使用后写的值。布尔效果可省略 `true`，例如 `.glow()`；传入 `false` 可关闭效果。

## 组效果速查

| 方法 | 用途 | 示例 |
| --- | --- | --- |
| `.physics({...})` | 添加物理骨骼 | `.physics({g: 9.8, s: 20})` |
| `.physics({kind: 'tail'})` | 添加尾骨 | `.physics({kind: 'tail', gw: 3})` |
| `.end()` | 标记物理链末端 | `event.modify('tail_end').end()` |
| `.collider()` | 将组内方块用作身体碰撞体 | `event.modify('body_collider').collider()` |
| `.facingCamera()` | 让组面向摄像机 | `.facingCamera()` |
| `.glow()` | 启用发光 | `.glow()` |
| `.item()` | 为单面贴图生成厚度 | `.item()` |
| `.cosmic({...})` | 添加星空效果 | `.cosmic({sc: '3388FF', sa: 25})` |
| `.outline({...})` | 添加描边 | `.outline({color: 'auto', width: 2})` |
| `.frameAnimation({...})` | 切换 UV 动画帧 | `.frameAnimation({count: 2, tick: 6})` |

### 星空与描边

星空随观察方向变化。普通子组继承星空和描边设置。

| 参数 | 含义 | 取值 |
| --- | --- | --- |
| `cosmic.sc` | 空间颜色 | 六位 RGB，例如 `'3388FF'` |
| `cosmic.sa` | 空间层透出模型贴图颜色的比例 | `0–100`，默认 `0` |
| `cosmic.ac` | 星体颜色 | 六位 RGB，例如 `'FFFFFF'` |
| `cosmic.aa` | 星体层透出空间颜色的比例 | `0–100`，默认 `0` |
| `cosmic.effect` | 自定义星空名称 | 在 `cosmic` 事件中注册的名称 |
| `outline.color` | 描边颜色 | 六位 RGB 或 `'auto'`，默认 `'auto'` |
| `outline.width` | 描边宽度 | `0–64` 渲染像素，默认 `2`，支持小数 |

颜色直接写六位 RGB，例如 `'33AAFF'`。自动描边颜色根据当前贴图计算。描边宽度为 `0` 时关闭描边。

自定义星空示例：

```js
Rysm.model('cosmic', event => {
  // 贴图位置：rysm/cosmic/crosses/cross.png
  event.register('crosses', {textures: ['cross.png']});
});

Rysm.model('group_modifier', event => {
  event.modify('panel').cosmic({effect: 'crosses'});
});
```

使用默认星空时，调用 `.cosmic({})`。自定义星空的竖向帧条通过同名 `.png.mcmeta` 设置帧时间。

### 面向摄像机、单面厚度与帧动画

面向摄像机的组围绕组枢轴旋转。制作平面效果时，将贴图平面放在组的局部 XY 平面。

使用 `.item()` 时，在组内直接放置一个方块。为一个面分配有效 UV，其余面使用空 UV。系统沿贴图轮廓生成侧面，总厚度为一个模型像素。

帧动画也使用单个方块和一个有效 UV 面。将各帧在贴图中竖直排列，按第一帧设置 UV。

```js
Rysm.model('group_modifier', event => {
  // 共两帧，每 6 tick 切换一次。20 tick 等于 1 秒。
  event.modify('frame_panel').frameAnimation({count: 2, tick: 6});

  // 用 Molang 值选择帧，帧号从 1 开始。
  event.modify('macro_panel').frameAnimation({
    count: 2,
    macro: 'rysmjs.frame',
    macroRepeat: false
  });
});
```

`tick` 和 `macro` 选择一种。`macroRepeat` 默认为 `true`，超过末帧后循环；设为 `false` 后保持末帧。

### 半透明方块

把需要合并外观的半透明方块放在同一普通组内。闭合方块相交时，内部重叠表面会隐藏，减少透明部件交叠造成的杂乱。旋转和骨骼动画也适用。开口或单面部件请在游戏中检查显示效果。

完整演示见[示例模型](examples.md)。
