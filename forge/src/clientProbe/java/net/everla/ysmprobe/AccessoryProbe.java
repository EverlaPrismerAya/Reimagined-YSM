package net.everla.ysmprobe;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;

/** Actual installed accessory renderers; opt-in development probe only. */
public final class AccessoryProbe {
    private static Object layer;
    private static java.lang.reflect.Method render;
    public static void render(Object entity, PoseStack poses, int light, float partialTick) throws Exception {
        if(layer==null) {
            var resource=new ResourceLocation("goety_revelation","textures/entity/player/halo.png");
            if(Minecraft.getInstance().getResourceManager().getResource(resource).isEmpty())
                throw new AssertionError("Install the original Revelation halo texture in the probe resource pack");
            var type=Class.forName("net.everla.reimaginedysm.compat.goety.GoetyAccessoryLayer");
            layer=type.getConstructor().newInstance();
            render=java.util.Arrays.stream(type.getDeclaredMethods()).filter(m->m.getName().equals("renderLocatedAccessory")).findFirst().orElseThrow();
            render.setAccessible(true);
        }
        var player=(net.minecraft.world.entity.LivingEntity)entity.getClass().getMethod("getEntity").invoke(entity);
        net.minecraft.client.renderer.MultiBufferSource buffers=Minecraft.getInstance().renderBuffers().bufferSource();
        if(Boolean.getBoolean("ysm.accessoryBaseline")) {
            var world=buffers;
            buffers=type->{
                String name=type.toString();
                if(name.contains("goety_revelation:textures/entity/player/halo.png"))
                    return world.getBuffer(net.minecraft.client.renderer.RenderType.energySwirl(new ResourceLocation("goety_revelation","textures/entity/player/halo.png"),1,1));
                if(name.contains("unholy_hat_halo")) try {
                    return world.getBuffer((net.minecraft.client.renderer.RenderType)Class.forName("com.Polarice3.Goety.client.render.ModRenderType").getMethod("wraith",ResourceLocation.class)
                            .invoke(null,new ResourceLocation("goety","textures/models/curios/unholy_hat_halo.png")));
                } catch(ReflectiveOperationException e) {throw new RuntimeException(e);}
                return world.getBuffer(type);
            };
        }
        String[] ids={"goety_revelation:ascension_halo","goety:unholy_hat"};
        for(int i=0;i<ids.length;i++) {
            var stack=new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(ids[i])));
            if(i>0 && stack.isEmpty()) throw new AssertionError("Missing accessory: "+ids[i]);
            poses.pushPose();
            try {
                poses.translate((i-1)*1.5,0,0);
                if(i==0) {
                    var halo=layer.getClass().getDeclaredMethod("renderRevelationHalo",net.minecraft.world.entity.LivingEntity.class,boolean.class,PoseStack.class,net.minecraft.client.renderer.MultiBufferSource.class,int.class);
                    halo.setAccessible(true);
                    poses.translate(0,1.5,0);poses.scale(-1,-1,1);
                    halo.invoke(layer,player,false,poses,buffers,light);
                } else render.invoke(layer,stack,new SlotContext("head",player,0,false,true),poses,
                        buffers,light,entity,partialTick,(float)player.tickCount);
            } finally {poses.popPose();}
        }
    }
}
