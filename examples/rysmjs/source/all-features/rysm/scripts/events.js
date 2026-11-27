// 示例：加载时请求输出快捷键列表，便于查找其他模组的 binding 标识。
// 这是加载期操作，不要放入 tick 或 pose；不需要时可删除整个事件。
Rysm.model('load', event => {
    event.logKeyBindings();
});

// 示例：每客户端 tick 更新一次实例状态，适合切换开关、累计次数。
// pressed/released 只持续一个 tick；down 表示持续按住。
Rysm.model('tick', game => {
    if (game.input.key('key.keyboard.escape').pressed) {
        game.model.state.esc = !game.model.state.esc;
        game.print('ESC 开关：', game.model.state.esc);
    }
    if (game.input.key('key.mouse.left').released) {
        game.model.state.releases = (game.model.state.releases || 0) + 1;
    }
});

// 示例：动画求值后修改姿态。组变换只能在 pose 事件中进行。
// move 使用模型像素，rotate 使用度，scale 使用倍率；三者可链式调用。
// 每次求值前会还原上一帧变换，因此不会逐帧累积偏移。
Rysm.model('pose', game => {
    game.group('ornament')
        .rotate(0, game.time.seconds * 60, 0)
        .move(0, Math.sin(game.time.seconds * 2) * 0.5, 0);

    // 运行时上下文同时提供相机角度和实体角度。
    game.group('camera_indicator').rotate(game.camera.pitch, game.camera.yaw - game.entity.yaw, 0);
    game.group('movement_gauge').move(0, Math.min(6, Math.abs(game.molang('rysmjs.movement')) * 2), 0);
    game.group('health_gauge').scale(1, Math.max(0.1, game.molang('rysmjs.health_ratio') * 3), 1);

    // 通过同步后的 Molang 值控制指示灯，其他玩家也能看到相同开关状态。
    // 避免在这里直接读取 game.model.state.esc，否则远端不会得到所属玩家的状态。
    game.group('esc_indicator').move(0, game.molang('rysmjs.escape_toggle') * 3, 0);
    game.group('inventory_indicator').move(0, game.molang('rysmjs.inventory') * 3, 0);
    game.group('backpack_indicator').move(0, game.molang('rysmjs.backpack') * 3, 0);
    game.group('mouse_indicator').move(0, game.molang('rysmjs.mouse') * 3, 0);
    game.group('release_indicator').move(0, game.molang('rysmjs.releases') * 3, 0);
});
