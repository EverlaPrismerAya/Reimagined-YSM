[返回 Wiki 目录](README.md)

# 按键与动画脚本

需要“按键切换装饰”“根据血量改变外观”等效果时，可以添加模型脚本。本页供愿意修改少量代码的模型作者使用；普通玩家无需编辑脚本。

先按照[模型效果](effects.md#1-添加脚本清单)创建脚本清单和文件。脚本用于控制模型的外观和动画，无需安装额外的脚本运行工具。

RYSM脚本有事件脚本和简化脚本两种写法。本页先介绍RYSM事件脚本，RYSM简化脚本的写法及适用范围见本页末尾。

## RYSM事件脚本

RYSM事件脚本使用 `Rysm.model('事件名', callback)`。事件名决定这段代码在什么时候运行，或用来设置哪一种效果。

| 事件 | 用途 | 回调中的主要接口 |
| --- | --- | --- |
| `group_modifier` | 设置组效果 | `event.modify(name)` |
| `cosmic` | 声明星空资源 | `event.register(name, options)` |
| `molang` | 注册可供动画查询的算法 | `event.register(name, callback, options)` |
| `load` | 在加载时输出快捷键列表 | `event.logKeyBindings()` |
| `tick` | 每客户端 tick 更新状态 | `game.model.state`、`game.input` |
| `pose` | 在动画求值后调整姿态 | `game.group(name)` 及其他上下文 |

下面的示例用 ESC 切换一个开关，再按开关旋转装饰组：

```js
Rysm.model('tick', game => {
  if (game.input.key('key.keyboard.escape').pressed) {
    game.model.state.open = !game.model.state.open;
  }
});

Rysm.model('molang', event => {
  // 动画中使用 rysmjs.toggle。sync:true 将结果传给其他玩家。
  event.register('toggle', game => !!game.model.state.open, {fallback: 0, sync: true});
});

Rysm.model('pose', game => {
  game.group('ornament').rotate(0, game.molang('rysmjs.toggle') * 90, 0);
});
```

Molang 注册名称使用小写字母、数字和下划线，并以字母或下划线开头。在注册名称中省略 `rysmjs.` 前缀。将名称和选项直接写在 `event.register` 中。

`fallback` 是计算出错或尚未收到其他玩家的数据时使用的备用数值。`sync: true` 用于让其他玩家看到相同的按键动画。服主可以通过 `rysmJsSyncEnabled` 开关控制这项同步功能，默认开启。

### 读取玩家属性

| 方法 | 返回值 | 属性缺失时 |
| --- | --- | --- |
| `game.entity.attribute(id, fallback)` | 包含修饰器的当前属性值 | 返回 `fallback`，默认 `0` |
| `game.entity.attributeBase(id, fallback)` | 属性基础值 | 返回 `fallback`，默认 `0` |

属性 ID 使用 `模组ID:属性名`。例如，模组 `example` 提供的 `generic.player_flying_speed` 写为 `example:generic.player_flying_speed`。读取的是使用该模型的玩家在本机游戏中显示的属性值。

```js
Rysm.model('molang', event => {
  // 动画中使用 rysmjs.flying_speed。
  event.register('flying_speed', game => {
    return game.entity.attribute('example:generic.player_flying_speed', 0);
  }, {fallback: 0, sync: true});

  // 计算当前值相对基础值的倍率。
  event.register('flying_multiplier', game => {
    const current = game.entity.attribute('example:generic.player_flying_speed', 0);
    const base = game.entity.attributeBase('example:generic.player_flying_speed', 0);
    return base > 0 ? current / base : 0;
  }, {fallback: 0, sync: true});
});
```

属性查询中的 `fallback` 用于找不到属性或玩家没有该属性的情况。`event.register` 选项中的 `fallback` 用于计算出错或尚未收到同步数据的情况。`sync: true` 让其他玩家也能使用这项计算结果显示动画。

带参数的阈值判断、生命值组合条件和速度平滑见 [advanced.js](../examples/rysmjs/source/all-features/rysm/scripts/advanced.js)。其中的属性 ID 需要替换为实际模组的值。按 F10 可将当前值和基础值输出到聊天栏。

### 在聊天栏输出信息

使用 `game.print(...)` 在聊天栏查看文字、数值或模型变量。一次填写多个内容时，它们会用空格隔开。消息只有本机使用该模型的玩家能看到。检查脚本时建议配合按键触发，避免连续刷屏。

```js
Rysm.model('tick', game => {
  // 按 F8 输出当前血量和模型变量。
  if (game.input.key('key.keyboard.f8').pressed) {
    game.print('当前血量：', game.entity.health);
    game.print('模型变量：', game.model.state);
  }
});
```

### 快速查询某个模组的快捷键

下面的脚本在按 F9 时，将精妙背包的快捷键输出到聊天栏。

```js
Rysm.model('tick', game => {
  if (game.input.key('key.keyboard.f9').pressed) {
    // 将 sophisticatedbackpacks 改为需要查询的模组 ID。
    const keys = game.input.bindings('sophisticatedbackpacks');
    game.print('匹配的快捷键数量：', keys.length);
    keys.forEach(key => {
      game.print(key.name, '| ID:', key.id, '| 当前按键:', key.key);
    });
  }
});
```

`bindings(filter)` 按快捷键 ID 或分类 ID 包含的文字筛选，忽略大小写。省略参数可查询全部快捷键。每项包含 `id`、`category`、`key` 和 `name`。`name` 优先使用资源包中的简体中文，其次使用英语，最后使用快捷键 ID。查询结果中的 `id` 可传给 `game.input.binding(id)`。

### game 速查

`tick`、`pose` 和 Molang 算法回调提供 `game`。将需要保留的值写入 `game.model.state`，其他字段用于读取。

| 字段或方法 | 含义 |
| --- | --- |
| `game.entity.health` / `maxHealth` | 当前生命值 / 最大生命值 |
| `game.entity.position.x/y/z` | 世界坐标 |
| `game.entity.velocity.x/y/z` | 速度分量，格/tick |
| `game.entity.yaw` / `pitch` | 实体水平角 / 俯仰角，单位度 |
| `game.entity.pose` | 姿态名称，例如 `'standing'` |
| `game.entity.local` | 是否为本机玩家 |
| `game.entity.attribute(id, fallback)` / `attributeBase(id, fallback)` | 读取当前属性值 / 基础值 |
| `game.camera.yaw` / `pitch` | 摄像机水平角 / 俯仰角，单位度 |
| `game.time.ticks` / `seconds` | 实体存活时间，包含动画插值 |
| `game.model.state` | 当前模型需要记住的变量，例如装饰开关 |
| `game.print(...values)` | 在本机聊天栏输出文字、变量和对象 |
| `game.input.bindings(filter)` | 查询快捷键 ID、绑定按键和中文优先的名称 |
| `game.input.key(id)` | 指定键盘或鼠标按键的状态 |
| `game.input.binding(id)` | 跟随游戏设置的快捷键状态 |
| `game.molang(expression)` | 读取 Molang 数值，例如 `'rysmjs.toggle'` |
| `game.group(name)` | 获取组变换对象，在 `pose` 中使用 |

按键状态包含 `down`（按住）、`pressed`（本 tick 按下）和 `released`（本 tick 松开）。例如：

```js
game.input.key('key.mouse.left').down;
game.input.binding('key.inventory').pressed;
```

按键输入来自模型所属的本机玩家。多人动画通过同步的 Molang 值读取开关状态。查询快捷键名称时，在游戏中执行 `/reimaginedysm keys`。

组变换支持 `.move(x, y, z)`、`.rotate(x, y, z)` 和 `.scale(x, y, z)`。单位依次为模型像素、度和缩放倍率。这里设置的移动、旋转和缩放不会逐帧累加。

玩家速度还可通过 `rysm.longitudinal_speed` 和 `rysm.lateral_speed` 查询。单位为格/秒，方向以玩家水平朝向为准。前者正数向前、负数向后；后者正数向左、负数向右。

完整文件和安装包见[示例模型](examples.md)。

## RYSM简化脚本

RYSM简化脚本仅适合少量、简单的脚本，例如设置几个组效果，或用一个按键切换装饰。需要编写多段逻辑、添加较多效果或长期维护模型时，建议使用RYSM事件脚本，方便按用途整理和修改。

进入世界并加载对应模型时，RYSM简化脚本会自动转换后使用，无需手动修改原文件。

| 简化写法 | 用途 |
| --- | --- |
| `Rysm.group('组名', {glow: true})` | 为指定组添加效果，此例为自发光 |
| `Rysm.cosmic('效果名', {textures: ['贴图.png']})` | 为星空效果指定贴图，更多设置见[模型效果](effects.md) |
| `Rysm.molang('名称', game => 数值, {fallback: 0})` | 提供可在动画中使用的数值，使用时写作 `rysmjs.名称` |
| `Rysm.update(game => { /* 更新逻辑 */ })` | 持续检查按键或更新模型变量 |
| `Rysm.pose(game => { /* 姿态逻辑 */ })` | 调整组的位置、旋转和大小 |
| `Rysm.logKeyBindings()` | 加载模型时输出快捷键列表，直接写在脚本中即可 |

下面是RYSM简化脚本的示例：按 ESC 切换 `ornament` 组的旋转角度。请将 `ornament` 替换为模型中实际使用的组名。

```js
Rysm.group('ornament', {glow: true});

Rysm.update(game => {
  if (game.input.key('key.keyboard.escape').pressed) {
    game.model.state.open = !game.model.state.open;
  }
});

Rysm.molang('toggle', game => !!game.model.state.open, {fallback: 0, sync: true});

Rysm.pose(game => {
  game.group('ornament').rotate(0, game.molang('rysmjs.toggle') * 90, 0);
});
```

`game`、按键名称和 `fallback`、`sync` 的含义与上文相同。
