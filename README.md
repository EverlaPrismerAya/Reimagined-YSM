# Reimagined YSM

Reimagined YSM 是一款基于 Yes Steve Model 的模组，它修改了原版玩家模型，其核心使用 GeckoLib 库，并采用了 Minecraft 基岩版的模型和动画文件。这使得玩家可以根据自己的喜好自定义玩家模型和动画。

本项目基于OpenYSM，目标是提供一个在多人模式下优化了网络传输方式，并可以纯客户端模式运行的替代品。

本人不支持，不认可OpenYSM开发组反编译闭源项目并以MIT协议开源的行为。拿着反编译的产物作为把柄要挟别人，这并非开源精神，而是绝对可耻的，对开源精神的亵渎。

本人始终尊重YSM模组开发团队及其社区的模型作者的劳动成果。没有他们的劳动就没有YSM如今的辉煌。

此项目诞生的原因仅仅是我受不了YSM没有官方支持的纯客户端模式与模型下载传输优化。

Reimagined YSM 仅维护简体中文（zh_cn）和英语（en_us）两种语言。

## 改良与兼容性

### 官方 YSM 服务端兼容

本模组客户端兼容官方YSM2.6.x版本的传输协议。客户端在YSM2.6.x服务器时本模组的网络包行为将与官方YSM一致。

因网络传输相关优化的实现方式限制，暂不支持官方版YSM加入安装本模组的服务器。

### 官方 YSM 模型格式兼容

本模组兼容官方YSM截止到2.6.x版本的全部模型格式。

### 模型按需传输

默认情况下联机时先同步模型名称、哈希、分类和搜索所需的轻量目录。仅显示对应页模型的预览图，点击相应模型后触发加载。 加载完成后将使用此模型YSM原生的动态模型预览。

分为土豆、按页、全量三档。土豆为上述，按页为仅传输玩家可见的模型，全量则为原版YSM的同步模型库全部模型。

服务端 `config/yes_steve_model-common.toml` 的 `[server_scheduler] BandwidthLimit` 控制每位玩家的模型下载速率。

默认 `36` Mbps（4.5 MB/s，相当于基本上没有限速），范围 `1–999`。可通过管理员服务端配置面板调整，即时生效。

### 纯客户端模型

把模型文件夹（包含 `ysm.json`）、`.ysm` 或 `.zip` 放入 `config/yes_steve_model/client/`即可使用，例如 `client/my_model/ysm.json`。这些模型在多人模式下仅自己可见。

可在配置启用纯客户端模式，启用后本地的一切模型均将在客户端模式下运行。

### 新增变量

#### 玩家速度相关

| 变量 | 描述 | 备注 |
|---|---|---|
| `rysm.longitudinal_speed` | 返回以玩家朝向为参照系的前后速度,单位为格/秒 | 正数为向前，负数为向后，静止返回0 |
| `rysm.lateral_speed` | 返回以玩家朝向为参照系的左右速度,单位为格/秒 | 正数为向左，负数为向右，静止返回0|

方向以玩家视角的水平朝向为准。

因浮点精度误差，当玩家没有面向以90为倍数的角度时水平方向上可能产生极小的方向分量。

### 半透明方块合并

优化了半透明组的渲染逻辑，现在模型制作者只需考虑正面透明度，不再需要考虑模型交并与Z冲突问题。

同一普通组内的闭合半透明方块共享合并范围。 方块相交时，系统按当前姿态裁掉体积内部的面。部分相交的面保留外侧片段和对应 UV。此处理支持旋转方块和骨骼动画。

闭合透明块剔除背面，只显示朝向相机的外壳。开口、镂空和单面几何沿用各自的双面规则。裁切使用闭合方块作为实体体积。剩余半透明外表面按由远至近的顺序混合。

## 安装模型

将模型放入游戏目录下的对应文件夹。

| 用途 | 目录 | 说明 |
| --- | --- | --- |
| 单人模型或服务器共享模型 | `config/yes_steve_model/custom/` | 单人世界，或通过服务器向玩家共享 |
| 个人模型 | `config/yes_steve_model/client/` | 本机玩家可见 |

模型可以使用源码文件夹、`.zip` 或 `.ysm`。带 JS 的导出模型由同名的 `.ysm` 和 `.rysm` 组成。将这两个文件放在同一目录。

源码模型示例：

```text
config/yes_steve_model/custom/my_model/
├─ ysm.json
├─ models/main.json
├─ animations/main.animation.json
└─ rysm/
   ├─ manifest.json
   └─ scripts/main.js
```

进入世界后，在模型选择界面选择模型。启用「强制纯客户端模式」后，本地模型按个人模型使用。

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

同一普通组内的闭合半透明方块共享合并范围。方块相交时，系统根据当前姿态裁切内部表面，保留外侧表面和对应 UV。该处理支持旋转方块和骨骼动画。

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

## 编写按键和动画逻辑

所有入口使用 `Rysm.model('事件名', callback)`。

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

`fallback` 是算法出错或远端同步值尚未到达时使用的数值。`sync: true` 用于让其他玩家看到相同的按键动画。服务器通过 `rysmJsSyncEnabled` 设置是否转发同步值，默认开启。

### 读取玩家属性

| 方法 | 返回值 | 属性缺失时 |
| --- | --- | --- |
| `game.entity.attribute(id, fallback)` | 包含修饰器的当前属性值 | 返回 `fallback`，默认 `0` |
| `game.entity.attributeBase(id, fallback)` | 属性基础值 | 返回 `fallback`，默认 `0` |

属性 ID 使用 `模组ID:属性名`。例如，模组 `example` 注册的 `generic.player_flying_speed` 写为 `example:generic.player_flying_speed`。接口读取模型所属玩家的客户端属性值。

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

属性查询的 `fallback` 用于属性未注册或玩家没有该属性的情况。注册选项中的 `fallback` 用于算法执行错误或同步值尚未到达的情况。`sync: true` 将本机算法的数值结果传给其他玩家。

带参数的阈值判断、生命值组合条件和速度平滑见 [advanced.js](examples/rysmjs/source/all-features/rysm/scripts/advanced.js)。其中的属性 ID 需要替换为实际模组的值。按 F10 可将当前值和基础值输出到聊天栏。

### 在聊天栏输出信息

使用 `game.print(...)` 输出文字、数字、布尔值、数组或对象。多个值用空格分隔，对象按 JSON 输出。消息显示在模型所属本机玩家的聊天栏。每个模型实例每 tick 最多输出 16 条，每条最多 4096 个字符。

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
| `game.model.state` | 当前模型实例保存的数据 |
| `game.print(...values)` | 在本机聊天栏输出文字、变量和对象 |
| `game.input.bindings(filter)` | 查询快捷键 ID、绑定按键和中文优先的名称 |
| `game.input.key(id)` | 原始键盘或鼠标按键状态 |
| `game.input.binding(id)` | 跟随游戏设置的快捷键状态 |
| `game.molang(expression)` | 读取 Molang 数值，例如 `'rysmjs.toggle'` |
| `game.group(name)` | 获取组变换对象，在 `pose` 中使用 |

按键状态包含 `down`（按住）、`pressed`（本 tick 按下）和 `released`（本 tick 松开）。例如：

```js
game.input.key('key.mouse.left').down;
game.input.binding('key.inventory').pressed;
```

按键输入来自模型所属的本机玩家。多人动画通过同步的 Molang 值读取开关状态。查询快捷键名称时，在游戏中执行 `/reimaginedysm keys`。

组变换支持 `.move(x, y, z)`、`.rotate(x, y, z)` 和 `.scale(x, y, z)`。单位依次为模型像素、度和缩放倍率。每次动画求值前会还原上次变换。

玩家速度还可通过 `rysm.longitudinal_speed` 和 `rysm.lateral_speed` 查询。单位为格/秒，方向以玩家水平朝向为准。前者正数向前、负数向后；后者正数向左、负数向右。

## 使用 Blockbench

安装 [分组编辑器](blockbench/README.md)，在模型中选择组，再打开「编辑组属性」。保存后，插件将组效果写入带中英双语覆盖提示的 `rysm/scripts/generated.blockbench.js`。 选中物理骨或末端可打开整链表单，逐节编辑或批量设置全部物理参数；主页可新建脚本并使用补全和 RGB 选色。非主模型用文件名限定组名，例如 `models/arrow.json` 的 `Bow` 对应 `arrow.Bow`。

使用内置「JavaScript 编辑器」编写独立脚本，支持补全、逐行诊断、语法高亮与 RGB 颜色选择。编辑模式仅提供可开关的静态物理骨链与碰撞体积辅助。完整操作见 [Blockbench 使用说明](blockbench/README.md)。

带中文注释的脚本位于 [示例模型](examples/rysmjs/README.md)：

- [effects.js](examples/rysmjs/source/all-features/rysm/scripts/effects.js)：星空、物理和同组效果叠加。
- [algorithms.js](examples/rysmjs/source/all-features/rysm/scripts/algorithms.js)：血量、帧宏和多人同步。
- [advanced.js](examples/rysmjs/source/all-features/rysm/scripts/advanced.js)：模组属性、属性倍率、组合条件和数值平滑。
- [events.js](examples/rysmjs/source/all-features/rysm/scripts/events.js)：加载日志、按键开关和姿态。


## 饰品联动

Forge 版可为以下饰品添加模型定位组。定位组的枢轴、旋转、缩放和父组变换决定饰品位置。

| 模组或饰品 | 定位组 |
| --- | --- |
| 诡厄巫法及启示录：不洁圣冠、破碎之环、晋升之环 | `HaloLocator` |
| 诡厄巫法：启示录，终末之环 | `OdamaneHaloLocator` |
| 诡厄巫法：星翼幻想，使徒光环、哈迪斯光环 | `FantasyHaloLocator` |
| 秘法法术：伊始之环 | `EsotericHaloLocator` |
| 梦幻终焉：泡影之领域 | `FantasyEndingHaloLocator` |
| 妖怪们的归家：头饰与发饰 | `YoukaiHeadLocator` |

终末之环、秘法法术和梦幻终焉饰品默认按身体定位，其余按头部定位。为定位组设置变换可调整饰品位置。

模型通过 `ysm.has_any_curios` 检测对应 Goety 饰品时，由模型显示该饰品。标签查询 `has_any_curios_with_all_tags` 和 `has_any_curios_with_any_tag` 同样适用。安装 showmeurhalo 时，这些饰品由本模组的饰品层显示。

星翼幻想光环按 Curios 栏位顺序选择一个。装备哈迪斯光环时优先显示哈迪斯光环。

## 关于旧构建下RYSM模型的升级

旧组名前缀模型请使用 `.legacy-physic-upgrade-tool` 中的工具升级为 JS 驱动。仅接受单个文件夹格式 YSM 模型，暂不支持.zip或者.ysm。

模组目前已不再支持除ysmGlow这个官方版也有的修饰符以外的所有组名前缀修饰符。

## 构建

构建环境使用 Java 21。编译产物以 Java 17 为目标。

```powershell
./gradlew.bat :forge:build :fabric:build
```

产物位于 `forge/build/libs/` 和 `fabric/build/libs/`。

源码检查和示例生成命令见 [示例说明](examples/rysmjs/README.md#开发检查)。

## 来源与许可

源码使用 [MIT 许可证](LICENSE.txt)。默认模型使用 CC0，酒狐模型使用 CC BY-NC-SA 4.0。模型的具体条款见对应资源说明。

模型体系来源于 [Yes Steve Model](https://modrinth.com/mod/yes-steve-model)，模型与动画处理使用 [GeckoLib](https://github.com/bernie-g/geckolib)。可选原生渲染库见 [openysm.cpp](https://github.com/OpenYSMDev/openysm.cpp)。

默认星空的着色器和纹理来源于 [Avaritia](https://github.com/Morpheus1101/Avaritia/tree/07ce275b46e8afe4d932a0178777591794d7f612)。资源中的 `cosmic/SOURCE.txt` 保存了来源与适配说明。
