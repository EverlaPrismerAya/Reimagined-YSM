[返回 Wiki 目录](README.md)

# 披风、衣摆与尾巴物理

物理效果让模型部件随角色动作摆动。先在 Blockbench 中安排好组的父子关系，再设置哪些组会摆动、哪些组是末端、哪些方块用于阻挡穿插。可以使用[编辑器表单](blockbench.md)完成这些设置，无需从零编写脚本。

## 设置物理链

先在 Blockbench 中建立父子关系：

```text
Body
├─ body_collider
└─ cape
   └─ cape_tip
      └─ cape_end
```

再为各组分配角色：

```js
Rysm.model('group_modifier', event => {
  event.modify('cape').physics({g: 9.8, s: 20, a: 90, bc: true});
  event.modify('cape_tip').physics({});
  event.modify('cape_end').end();
  event.modify('body_collider').collider();
});
```

将末端组放在骨骼的延伸方向，使骨骼长度大于零。身体碰撞组直接包含用于碰撞的方块。每个组从物理骨骼、末端和身体碰撞体中选择一种角色，再按需要叠加描边等效果。

尾巴使用 `.physics({kind: 'tail'})`。连续尾骨形成尾链。尾巴随水平移动摆动，停止移动后回到动画姿态。

| 参数 | 含义 | 普通物理默认值 | 尾骨默认值 |
| --- | --- | --- | --- |
| `r` | 阻尼；数值越大，运动衰减越快 | `1` | `10` |
| `g` | 世界向下的加速度，格/秒²；负数向上 | `9.8` | 使用 `gw` |
| `gw` | 朝动画目标的加速度，格/秒²；负数向外 | 使用 `g` | `3` |
| `s` | 回弹强度；数值越大，越快回到动画姿态 | `20` | `16` |
| `a` | 最大偏转角，度，范围 `0–180` | `90` | `50` |
| `c` | 骨骼碰撞半径，模型像素 | `1` | `1` |
| `air` | 空气阻力系数 | `0.5` | `0.5` |
| `bc` | 启用世界方块碰撞 | `false` | `false` |
| `enable` | 仅在条件成立时启用物理 | 默认启用 | 默认启用 |
| `disable` | 在条件成立时停用物理 | 默认启用 | 默认启用 |

`r`、`s`、`c` 和 `air` 使用大于或等于零的数值。重力选择 `g` 或 `gw`。连续同类物理骨骼继承父组参数，子组可以覆盖指定参数。

按动画或数值控制物理：

```js
Rysm.model('group_modifier', event => {
  // jump 动画播放时停用衣摆物理。
  event.modify('robe').physics({disable: '(jump)'});

  // jump 或 fly 动画播放时启用翅膀物理。
  event.modify('wing').physics({enable: '(jump,fly)'});

  // 两项比较同时成立时启用左翼物理。
  event.modify('leftwing').physics({
    enable: '(v.wings_phys>=1,rysmjs.toggle=1)'
  });
});
```

动画条件使用动画文件中的完整动画名。每个条件列表选择动画名或数值比较。数值比较支持 `v.*`、`variable.*` 和 `rysmjs.*`，运算符为 `=`、`!=`、`>`、`>=`、`<`、`<=`。`disable` 条件优先。

碰撞用于同一模型内的部件，以及启用方块碰撞后的世界方块；不用于两个玩家模型互相碰撞。调整后请进入游戏检查走动、转向和动画切换。

可对照[示例模型中的披风与尾巴](examples.md)。
