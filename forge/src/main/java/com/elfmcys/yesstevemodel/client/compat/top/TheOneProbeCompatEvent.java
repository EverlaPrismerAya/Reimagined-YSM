package com.elfmcys.yesstevemodel.client.compat.top;

import net.everla.reimaginedysm.core.RuntimePolicy;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public final class TheOneProbeCompatEvent {
    @SubscribeEvent
    public static void onInterModEnqueue(InterModEnqueueEvent event) {
        if (RuntimePolicy.isDisabled()) {
            return;
        }
        InterModComms.sendTo("theoneprobe", "getTheOneProbe", () -> {
            return new TheOneProbeEntityProvider();
        });
    }
}