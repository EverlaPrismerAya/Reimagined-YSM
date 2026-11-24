package com.elfmcys.yesstevemodel.resource;

import com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel.RawFace;
import org.joml.Vector3f;
import java.awt.image.BufferedImage;

public final class ItemThicknessCheck {
    public static void main(String[] args) throws Exception {
        var face=new RawFace();face.normal=new float[]{0,0,1};
        face.positions=new float[][]{{0,0,0},{1,0,0},{1,1,0},{0,1,0}};
        face.u=new float[]{0,1,1,0};face.v=new float[]{0,0,1,1};
        var image=new BufferedImage(3,3,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<3;y++) for(int x=0;x<3;x++) image.setRGB(x,y,0xffffffff);
        var full=ItemThickness.bake(face,new YSMClientMapper.TranslucencyScanner(new BufferedImage[]{image},1),3,3);
        assert full.quads.size()==14 : "expected front/back and 12 pixel boundary sides";
        image.setRGB(1,1,0);
        var hole=ItemThickness.bake(face,new YSMClientMapper.TranslucencyScanner(new BufferedImage[]{image},1),3,3);
        assert hole.quads.size()==18 : "transparent interior contour missing";
        for(var quad:hole.quads) {
            var a=new Vector3f(quad.positions[0],quad.positions[1],quad.positions[2]);
            var b=new Vector3f(quad.positions[3],quad.positions[4],quad.positions[5]);
            var c=new Vector3f(quad.positions[6],quad.positions[7],quad.positions[8]);
            assert b.sub(a).cross(c.sub(a)).dot(new Vector3f(quad.normal))>0 : "inward face winding";
            for(int i=2;i<12;i+=3) assert Math.abs(Math.abs(quad.positions[i])-1/32f)<1e-6 : "wrong item thickness";
        }
        for(int y=0;y<3;y++) for(int x=0;x<3;x++) image.setRGB(x,y,0);
        assert ItemThickness.bake(face,new YSMClientMapper.TranslucencyScanner(new BufferedImage[]{image},1),3,3).quads.isEmpty();
        // Rotated UVs and rotated geometry must map the contour onto the same plane.
        image.setRGB(0,0,0xffffffff);
        face.u=new float[]{1,1,0,0};face.v=new float[]{0,1,1,0};
        var rotation=new org.joml.Quaternionf().rotationXYZ(.3f,.8f,-.4f);
        var n=new Vector3f(0,0,1).rotate(rotation);face.normal=new float[]{n.x,n.y,n.z};
        for(int i=0;i<4;i++) {var p=new Vector3f(face.positions[i]).rotate(rotation);face.positions[i]=new float[]{p.x,p.y,p.z};}
        var rotated=ItemThickness.bake(face,new YSMClientMapper.TranslucencyScanner(new BufferedImage[]{image},1),3,3);
        assert rotated.quads.size()==6 : "single visible pixel contour";
        var raw=new com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel.RawGeometry();
        raw.textureWidth=raw.textureHeight=3;
        var bone=new com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel.RawBone();bone.name="ysmItem@check";
        var cube=new com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel.RawCube();cube.faces.add(face);bone.cubes.add(cube);raw.bones.add(bone);
        var missing=new RawFace();java.util.Arrays.fill(missing.u,-1);java.util.Arrays.fill(missing.v,-1);cube.faces.add(missing);
        var method=YSMClientMapper.class.getDeclaredMethod("buildMesh",raw.getClass(),com.elfmcys.yesstevemodel.resource.models.GeometryDescription.class,int.class,YSMClientMapper.TranslucencyScanner.class,boolean.class);
        method.setAccessible(true);
        var context=new com.elfmcys.yesstevemodel.resource.models.GeometryDescription("item_check",3,3,4,4,new double[3]);
        var mesh=(com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel)method.invoke(null,raw,context,1,new YSMClientMapper.TranslucencyScanner(new BufferedImage[]{image},1),false);
        assert mesh.bakedBones.get(0).cubes.get(0).quads.size()==6 : "item prefix was not baked through production mapper";
        assert mesh.ysmPhysics==null;
        System.out.println("PASS: item thickness, front/back winding, opaque edges, transparent holes and fully transparent texture");
    }
}
