package net.everla.reimaginedysm.compat.goety;

import com.elfmcys.yesstevemodel.client.entity.CustomPlayerEntity;
import net.everla.reimaginedysm.compat.ModelAccessoryOverrides;
import com.elfmcys.yesstevemodel.geckolib3.geo.GeoLayerRenderer;
import com.elfmcys.yesstevemodel.geckolib3.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.SlotResult;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

import java.lang.reflect.Method;
import java.util.*;

/** Uses the installed mods' textures/renderers; no accessory assets are bundled. */
public final class GoetyAccessoryLayer extends GeoLayerRenderer<CustomPlayerEntity> {
    private static final Set<UUID> RENDERED = new HashSet<>();
    private static boolean rendering;
    private static final TagKey<net.minecraft.world.item.Item> FANTASY_HALOS = TagKey.create(
            Registries.ITEM, new ResourceLocation("starfantasy_goety", "halo_of_apostle"));
    private static final ResourceLocation HADES_HALO = new ResourceLocation("starfantasy_goety", "halo_of_hades");
    private static final ResourceLocation ESOTERIC_HALO = new ResourceLocation("esoteric_spell", "rising_inolia_ring");
    private static final ResourceLocation FANTASY_ENDING_HALO = new ResourceLocation("fantasy_ending", "the_domain_of_fade");
    private final ModelPart halo;
    private final PlayerModel<AbstractClientPlayer> parentModel;
    private final RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent;
    private Method spin;
    private Method meteoring;
    private boolean abilityResolved;

    public GoetyAccessoryLayer() {
        var mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("halo", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-8, -8, 0, 16, 16, 0), PartPose.ZERO);
        halo = LayerDefinition.create(mesh, 16, 16).bakeRoot().getChild("halo");
        parentModel = new PlayerModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        parent = new RenderLayerParent<>() {
            public PlayerModel<AbstractClientPlayer> getModel() { return parentModel; }
            public ResourceLocation getTextureLocation(AbstractClientPlayer player) { return player.getSkinTextureLocation(); }
        };
    }

    @SubscribeEvent
    public static void beginFrame(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) RENDERED.clear();
    }

    public static boolean rendered(Player player) { return RENDERED.contains(player.getUUID()); }
    public static boolean isRendering() { return rendering; }

    @Override
    public void render(PoseStack poses, MultiBufferSource buffers, int light, CustomPlayerEntity entity,
                       float limbSwing, float limbAmount, float partialTick, float age, float yaw, float pitch) {
        if (!(entity.getEntity() instanceof AbstractClientPlayer player) || player.isInvisible() || player.isSpectator()) return;
        var model = entity.getCurrentModel();
        if (model == null) return;
        RENDERED.add(player.getUUID());
        CuriosApi.getCuriosInventory(player).ifPresent(inventory -> {
            var fantasyHalo = selectFantasyHalo(inventory);
            // Equipment owns priority even when hidden or rendered by the model itself.
            int highestHalo = 0;
            for (var handler : inventory.getCurios().values()) {
                for (int i = 0; i < handler.getStacks().getSlots(); i++) {
                    highestHalo = Math.max(highestHalo, haloPriority(BuiltInRegistries.ITEM.getKey(
                            handler.getStacks().getStackInSlot(i).getItem())));
                }
            }
            final int equippedHalo = highestHalo;
            inventory.getCurios().forEach((slot, handler) -> {
            for (int i = 0; i < handler.getStacks().getSlots(); i++) {
                ItemStack stack = handler.getCosmeticStacks().getStackInSlot(i);
                boolean cosmetic = !stack.isEmpty();
                boolean visible = i < handler.getRenders().size() && handler.getRenders().get(i);
                if (!cosmetic && visible) stack = handler.getStacks().getStackInSlot(i);
                if (stack.isEmpty()) continue;
                var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                boolean fantasy = isFantasyHalo(stack);
                if (!isSupported(stack)) continue;
                if (fantasy && (fantasyHalo == null
                        || !fantasyHalo.slotContext().identifier().equals(slot)
                        || fantasyHalo.slotContext().index() != i
                        || isHadesHalo(fantasyHalo.stack()) && !isHadesHalo(stack))) continue;
                int priority = haloPriority(id);
                if (priority > 0 && priority < equippedHalo) continue;
                renderLocatedAccessory(stack, new SlotContext(slot, player, i, cosmetic, visible),
                        poses, buffers, light, entity, partialTick, age);
            }
            });
        });
        if (net.minecraftforge.fml.ModList.get().isLoaded("esoteric_spell")) {
            EsotericRenderer.hiddenRing(player).ifPresent(stack -> renderLocatedAccessory(stack,
                    new SlotContext("hidden_rising_ring", player, 0, false, true),
                    poses, buffers, light, entity, partialTick, age));
        }
    }

    private void renderLocatedAccessory(ItemStack stack, SlotContext slot, PoseStack poses,
                                        MultiBufferSource buffers, int light, CustomPlayerEntity entity,
                                        float partialTick, float age) {
        if (stack.isEmpty() || ModelAccessoryOverrides.handles(entity, stack)) return;
        var model = entity.getCurrentModel();
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        boolean fantasy = isFantasyHalo(stack);
        boolean bodyHalo = isBodyHalo(id);
        var locator = ESOTERIC_HALO.equals(id) ? model.esotericHaloBones()
                : FANTASY_ENDING_HALO.equals(id) ? model.fantasyEndingHaloBones()
                : fantasy ? model.fantasyHaloBones()
                : bodyHalo ? model.odamaneHaloBones() : model.haloBones();
        if (locator.isEmpty() && !bodyHalo && model.headBones().isEmpty()) return;
        poses.pushPose();
        try {
            if (!locator.isEmpty()) {
                if (RenderUtils.prepMatrixForLocator(poses, locator)) return;
                var tip = locator.get(locator.size() - 1);
                if (tip.getScaleX() == 0 || tip.getScaleY() == 0 || tip.getScaleZ() == 0) return;
            } else if (bodyHalo) {
                if (prepareBody(poses, model)) return;
                // Restore each native renderer's vanilla model origin in body space.
                poses.translate(0, id.getPath().equals("halo_of_the_end") ? 24.0 / 16.0 + 0.3 : 1.501, 0);
            } else if (RenderUtils.prepMatrixForLocator(poses, model.headBones())) return;
            poses.scale(-1, -1, 1);
            rendering = bodyHalo || fantasy;
            renderAccessory(stack, id, slot, poses,
                    buffers, light, partialTick, age);
        } finally {
            rendering = false;
            poses.popPose();
        }
    }

    private static boolean prepareBody(PoseStack poses,
            com.elfmcys.yesstevemodel.geckolib3.geo.animated.AnimatedGeoModel model) {
        var bones = model.getGeoModel().topLevelBones();
        int body = -1;
        for (String name : List.of("AllBody", "Body", "body")) {
            for (int i = 0; i < bones.size(); i++) {
                if (bones.get(i).getName().equals(name)) {
                    body = i;
                    break;
                }
            }
            if (body >= 0) break;
        }
        // Keep the renderer's feet-origin coordinates; a locator would move it to a pivot.
        var chain = new ArrayList<Integer>();
        for (int i = body; i >= 0; i = bones.get(i).parentIdx) chain.add(i);
        for (int i = chain.size() - 1; i >= 0; i--) {
            var bone = model.bones().get(bones.get(chain.get(i)).getBoneId());
            if (RenderUtils.prepMatrixForBone(poses, bone)) return true;
        }
        return false;
    }

    private static int haloPriority(ResourceLocation id) {
        if (!id.getNamespace().equals("goety_revelation")) return 0;
        return switch (id.getPath()) {
            case "halo_of_the_end" -> 3;
            case "ascension_halo" -> 2;
            case "broken_halo" -> 1;
            default -> 0;
        };
    }

    // Equipment determines the winner before visibility, cosmetics or model overrides are applied.
    private static SlotResult selectFantasyHalo(ICuriosItemHandler inventory) {
        SlotResult first = null;
        for (var entry : inventory.getCurios().entrySet()) {
            var stacks = entry.getValue().getStacks();
            for (int i = 0; i < stacks.getSlots(); i++) {
                ItemStack stack = stacks.getStackInSlot(i);
                if (!isFantasyHalo(stack)) continue;
                var result = new SlotResult(new SlotContext(entry.getKey(), inventory.getWearer(), i, false, true), stack);
                if (isHadesHalo(stack)) return result;
                if (first == null) first = result;
            }
        }
        return first;
    }

    private static boolean isHadesHalo(ItemStack stack) {
        return HADES_HALO.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    public static boolean isFantasyHalo(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(FANTASY_HALOS) || isHadesHalo(stack));
    }

    public static boolean isSupported(ItemStack stack) {
        return isSupported(BuiltInRegistries.ITEM.getKey(stack.getItem())) || isFantasyHalo(stack);
    }

    public static boolean isSupported(ResourceLocation id) {
        return ESOTERIC_HALO.equals(id) || FANTASY_ENDING_HALO.equals(id)
                || id.getNamespace().equals("goety") && Set.of("unholy_hat", "unholy_hat_halo").contains(id.getPath())
                || id.getNamespace().equals("goety_revelation")
                && Set.of("ascension_halo", "broken_halo", "halo_of_the_end").contains(id.getPath());
    }

    private static boolean isBodyHalo(ResourceLocation id) {
        return ESOTERIC_HALO.equals(id) || FANTASY_ENDING_HALO.equals(id)
                || id.getNamespace().equals("goety_revelation") && id.getPath().equals("halo_of_the_end");
    }

    private void renderAccessory(ItemStack stack, ResourceLocation id, SlotContext slot, PoseStack poses,
                                 MultiBufferSource buffers, int light, float partialTick, float age) {
        if (id.getNamespace().equals("goety")) {
            CuriosRendererRegistry.getRenderer(stack.getItem()).ifPresent(renderer -> {
                if (renderer instanceof HeadWearRenderer wear) wear.ysm$renderHead(slot.entity(), stack, poses, buffers, light, age);
            });
        } else if (ESOTERIC_HALO.equals(id)) {
            EsotericRenderer.render(slot.entity(), poses, buffers, light, partialTick);
        } else if (isBodyHalo(id) || isFantasyHalo(stack)) {
            // Keep the installed mod's native animation, shaders and item-dependent effects.
            parentModel.head.resetPose();
            parentModel.young = false;
            CuriosRendererRegistry.getRenderer(stack.getItem()).ifPresent(renderer -> {
                try (var state = FANTASY_ENDING_HALO.equals(id) ? new FantasyEndingHaloRenderState() : null) {
                    renderer.render(stack, slot, poses, parent, buffers, light, 0, 0, partialTick, age, 0, 0);
                }
            });
        } else {
            renderRevelationHalo(slot.entity(), id.getPath().equals("broken_halo"), poses, buffers, light);
        }
    }

    // Load the optional mod's API only when its item is actually rendered.
    private static final class EsotericRenderer {
        static Optional<ItemStack> hiddenRing(net.minecraft.world.entity.LivingEntity entity) {
            if (com.inolia_zaicek.barrier_spell.item.RisingInoliaRingHelper.findCurioRing(entity).isPresent()) {
                return Optional.empty();
            }
            return com.inolia_zaicek.barrier_spell.item.RisingInoliaRingHelper.getHiddenRing(entity)
                    .filter(stack -> ESOTERIC_HALO.equals(BuiltInRegistries.ITEM.getKey(stack.getItem())));
        }

        static void render(net.minecraft.world.entity.LivingEntity entity, PoseStack poses,
                           MultiBufferSource buffers, int light, float partialTick) {
            com.inolia_zaicek.barrier_spell.client.ring.RisingRingCurioRenderer.renderDirect(
                    entity, poses, buffers, light, partialTick);
        }
    }

    private void renderRevelationHalo(net.minecraft.world.entity.LivingEntity player, boolean broken,
                                      PoseStack poses, MultiBufferSource buffers, int light) {
        if (!abilityResolved) {
            abilityResolved = true;
            try {
                Class<?> ability = Class.forName("z1gned.goetyrevelation.util.PlayerAbilityHelper");
                spin = ability.getMethod("getSpin");
                meteoring = ability.getMethod("getMeteoring");
            } catch (ReflectiveOperationException e) {
                net.everla.reimaginedysm.core.YesSteveModel.LOGGER.warn("GR halo animation API unavailable", e);
            }
        }
        poses.translate(0, -12.0 / 16, 5.0 / 16);
        poses.mulPose(Axis.XP.rotation(0.7854F));
        String texture = broken ? "broken_halo" : "halo";
        if (!broken && spin != null && meteoring != null) {
            try {
                poses.mulPose(Axis.ZP.rotationDegrees(90 * ((Number) spin.invoke(player)).floatValue()));
                if (((Number) meteoring.invoke(player)).intValue() > 0) texture = "halo_second";
            } catch (ReflectiveOperationException | RuntimeException e) {
                spin = null;
                meteoring = null;
                net.everla.reimaginedysm.core.YesSteveModel.LOGGER.warn("GR halo animation API failed", e);
            }
        }
        var haloTexture = new ResourceLocation("goety_revelation", "textures/entity/player/" + texture + ".png");
        // A free-standing halo is geometry, not an overlay on an existing surface.
        // Deferred packs need its own depth, rather than the player's/world's depth.
        boolean shaders = net.everla.reimaginedysm.compat.oculus.OculusCompat.isShaderPackInUse();
        var type = shaders
                ? RenderType.entityTranslucent(haloTexture) : RenderType.energySwirl(haloTexture, 1, 1);
        halo.render(poses, buffers.getBuffer(type), shaders ? 15728880 : light, OverlayTexture.NO_OVERLAY);
    }

}
