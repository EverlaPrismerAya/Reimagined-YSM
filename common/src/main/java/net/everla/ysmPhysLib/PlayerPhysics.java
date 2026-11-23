package net.everla.ysmPhysLib;

import com.elfmcys.yesstevemodel.client.entity.IPreviewAnimatable;
import com.elfmcys.yesstevemodel.client.renderer.ModelPreviewRenderer;
import com.elfmcys.yesstevemodel.geckolib3.core.AnimatableEntity;
import com.elfmcys.yesstevemodel.geckolib3.geo.animated.AnimatedGeoModel;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/** The Minecraft/rendering adapter for world entities. UI previews never simulate. */
public final class PlayerPhysics {
    private PlayerPhysics() {}

    public static void beforeAnimation(AnimatedGeoModel model) {
        if(model!=null && model.ysmPhysics!=null) model.ysmPhysics.restore(model.getMatrixData());
    }

    public static void render(AnimatedGeoModel model, AnimatableEntity<?> animatable, float partialTick, Matrix4f renderPose) {
        var mc=Minecraft.getInstance();
        boolean eligible=WorldPhysicsContext.allows(
                animatable instanceof IPreviewAnimatable || ModelPreviewRenderer.isPreview() || ModelPreviewRenderer.isExtraPlayer())
                && mc.level!=null && animatable.entity.level()==mc.level;
        if(!eligible) {
            // Inventory/HUD icons can draw the same instance between world passes.
            // Restore animation for that draw while retaining its world simulation.
            beforeAnimation(model);
            return;
        }
        int fps=PhysConfig.frameRate();
        if(fps==0) {
            GpuPhysics.deactivate(model.getMatrixData());
            beforeAnimation(model);
            if(model.ysmPhysics!=null) model.ysmPhysics.invalidate();
            return;
        }
        var definition=model.getGeoModel().ysmPhysics;
        if(definition==null || definition.joints.isEmpty()) return;
        if(model.ysmPhysics==null) model.ysmPhysics=new PhysSimulation(definition);
        model.ysmPhysics.setFrameRate(fps);
        boolean[] enabled=enabled(definition,animatable::isAnimationPlaying,animatable.getEvaluationContext()::physicsVariable);
        boolean allEnabled=true;
        for(var joint:definition.joints) allEnabled&=enabled[joint.bone()];
        // Conditional groups use the CPU solver while disabled; GPU topology remains immutable.
        if(!allEnabled) GpuPhysics.deactivate(model.getMatrixData());
        model.ysmPhysics.setEnabled(model.getMatrixData(),enabled);
        var entity=animatable.entity;
        double x=Mth.lerp(partialTick,entity.xo,entity.getX());
        double y=Mth.lerp(partialTick,entity.yo,entity.getY());
        double z=Mth.lerp(partialTick,entity.zo,entity.getZ());
        // World rendering pose includes camera rotation and camera-relative translation.
        // Undo both, then rebase near the entity to retain float precision far from spawn.
        var camera=mc.gameRenderer.getMainCamera();
        var cameraPos=camera.getPosition();
        Matrix4f root=new Matrix4f().translation((float)(cameraPos.x-x),(float)(cameraPos.y-y),(float)(cameraPos.z-z))
                .mul(WorldPhysicsContext.modelToWorld(renderPose));
        if(allEnabled && GpuPhysics.prepare(model,root,(entity.tickCount+partialTick)/20.0,mc.isPaused(),entity.level(),x,y,z,fps,
                animatable.getTextureLocation()!=null,bounds->entity.level().getBlockCollisions(entity,bounds))) return;
        long started=System.nanoTime();
        model.ysmPhysics.update(model.getMatrixData(),root,(entity.tickCount+partialTick)/20.0,mc.isPaused(),
                entity.level(),x,y,z,bounds->entity.level().getBlockCollisions(entity,bounds));
        if(allEnabled && model.ysmPhysics.sampledFrame())
            GpuPhysics.recordCpu(definition,entity.level(),fps,(entity.tickCount+partialTick)/20.0,System.nanoTime()-started);
    }

    static boolean[] enabled(PhysDefinition definition,java.util.function.Predicate<String> animation,
                             java.util.function.ToDoubleFunction<String> values) {
        boolean[] result=new boolean[definition.bones.size()];
        java.util.Arrays.fill(result,true);
        for(var joint:definition.joints) result[joint.bone()]=(joint.parent()<0||result[joint.parent()])
                &&joint.parameters().enabled(animation,values);
        return result;
    }

    public static float[] hideEndpoints(com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel model, float[] parameters) {
        if(model.ysmPhysics==null || model.ysmPhysics.ends.length==0) return parameters;
        float[] result=parameters.clone();
        for(int end:model.ysmPhysics.ends) {
            result[end*12+9]=1;
            result[end*12+10]=1;
            result[end*12+6]=result[end*12+7]=result[end*12+8]=0;
        }
        return result;
    }
}
