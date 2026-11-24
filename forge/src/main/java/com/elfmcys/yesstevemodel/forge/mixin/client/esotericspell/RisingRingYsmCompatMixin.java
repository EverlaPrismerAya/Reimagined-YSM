package com.elfmcys.yesstevemodel.forge.mixin.client.esotericspell;

import com.elfmcys.yesstevemodel.capability.PlayerCapability;
import com.elfmcys.yesstevemodel.geckolib3.core.AnimatableEntity;
import com.inolia_zaicek.barrier_spell.compat.yesstevemodel.RisingRingYsmCompat.Scale;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.inolia_zaicek.barrier_spell.compat.yesstevemodel.RisingRingYsmCompat", remap = false)
public abstract class RisingRingYsmCompatMixin {
    /**
     * @author ReimaginedYSM
     * @reason Replace the dependency on official YSM's obfuscated animatable class.
     */
    @Overwrite(remap = false)
    public static LivingEntity getRenderedEntity(Object animatable) {
        if (com.elfmcys.yesstevemodel.RuntimePolicy.isDisabled()) return null;
        return animatable instanceof AnimatableEntity<?> model && model.getEntity() instanceof LivingEntity entity
                ? entity : null;
    }

    /**
     * @author ReimaginedYSM
     * @reason Use ReimaginedYSM's capability instead of official YSM's obfuscated provider.
     */
    @Overwrite(remap = false)
    public static boolean hasActiveModel(LivingEntity entity) {
        if (com.elfmcys.yesstevemodel.RuntimePolicy.isDisabled()) return false;
        return entity instanceof Player player
                && PlayerCapability.get(player).map(PlayerCapability::isModelActive).orElse(false);
    }

    /**
     * @author ReimaginedYSM
     * @reason Preserve ring scaling without linking official YSM's obfuscated capability.
     */
    @Overwrite(remap = false)
    public static Scale readScale(LivingEntity entity) {
        if (com.elfmcys.yesstevemodel.RuntimePolicy.isDisabled()) return Scale.DEFAULT;
        if (!(entity instanceof Player player)) return Scale.DEFAULT;
        return PlayerCapability.get(player).filter(PlayerCapability::isModelActive)
                .map(cap -> new Scale(Mth.clamp(cap.getHeightScale(), 0.1F, 10.0F),
                        Mth.clamp(cap.getWidthScale(), 0.1F, 10.0F)))
                .orElse(Scale.DEFAULT);
    }
}
