package com.elfmcys.yesstevemodel.client.compat.touhoulittlemaid.event;

import net.everla.reimaginedysm.core.YesSteveModel;
import com.elfmcys.yesstevemodel.client.compat.touhoulittlemaid.capability.MaidCapabilityProvider;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@OnlyIn(Dist.CLIENT)
public final class MaidCapabilityEvent {

    private static final ResourceLocation CAPABILITY_KEY = new ResourceLocation(net.everla.ysmCompactLayer.YsmIdentity.DATA_NAMESPACE, "ysm_maid");

    @SubscribeEvent
    public void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        Object object = event.getObject();
        if (object instanceof EntityMaid entityMaid) {
            if (entityMaid.level().isClientSide()) {
                event.addCapability(CAPABILITY_KEY, new MaidCapabilityProvider(entityMaid));
            }
        }
    }
}