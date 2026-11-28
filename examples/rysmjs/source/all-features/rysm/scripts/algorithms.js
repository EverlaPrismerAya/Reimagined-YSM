// 示例：注册动画可以读取的数值。此事件在模型脚本加载时执行。
// 名称不带 rysmjs. 前缀；动画里写 rysmjs.health_ratio 或 rysmjs.sum(1, 2)。
Rysm.model('molang', event => {
    // 根据当前血量计算，避免除以零。失败时返回 fallback。
    event.register('health_ratio', game => {
        return game.entity.health / Math.max(game.entity.maxHealth, 1);
    }, {fallback: 1, sync: true});

    // game 是运行时上下文，后续参数来自动画中的函数调用。
    // 带数值参数的算法不能设置 sync:true。
    // 未传入的参数按零计算，预览器不带参数查询时也有有效结果。
    event.register('sum', (game, a, b) => (a || 0) + (b || 0), {fallback: 0});

    // 读取已有的只读 Molang 查询，不修改游戏或动画变量。
    event.register('movement', game => game.molang('rysm.longitudinal_speed'), {fallback: 0, sync: true});

    // 帧动画宏从 1 开始编号。这里每秒产生 1/2/3/4。
    // macro_panel 只有两帧且 macroRepeat:false，超过 2 后保持末帧。
    event.register('frame', game => 1 + Math.floor(game.time.seconds) % 4, {fallback: 1, sync: true});

    // state 属于当前模型实例，由 events.js 的 tick 事件更新。
    // sync:true 让其他玩家读取所属玩家的结果，而非读取他们自己的键盘。
    event.register('escape_toggle', game => !!game.model.state.esc, {fallback: 0, sync: true});
    event.register('releases', game => (game.model.state.releases || 0) % 2, {fallback: 0, sync: true});

    // binding 跟随游戏设置中的按键重绑定；未安装对应模组时返回未按下。
    event.register('inventory', game => game.input.binding('key.inventory').down, {fallback: 0, sync: true});
    event.register('backpack', game => game.input.binding('key.sophisticatedbackpacks.open_backpack').down, {fallback: 0, sync: true});

    // key 使用原始键标识，如 key.keyboard.escape、key.mouse.left。
    event.register('mouse', game => game.input.key('key.mouse.left').down, {fallback: 0, sync: true});
});
