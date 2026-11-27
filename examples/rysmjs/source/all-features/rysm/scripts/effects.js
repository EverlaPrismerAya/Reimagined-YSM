// 示例：声明星空贴图资源。注册阶段只声明资源，不操作骨骼或按键状态。
Rysm.model('cosmic', event => {
    // 路径相对于 rysm/cosmic/效果名/；此处使用 cosmic/crosses/cross.png。
    event.register('crosses', {textures: ['cross.png']});

    // pulses/pulse.png 是竖向帧条，帧时间取同目录 pulse.png.mcmeta。
    event.register('pulses', {textures: ['pulse.png']});
});

// 示例：为已有普通组名设置效果。名称必须与模型骨骼一致。
// 在同一组继续叠加效果，也可以分别修改单个字段。
Rysm.model('group_modifier', event => {
    // 一个组同时使用星空、描边、UV 帧动画。
    // frame_panel 是只有一个有效 UV 面的方块，满足帧动画的几何要求。
    event.modify('frame_panel')
        .cosmic({effect: 'crosses', sc: '3388FF', sa: 25})
        .outline({width: 2, color: '33AAFF'})
        .frameAnimation({count: 2, tick: 6});

    // 再次修改同一组时，仅覆盖指定字段；effect 和 sc 会保留。
    event.modify('frame_panel').cosmic({sa: 15});

    // 布尔效果可省略 true；关闭时传 false，如 .glow(false)。
    event.modify('ornament').facingCamera().glow();

    // 披风物理可与描边组合；末端和身体碰撞组须单独声明。
    // 为物理骨骼添加描边。
    event.modify('cape')
        .physics({g: 9.8, s: 20, a: 90, c: 1, bc: true})
        .outline({width: 1, color: '33AAFF'});
    event.modify('cape_end').end();
    event.modify('collider').collider();
});

// 其余示例部件的基础效果。
Rysm.model('group_modifier', event => {
    event.modify('tail').physics({kind: 'tail', g: 4, s: 12, a: 60, bc: true});
    event.modify('tail_end').end();
    event.modify('default_sky').cosmic({});
    event.modify('custom_sky').cosmic({effect: 'crosses', sc: '3388FF', sa: 25})
        .outline({width: 2, color: '33AAFF'});
    event.modify('animated_sky').cosmic({effect: 'pulses'});
    event.modify('macro_panel').frameAnimation({count: 2, macro: 'rysmjs.frame', macroRepeat: false});
    event.modify('paper_item').item().glow();
    ['esc_indicator', 'inventory_indicator', 'backpack_indicator', 'mouse_indicator', 'release_indicator'].forEach(name => {
        event.modify(name).glow().outline({color: 'auto', width: 1});
    });
});
