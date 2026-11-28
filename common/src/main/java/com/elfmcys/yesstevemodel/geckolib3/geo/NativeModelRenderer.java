

package com.elfmcys.yesstevemodel.geckolib3.geo;

import net.everla.reimaginedysm.core.NativeLibLoader;
import com.elfmcys.yesstevemodel.client.renderer.ModelPreviewRenderer;
import com.elfmcys.yesstevemodel.config.GeneralConfig;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.yesstevemodel.util.log.ChatLogger;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import net.everla.reimaginedysm.compat.oculus.OculusCompat;
import net.everla.reimaginedysm.compat.optifine.OptiFineDetector;
import net.everla.reimaginedysm.gpu.GpuCapability;
import net.everla.reimaginedysm.gpu.GpuRenderPath;
import net.everla.reimaginedysm.gpu.IrisRenderPath;
import net.everla.reimaginedysm.compat.firstperson.FirstPersonCompat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class NativeModelRenderer {
    private static final Matrix4f projectionModelViewMatrix = new Matrix4f();

    static boolean isPreview() {
        // Only world rendering may use GPU draws. This also covers HUD mods that
        // call the dispatcher directly without setting our explicit preview flag.
        return ModelPreviewRenderer.isPreview() || ModelPreviewRenderer.isExtraPlayer()
                || !net.everla.ysmPhysLib.WorldPhysicsContext.allows(false);
    }

    static boolean isGpuPass(boolean firstPersonBody) {
        // FirstPerson still renders a world body, with physics and head hiding,
        // but must submit its vertices through Minecraft's buffered CPU path.
        return !isPreview() && !firstPersonBody;
    }

    static boolean isGpuPass(boolean firstPersonBody, boolean globally) {
        return globally || isGpuPass(firstPersonBody);
    }

    static boolean isGpuBuffer(VertexConsumer buffer, float alpha) {
        // Outline and multi-consumers must receive vertices (including invisible,
        // outline-only entities). Direct GPU draws cannot reproduce their passes.
        return buffer instanceof BufferBuilder && alpha == 1.0f;
    }

    public static void renderMesh(VertexConsumer buffer, PoseStack.Pose pose, GeoModel model, float[] boneParams, float[] stateBuffer, int textureIndex, int renderPartMask, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        renderMesh(buffer, pose, model, boneParams, stateBuffer, textureIndex, renderPartMask, packedLight, packedOverlay, red, green, blue, alpha, null);
    }

    public static void renderMesh(VertexConsumer buffer, PoseStack.Pose pose, GeoModel model, float[] boneParams, float[] stateBuffer, int textureIndex, int renderPartMask, int packedLight, int packedOverlay, float red, float green, float blue, float alpha, net.minecraft.resources.ResourceLocation textureLocation) {
        renderMesh(buffer, pose, model, boneParams, stateBuffer, textureIndex, renderPartMask, packedLight,
                packedOverlay, red, green, blue, alpha, textureLocation, null, 0);
    }

    private record FrameContext(double ticks, java.util.function.ToDoubleFunction<String> variables) {}
    private static final ThreadLocal<FrameContext> FRAME_CONTEXT = new ThreadLocal<>();

    static float frameOffset(GeoModel.BakedBone bone) {
        if (bone.frameAnimation == null) return 0;
        FrameContext context = FRAME_CONTEXT.get();
        return bone.frameHeight * bone.frameAnimation.frame(context == null ? 0 : context.ticks,
                context == null ? path -> 0 : context.variables);
    }

    public static void renderMesh(VertexConsumer buffer, PoseStack.Pose pose, GeoModel model, float[] boneParams,
            float[] stateBuffer, int textureIndex, int renderPartMask, int packedLight, int packedOverlay,
            float red, float green, float blue, float alpha, net.minecraft.resources.ResourceLocation textureLocation,
            com.elfmcys.yesstevemodel.geckolib3.core.AnimatableEntity<?> animatable, float partialTick) {
        FrameContext previous = FRAME_CONTEXT.get();
        if (model.hasFrameAnimations) FRAME_CONTEXT.set(new FrameContext(
                animatable == null ? 0 : animatable.getEntity().tickCount + (double) partialTick,
                animatable == null ? path -> 0 : animatable.getEvaluationContext()::physicsVariable));
        try {
            renderMeshCurrent(buffer, pose, model, boneParams, stateBuffer, textureIndex, renderPartMask,
                    packedLight, packedOverlay, red, green, blue, alpha, textureLocation);
        } finally {
            if (previous == null) FRAME_CONTEXT.remove(); else FRAME_CONTEXT.set(previous);
        }
    }

    private static void renderMeshCurrent(VertexConsumer buffer, PoseStack.Pose pose, GeoModel model, float[] boneParams,
            float[] stateBuffer, int textureIndex, int renderPartMask, int packedLight, int packedOverlay,
            float red, float green, float blue, float alpha, net.minecraft.resources.ResourceLocation textureLocation) {
        OculusCompat.updatePBRState();
        RenderSystem.getProjectionMatrix().mul(RenderSystem.getModelViewMatrix(), projectionModelViewMatrix);
        boolean isPreview = isPreview();

        if (textureLocation != null && buffer instanceof BufferBuilder
                && (model.hasGroupStyles || model.isTranslucentTexture(textureIndex))) {
            if (GroupEffectRenderer.render(pose, model,
                    boneParams, stateBuffer,
                    textureIndex, renderPartMask, packedLight, packedOverlay, red, green, blue, alpha,
                    textureLocation, isPreview)) return;
        }

        // GUI previews, including the HUD paper doll, share deferred buffers and
        // shader state with name tags and other UI. Submit them through that buffer.
        if (isGpuBuffer(buffer, alpha) && isGpuPass(FirstPersonCompat.isFirstPersonActive(), com.elfmcys.yesstevemodel.config.ClientConfigScope.effective(GeneralConfig.USE_GPU_RENDERER_GLOBALLY).get()) && textureLocation != null && NativeLibLoader.isLoaded() && !com.elfmcys.yesstevemodel.config.ClientConfigScope.effective(GeneralConfig.USE_COMPATIBILITY_RENDERER).get() && com.elfmcys.yesstevemodel.config.ClientConfigScope.effective(GeneralConfig.USE_GPU_RENDERER).get()) {

            if(!GpuCapability.isAvailable())
            {
                ChatLogger.INSTANCE.logFormatted("Disabled GPU renderer for: " + GpuCapability.getReason());
                com.elfmcys.yesstevemodel.config.ClientConfigScope.effective(GeneralConfig.USE_GPU_RENDERER).set(false);
                net.everla.ysmPhysLib.GpuPhysics.fallback(boneParams);
            }

            if (OculusCompat.isShaderPackInUse()) {
                if (IrisRenderPath.tryRender(model, pose, boneParams, textureIndex, renderPartMask, packedLight, packedOverlay, red, green, blue, alpha, textureLocation)) {
                    renderDynamicGeometry(buffer, pose, model, boneParams, stateBuffer, textureIndex,
                            renderPartMask, packedLight, packedOverlay, red, green, blue, alpha, isPreview);
                    return;
                }
            } else {
                if (GpuRenderPath.tryRender(model, pose, boneParams, stateBuffer, textureIndex, renderPartMask, packedLight, packedOverlay, red, green, blue, alpha, textureLocation)) {
                    renderDynamicGeometry(buffer, pose, model, boneParams, stateBuffer, textureIndex,
                            renderPartMask, packedLight, packedOverlay, red, green, blue, alpha, isPreview);
                    return;
                }
            }
        }

        // A GUI draw uses the restored animation without resetting the world's GPU solver.
        if (!isPreview) net.everla.ysmPhysLib.GpuPhysics.fallback(boneParams);
        boneParams=net.everla.ysmPhysLib.PlayerPhysics.hideEndpoints(model,boneParams);
        if (NativeLibLoader.isLoaded() && !com.elfmcys.yesstevemodel.config.ClientConfigScope.effective(GeneralConfig.USE_COMPATIBILITY_RENDERER).get()) { // WIP: SIMD MODEL RENDER
            nativeRenderModel(
                    buffer,
                    pose,
                    projectionModelViewMatrix,
                    OptiFineDetector.isOptifinePresent(),
                    model,
                    boneParams,
                    stateBuffer,
                    textureIndex,
                    renderPartMask,
                    packedLight,
                    packedOverlay,
                    red, green, blue, alpha,
                    isPreview
            );
            renderDynamicGeometry(buffer, pose, model, boneParams, stateBuffer, textureIndex,
                    renderPartMask, packedLight, packedOverlay, red, green, blue, alpha, isPreview);
        } else {
            renderModel(
                    buffer,
                    pose,
                    projectionModelViewMatrix,
                    OptiFineDetector.isOptifinePresent(),
                    model,
                    boneParams,
                    stateBuffer,
                    textureIndex,
                    renderPartMask,
                    packedLight,
                    packedOverlay,
                    red, green, blue, alpha,
                    isPreview
            );
        }
    }

    private static void renderDynamicGeometry(VertexConsumer buffer, PoseStack.Pose pose, GeoModel model,
            float[] params, float[] state, int texture, int part, int light, int overlay,
            float r, float g, float b, float a, boolean preview) {
        if (!model.hasFacingCameraBones && !model.hasFrameAnimations) return;
        renderModel(buffer, pose, projectionModelViewMatrix, OptiFineDetector.isOptifinePresent(), model,
                net.everla.ysmPhysLib.PlayerPhysics.hideEndpoints(model, params), state,
                texture, part, light, overlay, r, g, b, a, preview, true);
    }

    public static void renderModel(
            VertexConsumer vertexConsumer,
            PoseStack.Pose pose,
            Matrix4f projectionModelViewMatrix,
            boolean isCompatMode,
            GeoModel mesh,
            float[] boneParams,
            float[] stateBuffer,
            int textureIndex, int renderPartMask,
            int packedLight, int packedOverlay,
            float r, float g, float b, float a,
            boolean isPreview) {
        renderModel(vertexConsumer, pose, projectionModelViewMatrix, isCompatMode, mesh,
                boneParams, stateBuffer, textureIndex, renderPartMask, packedLight, packedOverlay,
                r, g, b, a, isPreview, false);
    }

    private static void renderModel(VertexConsumer vertexConsumer, PoseStack.Pose pose,
            Matrix4f projectionModelViewMatrix, boolean isCompatMode, GeoModel mesh,
            float[] boneParams, float[] stateBuffer, int textureIndex, int renderPartMask,
            int packedLight, int packedOverlay, float r, float g, float b, float a,
            boolean isPreview, boolean dynamicOnly) {

        if (mesh.bakedBones == null || mesh.bakedBones.isEmpty()) return;

        // TODO: 修復GC壓力
        Matrix4f rootPoseMat = pose.pose();
        Matrix3f rootNormalMC = pose.normal();
        Matrix4f projMat = RenderSystem.getProjectionMatrix();

        Matrix4f modelToCamera = FacingCameraTransform.previewToCamera(rootPoseMat, projMat.m11());
        if (mesh.hasFacingCameraBones && net.everla.ysmPhysLib.WorldPhysicsContext.allows(isPreview)) {
            modelToCamera = net.everla.ysmPhysLib.WorldPhysicsContext.modelToWorld(rootPoseMat);
            new Matrix4f().rotation(new org.joml.Quaternionf(net.minecraft.client.Minecraft.getInstance()
                    .getEntityRenderDispatcher().cameraOrientation()).conjugate()).mul(modelToCamera, modelToCamera);
        }
        Matrix4f identityMat = new Matrix4f();
        Matrix4f globalBoneMat = new Matrix4f();
        Matrix4f projBoneMat = new Matrix4f();
        Matrix3f localNormalMat = new Matrix3f();
        Matrix3f globalNormalMat = new Matrix3f();

        Vector4f p1 = new Vector4f();
        Vector4f p2 = new Vector4f();
        Vector4f p3 = new Vector4f();
        Vector4f tempPos = new Vector4f();
        Vector3f tempNorm = new Vector3f();
        Matrix4f[] boneLocalTransforms = new Matrix4f[mesh.bakedBones.size()];
        boolean[] boneVisible = new boolean[mesh.bakedBones.size()];

        if (!dynamicOnly) {
            for (int i = 0; i < mesh.bakedBones.size(); i++) {
                calculateBoneMatrix(i, mesh.bakedBones, boneParams, boneLocalTransforms, boneVisible,
                        identityMat, stateBuffer, modelToCamera);
            }
        }
        for (int i = 0; i < mesh.bakedBones.size(); i++) {
            GeoModel.BakedBone bone = mesh.bakedBones.get(i);
            if (dynamicOnly && !bone.dynamicGeometry()) continue;
            if (renderPartMask != 0 && bone.partMask != renderPartMask && bone.partMask != 3) continue;
            calculateBoneMatrix(i, mesh.bakedBones, boneParams, boneLocalTransforms, boneVisible,
                    identityMat, stateBuffer, modelToCamera);
            if (!boneVisible[i] || boneParams[i * 12 + 9] != 0 || bone.cubes.isEmpty()) continue;

            Matrix4f localBoneMat = boneLocalTransforms[i];
            globalBoneMat.set(rootPoseMat).mul(localBoneMat);
            projBoneMat.set(projMat).mul(globalBoneMat);

            // 法線全域矩陣
            localBoneMat.normal(localNormalMat);
            globalNormalMat.set(rootNormalMC).mul(localNormalMat);

            int currentPackedLight = bone.glow ? LightTexture.pack(15, 15) : packedLight;

            float frameV = frameOffset(bone);
            for (GeoModel.BakedCube cube : bone.cubes) {
                for (GeoModel.BakedQuad quad : cube.quads) {
                    if (cube.cullable) {
                        p1.set(quad.positions[0], quad.positions[1], quad.positions[2], 1.0f).mul(projBoneMat);
                        p2.set(quad.positions[3], quad.positions[4], quad.positions[5], 1.0f).mul(projBoneMat);
                        p3.set(quad.positions[6], quad.positions[7], quad.positions[8], 1.0f).mul(projBoneMat);
                        float det = p1.x() * (p2.y() * p3.w() - p3.y() * p2.w()) - p2.x() * (p1.y() * p3.w() - p3.y() * p1.w()) + p3.x() * (p1.y() * p2.w() - p2.y() * p1.w());
                        if (det <= 0.0f) {
                            continue;
                        }
                    }
                    tempNorm.set(quad.normal[0], quad.normal[1], quad.normal[2]).mul(globalNormalMat).normalize();
                    for (int v = 0; v < 4; v++) {
                        int positionOffset = v * 3;
                        int uvOffset = v * 2;
                        tempPos.set(quad.positions[positionOffset], quad.positions[positionOffset + 1], quad.positions[positionOffset + 2], 1.0f).mul(globalBoneMat);
                        vertexConsumer.vertex(tempPos.x(), tempPos.y(), tempPos.z(), r, g, b, a, quad.uvs[uvOffset], quad.uvs[uvOffset + 1] + frameV, packedOverlay, currentPackedLight, tempNorm.x(), tempNorm.y(), tempNorm.z());
                    }
                }
            }
        }
    }

    static Matrix4f calculateBoneMatrix(int idx, java.util.List<GeoModel.BakedBone> bones, float[] boneParams, Matrix4f[] cache, boolean[] visibleCache, Matrix4f rootPose, float[] stateBuffer, Matrix4f modelToCamera) {
        if (cache[idx] != null) return cache[idx];

        GeoModel.BakedBone bone = bones.get(idx);
        Matrix4f parentMatrix = rootPose;
        boolean isVisible = true;

        if (bone.parentIdx != -1) {
            parentMatrix = calculateBoneMatrix(bone.parentIdx, bones, boneParams, cache, visibleCache, rootPose, stateBuffer, modelToCamera);
            // 如果父骨骼不可見，子骨骼必然跟著不可見
            if (!visibleCache[bone.parentIdx] || boneParams[bone.parentIdx * 12 + 10] != 0) {
                isVisible = false;
            }
        }

        Matrix4f localMat = new Matrix4f(parentMatrix);

        int pOffset = idx * 12;
        float animRx = boneParams[pOffset];
        float animRy = boneParams[pOffset + 1];
        float animRz = boneParams[pOffset + 2];
        float animTx = boneParams[pOffset + 3];
        float animTy = boneParams[pOffset + 4];
        float animTz = boneParams[pOffset + 5];
        float animSx = boneParams[pOffset + 6];
        float animSy = boneParams[pOffset + 7];
        float animSz = boneParams[pOffset + 8];

        float unk1 = boneParams[pOffset + 9];
        float unk2 = boneParams[pOffset + 10];
        float unk3 = boneParams[pOffset + 11];

        if (unk1 != 0.0F && unk2 != 0.0F && unk3 != 0.0F) {
            //"".hashCode();
        }

        if (animSx == 0.0f && animSy == 0.0f && animSz == 0.0f) {
            isVisible = false;
        }/* else if (unk1 == 1 || unk2 == 1) isVisible = false;*/

        localMat.translate(
                (bone.pivotX - animTx) * 0.0625f,
                (bone.pivotY + animTy) * 0.0625f,
                (bone.pivotZ + animTz) * 0.0625f
        );
        if (bone.facingCamera) {
            FacingCameraTransform.apply(localMat, modelToCamera);
        } else {
            localMat.rotateZ(animRz);
            localMat.rotateY(animRy);
            localMat.rotateX(animRx);
        }

//        if (bone.name.equals("gun")) {
//            //"".hashCode();
//        }

        if (animSx != 1.0f || animSy != 1.0f || animSz != 1.0f) {
            localMat.scale(animSx, animSy, animSz);
        }

        if (unk3 == 1.0F && stateBuffer != null && isVisible) {
            int offset = idx * 4;
            // bone pivot abs
            if (offset + 2 < stateBuffer.length) {
                stateBuffer[offset + 0] =-localMat.m30() * 16;
                stateBuffer[offset + 1] = localMat.m31() * 16;
                stateBuffer[offset + 2] = localMat.m32() * 16;
            }
        }

        localMat.translate(-bone.pivotX / 16f, -bone.pivotY / 16f, -bone.pivotZ / 16f);

        cache[idx] = localMat;
        visibleCache[idx] = isVisible; // 保存當前骨骼的可見性
        return localMat;
    }

    private static final float[] matrixTransferArray = new float[48];
    @SuppressWarnings("unused") // TODO: native中直接往VertexConsumer中的buffer写入顶点
    public static void submitVertices(Object v, int vertexCount, ByteBuffer fBuf, ByteBuffer iBuf) {
        FloatBuffer f = fBuf.order(ByteOrder.nativeOrder()).asFloatBuffer();
        IntBuffer in = iBuf.order(ByteOrder.nativeOrder()).asIntBuffer();
        VertexConsumer vc = (VertexConsumer) v;
        int fIdx = 0, iIdx = 0;
        for (int n = 0; n < vertexCount; n++) {
            vc.vertex(
                    // position
                    f.get(fIdx),     f.get(fIdx + 1), f.get(fIdx + 2),
                    // rgba
                    f.get(fIdx + 3), f.get(fIdx + 4), f.get(fIdx + 5), f.get(fIdx + 6),
                    // uv
                    f.get(fIdx + 7), f.get(fIdx + 8),
                    // overlay light
                    in.get(iIdx),    in.get(iIdx + 1),
                    // normal
                    f.get(fIdx + 9), f.get(fIdx + 10), f.get(fIdx + 11)
            );
            fIdx += 12;
            iIdx += 2;
        }
    }


    public static void nativeRenderModel( // TODO:
            VertexConsumer vertexConsumer, PoseStack.Pose pose, Matrix4f projectionModelViewMatrix,
            boolean isCompatMode, GeoModel mesh, float[] boneVertex, float[] stateBuffer,
            int textureIndex, int renderPartMask, int packedLight, int packedOverlay,
            float r, float g, float b, float a, boolean isPreview) {

        if (mesh.nativeModelHandle == 0) return;

        Matrix4f projMat = RenderSystem.getProjectionMatrix();

        pose.pose().get(matrixTransferArray, 0);
        pose.normal().get(matrixTransferArray, 16);
        projMat.get(matrixTransferArray, 32);

        GeoModel.nComputeModelVertices(
                mesh.nativeModelHandle,
                vertexConsumer,
                matrixTransferArray,
                boneVertex,
                stateBuffer,
                renderPartMask,
                packedLight, packedOverlay,
                r, g, b, a
        );
    }
}
