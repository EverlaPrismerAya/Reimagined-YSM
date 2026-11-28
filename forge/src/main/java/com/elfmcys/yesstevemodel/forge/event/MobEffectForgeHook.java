package com.elfmcys.yesstevemodel.forge.event;

import net.everla.reimaginedysm.core.RuntimePolicy;

import net.everla.reimaginedysm.core.YesSteveModel;
import com.elfmcys.yesstevemodel.client.event.MobEffectEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.event.entity.living.MobEffectEvent.Added;
import net.minecraftforge.event.entity.living.MobEffectEvent.Expired;
import net.minecraftforge.event.entity.living.MobEffectEvent.Remove;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = YesSteveModel.MOD_ID)
public final class MobEffectForgeHook {

    private MobEffectForgeHook() {
    }

    @SubscribeEvent
    public static void onEffectAdded(Added event) {
        if (RuntimePolicy.isDisabled()) {
            return;
        }
        MobEffectInstance instance = event.getEffectInstance();
        if (instance != null) {
            MobEffectEvent.onEffectAdded(event.getEntity(), instance.getEffect(), instance.getAmplifier());
        }
    }

    @SubscribeEvent
    public static void onEffectRemoved(Remove event) {
        if (RuntimePolicy.isDisabled()) {
            return;
        }
        MobEffectEvent.onEffectRemoved(event.getEntity(), event.getEffect());
    }

    @SubscribeEvent
    public static void onEffectExpired(Expired event) {
        if (RuntimePolicy.isDisabled()) {
            return;
        }
        MobEffectInstance instance = event.getEffectInstance();
        if (instance != null) {
            MobEffectEvent.onEffectRemoved(event.getEntity(), instance.getEffect());
        }
    }
}
