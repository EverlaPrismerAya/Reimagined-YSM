// 复杂示例：模组属性、修饰倍率、组合条件和数值平滑。
// 将 example:generic.player_flying_speed 改为实际的属性 ID。
Rysm.model('molang', event => {
    // 当前值包含属性修饰器。属性未注册或玩家没有该属性时使用 0。
    event.register('player_flying_speed', game => {
        return game.entity.attribute('example:generic.player_flying_speed', 0);
    }, {fallback: 0, sync: true});

    // 例如：基础值 0.05、当前值 0.15，倍率为 3。
    event.register('flying_speed_multiplier', game => {
        const current = game.entity.attribute('example:generic.player_flying_speed', 0);
        const base = game.entity.attributeBase('example:generic.player_flying_speed', 0);
        return base > 0 ? current / base : 0;
    }, {fallback: 0, sync: true});

    // 动画中使用 rysmjs.flying_speed_above(0.1)。布尔结果转为 1 或 0。
    // 带参数的算法按调用求值。
    event.register('flying_speed_above', (game, threshold) => {
        return game.entity.attribute('example:generic.player_flying_speed', 0) > (threshold || 0);
    }, {fallback: 0});

    // 组合飞行属性与生命值：速度大于 0，且血量至少为最大值的 25%。
    event.register('flight_ready', game => {
        return game.molang('rysmjs.player_flying_speed') > 0
            && game.entity.health / Math.max(1, game.entity.maxHealth) >= 0.25;
    }, {fallback: 0, sync: true});

    // 动画读取 tick 中计算的平滑速度。
    event.register('smooth_flying_speed', game => game.model.state.smoothFlyingSpeed || 0, {fallback: 0, sync: true});
});

Rysm.model('tick', game => {
    const target = game.entity.attribute('example:generic.player_flying_speed', 0);
    const previous = game.model.state.smoothFlyingSpeed;
    // 每 tick 向目标靠近 20%；第一次直接使用目标值。
    game.model.state.smoothFlyingSpeed = previous === undefined ? target : previous + (target - previous) * 0.2;

    // 按 F10 在聊天栏查看当前值和基础值。
    if (game.input.key('key.keyboard.f10').pressed) {
        game.print('当前速度：', game.entity.attribute('example:generic.player_flying_speed', 0));
        game.print('基础速度：', game.entity.attributeBase('example:generic.player_flying_speed', 0));
    }
});
