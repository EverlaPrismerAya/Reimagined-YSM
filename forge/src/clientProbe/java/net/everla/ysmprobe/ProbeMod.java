package net.everla.ysmprobe;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

@Mod("ysm_physics_probe")
public final class ProbeMod {
    public ProbeMod() throws ReflectiveOperationException {
        var type=Class.forName("net.everla.ysmPhysLib.PhysicsClientProbe");
        var probe=type.getConstructor().newInstance();var render=type.getMethod("render");
        var world=type.getMethod("renderWorld",com.mojang.blaze3d.vertex.PoseStack.class,float.class);
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.client.event.RenderLevelStageEvent event)->{
            if(event.getStage()==net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES)
                try {world.invoke(probe,event.getPoseStack(),event.getPartialTick());}
                catch(ReflectiveOperationException e) {throw new RuntimeException(e);}
        });
        MinecraftForge.EVENT_BUS.addListener((TickEvent.RenderTickEvent event)->{
            if(event.phase==TickEvent.Phase.END) try {render.invoke(probe);}
            catch(ReflectiveOperationException e) {throw new RuntimeException(e);}
        });
    }
}
