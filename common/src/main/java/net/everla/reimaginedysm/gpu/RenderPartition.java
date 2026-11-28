package net.everla.reimaginedysm.gpu;

import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoBone;
import java.util.*;

/** Face-disjoint base draws plus an independent outline overlay. Full skeleton indices are retained. */
public final class RenderPartition implements AutoCloseable {
    public record Slices(GeoModel ordinary,GeoModel effects,GeoModel outlines,int ordinaryFaces,int effectFaces,int outlineFaces,List<GeoModel> effectsInOrder,List<GeoModel> outlinesInOrder) {}
    private final GeoModel source;
    private record TextureKey(int texture,boolean combinePlain) {}
    private final Map<TextureKey,Slices> textures=new HashMap<>();
    public RenderPartition(GeoModel source){this.source=source;}
    public Slices texture(int texture){return texture(texture,false);}
    public Slices texture(int texture,boolean combinePlain){return textures.computeIfAbsent(new TextureKey(texture,combinePlain),key->build(key.texture(),key.combinePlain()));}
    public static boolean special(GeoModel.BakedBone bone,GeoModel.BakedQuad face,int texture){return bone.style.cosmic() || face.translucent(texture);}
    private Slices build(int texture,boolean combinePlain) {
        GeoModel ordinary=view(texture,0),effects=view(texture,1),outlines=view(texture,2);
        return new Slices(ordinary,effects,outlines,faces(ordinary),faces(effects),faces(outlines),ranges(texture,1,effects,combinePlain),ranges(texture,2,outlines,false));
    }
    private static int faces(GeoModel model){return model.bakedBones.stream().mapToInt(b->b.cubes.stream().mapToInt(c->c.quads.size()).sum()).sum();}
    private List<GeoModel> ranges(int texture,int kind,GeoModel whole,boolean combinePlain) {
        var dynamicGroups=new HashSet<Integer>();
        for(var bone:source.bakedBones)if(bone.dynamicGeometry()&&!bone.cubes.isEmpty())dynamicGroups.add(bone.surfaceGroup);
        var spans=new ArrayList<int[]>();int first=-1,last=-1,group=-1;
        for(int i=0;i<whole.bakedBones.size();i++) {
            var bone=whole.bakedBones.get(i);if(bone.cubes.isEmpty())continue;
            // Ordinary alpha surfaces keep their own clipping group IDs in the mesh,
            // but can share a draw view. Keep shader material atlases in small ranges.
            int next=kind==1?(combinePlain && !dynamicGroups.contains(bone.surfaceGroup)
                    && !bone.style.cosmic() && !bone.style.outline()?-1:bone.surfaceGroup):bone.outlineGroup;
            if(first>=0&&next!=group){spans.add(new int[]{first,last+1});first=-1;}
            if(first<0){first=i;group=next;}last=i;
        }
        if(first>=0)spans.add(new int[]{first,last+1});
        if(spans.size()<=1)return faces(whole)==0?List.of():List.of(whole);
        return spans.stream().map(span->view(texture,kind,span[0],span[1])).toList();
    }
    private GeoModel view(int texture,int kind){return view(texture,kind,0,source.bakedBones.size());}
    private GeoModel view(int texture,int kind,int first,int end) {
        String[][] names=new String[35][];Arrays.setAll(names,i->new String[0]);
        GeoModel view=new GeoModel(source.bones.toArray(GeoBone[]::new),names,new boolean[3],source.getProperties(),new boolean[Math.max(1,texture+1)]);
        view.renderSource=source;view.overlayOnly=kind==2;view.scripts=source.scripts;view.ysmPhysics=source.ysmPhysics;
        var bones=new ArrayList<GeoModel.BakedBone>();boolean translucent=false;
        for(int index=0;index<source.bakedBones.size();index++) {
            var original=source.bakedBones.get(index);
            var bone=new GeoModel.BakedBone();bone.name=original.name;bone.parentIdx=original.parentIdx;bone.partMask=original.partMask;
            bone.pivotX=original.pivotX;bone.pivotY=original.pivotY;bone.pivotZ=original.pivotZ;bone.rotX=original.rotX;bone.rotY=original.rotY;bone.rotZ=original.rotZ;
            bone.glow=original.glow;bone.facingCamera=original.facingCamera;bone.facingCameraSubtree=original.facingCameraSubtree;bone.frameAnimation=original.frameAnimation;bone.frameHeight=original.frameHeight;
            bone.surfaceGroup=original.surfaceGroup;bone.outlineGroup=original.outlineGroup;bone.averageColors=original.averageColors;bone.cosmicEffect=original.cosmicEffect;
            var style=original.style;
            bone.style=kind==0?GroupStyle.NONE:new GroupStyle(kind==1&&style.cosmic(),style.spaceColor(),style.spaceAlpha(),style.astralColor(),style.astralAlpha(),kind==2&&style.outline(),style.outlineColor(),style.outlineWidth());
            if(index>=first&&index<end)for(var originalCube:original.cubes) {
                var cube=new GeoModel.BakedCube();cube.cullable=originalCube.cullable;cube.closedVolume=originalCube.closedVolume;cube.sourceCube=originalCube;
                for(var face:originalCube.quads)if(kind==2?style.outline()&&style.outlineWidth()>0:(kind==1)==special(original,face,texture)) {
                    cube.quads.add(face);translucent|=face.translucent(texture);
                }
                if(!cube.quads.isEmpty())bone.cubes.add(cube);
            }
            bones.add(bone);
        }
        view.bakedBones=List.copyOf(bones);view.hasGroupStyles=kind!=0;
        view.hasFrameAnimations=bones.stream().anyMatch(b->!b.cubes.isEmpty()&&b.frameAnimation!=null);
        view.hasFacingCameraBones=bones.stream().anyMatch(b->!b.cubes.isEmpty()&&b.facingCameraSubtree);
        view.setTranslucentTexture(texture,translucent);return view;
    }
    public void close(){
        Set<GeoModel> views=Collections.newSetFromMap(new IdentityHashMap<>());
        for(var slices:textures.values()){views.addAll(List.of(slices.ordinary(),slices.effects(),slices.outlines()));views.addAll(slices.effectsInOrder());views.addAll(slices.outlinesInOrder());}
        views.forEach(GeoModel::freeNativeCache);textures.clear();
    }
}
