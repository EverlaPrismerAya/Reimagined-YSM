// Hand-authored algorithms: keep separate from generated group properties.
Rysm.cosmic("crosses", {textures:["cross.png"]});
Rysm.cosmic("pulses", {textures:["pulse.png"]});
Rysm.update(ctx => {
    if (ctx.input.key("key.keyboard.escape").pressed) ctx.state.esc = !ctx.state.esc;
    if (ctx.input.key("key.mouse.left").released) ctx.state.releases = (ctx.state.releases || 0) + 1;
});
Rysm.molang("escape_toggle", ctx => !!ctx.state.esc, {fallback:0,sync:true});
Rysm.molang("inventory", ctx => ctx.input.binding("key.inventory").down, {fallback:0,sync:true});
Rysm.molang("backpack", ctx => ctx.input.binding("key.sophisticatedbackpacks.open_backpack").down, {fallback:0,sync:true});
Rysm.molang("mouse", ctx => ctx.input.key("key.mouse.left").down, {fallback:0,sync:true});
Rysm.molang("releases", ctx => (ctx.state.releases || 0) % 2, {fallback:0,sync:true});
Rysm.molang("health_ratio", ctx => ctx.entity.health / Math.max(ctx.entity.maxHealth,1), {fallback:1});
Rysm.molang("movement", ctx => ctx.molang("rysm.longitudinal_speed"), {fallback:0});
Rysm.molang("sum", (ctx,a,b) => a+b, {fallback:0});
Rysm.molang("frame", ctx => 1 + Math.floor(ctx.time.seconds) % 4, {fallback:1});
Rysm.pose(ctx => {
    ctx.group("ornament").rotate(0,ctx.time.seconds*60,0);
    ctx.group("camera_indicator").rotate(ctx.camera.pitch,ctx.camera.yaw-ctx.entity.yaw,0);
    ctx.group("movement_gauge").move(0,Math.min(6,Math.abs(ctx.molang("rysmjs.movement"))*2),0);
    ctx.group("health_gauge").scale(1,Math.max(0.1,ctx.molang("rysmjs.health_ratio")*3),1);
    ctx.group("esc_indicator").move(0,ctx.molang("rysmjs.escape_toggle")*3,0);
    ctx.group("inventory_indicator").move(0,ctx.molang("rysmjs.inventory")*3,0);
    ctx.group("backpack_indicator").move(0,ctx.molang("rysmjs.backpack")*3,0);
    ctx.group("mouse_indicator").move(0,ctx.molang("rysmjs.mouse")*3,0);
    ctx.group("release_indicator").move(0,ctx.molang("rysmjs.releases")*3,0);
});
