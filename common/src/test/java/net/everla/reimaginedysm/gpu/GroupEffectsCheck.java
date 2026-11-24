package net.everla.reimaginedysm.gpu;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.util.List;

/** Parser/convex-clipping regression plus actual driver shader compilation. */
public final class GroupEffectsCheck {
    public static void main(String[] args) throws Exception {
        exampleModel();
        var cosmic=GroupStyle.parse("ysmCosmic#sc=00ffca,sa=96,ac=ff0000,aa=25@cape",GroupStyle.NONE);
        assert cosmic.cosmic() && cosmic.spaceColor()==0x00ffca && cosmic.spaceAlpha()==.96f;
        assert cosmic.astralColor()==0xff0000 && cosmic.astralAlpha()==.25f;
        var outline=GroupStyle.parse("ysmOutline#color=auto@edge",cosmic);
        assert outline.outline() && outline.cosmic() && outline.outlineColor()==-1;
        assert outline.outlineWidth()==2;
        var wide=GroupStyle.parse("ysmOutline#width=3.5,color=ff0000@wide",GroupStyle.NONE);
        assert wide.outlineWidth()==3.5f;
        assert GroupStyle.parse("ysmCosmic@nested",wide).outlineWidth()==3.5f;
        assert GroupStyle.parse("ysmOutline#width=0@off",wide).outlineWidth()==0;
        assert GroupStyle.parse("child",outline)==outline;
        assert GroupStyle.parse("ysmCosmic",GroupStyle.NONE).spaceAlpha()==0;
        for(String invalid:List.of("ysmCosmic#sa=101@a","ysmCosmic#sa=NaN@a","ysmCosmic#sc=xyzxyz@a",
                "ysmCosmic#sa=1,sa=2@a","ysmOutline#color=red@a","ysmOutline#unknown=1@a",
                "ysmOutline#width=-1@a","ysmOutline#width=NaN@a","ysmOutline#width=65@a",
                "ysmOutline#width=Infinity@a","ysmOutline#width=1,width=2@a")) {
            try {GroupStyle.parse(invalid,GroupStyle.NONE);throw new AssertionError(invalid);}
            catch(IllegalArgumentException expected) {}
        }
        var square=List.of(new float[]{0,0,0,0,0},new float[]{2,0,0,1,0},new float[]{2,2,0,1,1},new float[]{0,2,0,0,1});
        var box=List.of(new SurfaceUnion.Plane(-1,0,0,-1),new SurfaceUnion.Plane(1,0,0,3),
                new SurfaceUnion.Plane(0,-1,0,1),new SurfaceUnion.Plane(0,1,0,3),
                new SurfaceUnion.Plane(0,0,-1,1),new SurfaceUnion.Plane(0,0,1,1));
        var pieces=SurfaceUnion.subtract(square,box);
        assert pieces.size()==1 && pieces.get(0).size()==4;
        double area=0;
        var poly=pieces.get(0);
        for(int i=0;i<poly.size();i++) {
            var a=poly.get(i);var b=poly.get((i+1)%poly.size());area+=a[0]*b[1]-b[0]*a[1];
            assert Math.abs(a[3]-a[0]/2)<1e-6 && Math.abs(a[4]-a[1]/2)<1e-6:"UV interpolation";
        }
        assert Math.abs(Math.abs(area)/2-2)<1e-6:"partial intersection area";
        assert SurfaceUnion.subtract(square,List.of(new SurfaceUnion.Plane(1,0,0,3),new SurfaceUnion.Plane(-1,0,0,1))).isEmpty();
        if(!GLFW.glfwInit()) throw new AssertionError("GLFW unavailable");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window=GLFW.glfwCreateWindow(64,64,"Group effects verification",0,0);
        if(window==0) throw new AssertionError("OpenGL 3.2 unavailable");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            for(String file:List.of("/group_surface.fsh","/group_cosmic.fsh")) {
                int p=ShaderUtil.linkProgram(ShaderUtil.compileShaderFromResource(GL20.GL_VERTEX_SHADER,"/group_effect.vsh"),
                        ShaderUtil.compileShaderFromResource(GL20.GL_FRAGMENT_SHADER,file));
                if(file.equals("/group_surface.fsh")) outlineOcclusion(p);
                GL20.glDeleteProgram(p);
            }
            System.out.println("Group effects: parser, inheritance, intersection/UV, outline occlusion pixels and real GL shader links passed on "+GL11.glGetString(GL11.GL_RENDERER));
        } finally {GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }

    private static void exampleModel() throws Exception {
        String example=System.getProperty("ysm.groupExample");if(example==null) return;
        var path=java.nio.file.Path.of(example);
        try(var reader=new com.elfmcys.yesstevemodel.resource.YSMFolderDeserializer(path)) {
            var raw=reader.deserialize();var geometry=raw.mainEntity.mainModel;
            var image=javax.imageio.ImageIO.read(path.resolve("default.png").toFile());
            var scanner=new com.elfmcys.yesstevemodel.resource.YSMClientMapper.TranslucencyScanner(new java.awt.image.BufferedImage[]{image},1);
            var method=com.elfmcys.yesstevemodel.resource.YSMClientMapper.class.getDeclaredMethod("buildMesh",
                    com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel.RawGeometry.class,
                    com.elfmcys.yesstevemodel.resource.models.GeometryDescription.class,int.class,
                    com.elfmcys.yesstevemodel.resource.YSMClientMapper.TranslucencyScanner.class,boolean.class);
            method.setAccessible(true);
            var model=(com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel)method.invoke(null,geometry,
                    com.elfmcys.yesstevemodel.resource.YSMClientMapper.buildContext(geometry),1,scanner,false);
            assert model.hasGroupStyles && model.isTranslucentTexture(0);
            var union=model.bakedBones.stream().filter(b->b.name.equals("TranslucentUnion")).findFirst().orElseThrow();
            assert union.cubes.size()==3 && union.cubes.stream().allMatch(c->c.closedVolume):"translucency must not make a closed solid a cutout";
            assert union.cubes.stream().flatMap(c->c.quads.stream()).allMatch(q->q.isTranslucent);
            assert union.cubes.stream().allMatch(c->c.cullable):"closed transparent solids must cull back faces";
            assert model.bakedBones.stream().filter(b->b.style.cosmic()).count()==2;
            assert model.bakedBones.stream().filter(b->b.name.startsWith("ysmTail")).count()==4;
            assert model.ysmPhysics!=null && model.ysmPhysics.joints.size()==4 && model.ysmPhysics.ends.length==1:"tail topology";
            var item=model.bakedBones.stream().filter(b->b.name.startsWith("ysmItem@")).findFirst().orElseThrow();
            assert item.cubes.size()==1 && item.cubes.get(0).quads.size()>6:"item must have generated edges and hole";
            var panel=model.bakedBones.stream().filter(b->b.name.contains("BluePanelA")).findFirst().orElseThrow();
            assert panel.averageColors!=null && (panel.averageColors[0]&255)>((panel.averageColors[0]>>16)&255):"auto blue tint";
            int outline=panel.averageColors[0];
            assert (outline&255)<64 && ((outline>>16)&255)>0:"auto outline should be darker and desaturated";
            var output=java.nio.file.Path.of("build/group-effects-demo-preview.png");
            java.nio.file.Files.createDirectories(output.getParent());
            java.nio.file.Files.write(output,com.elfmcys.yesstevemodel.model.ModelThumbnailRenderer.render(raw));
            System.out.println("Example model loaded: "+model.bakedBones.size()+" bones; cosmic/outline/item/tail/translucency baked successfully");
        }
    }

    private static void outlineOcclusion(int p) {
        GL20.glUseProgram(p);
        int vao=GL30.glGenVertexArrays(),vbo=GL15.glGenBuffers(),tex=GL11.glGenTextures();
        GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
        for(int i=0;i<4;i++) GL20.glEnableVertexAttribArray(i);
        GL20.glVertexAttribPointer(GL20.glGetAttribLocation(p,"Position"),3,GL11.GL_FLOAT,false,48,0);
        GL20.glVertexAttribPointer(GL20.glGetAttribLocation(p,"UV0"),2,GL11.GL_FLOAT,false,48,12);
        GL20.glVertexAttribPointer(GL20.glGetAttribLocation(p,"Color"),4,GL11.GL_FLOAT,false,48,20);
        GL20.glVertexAttribPointer(GL20.glGetAttribLocation(p,"Normal"),3,GL11.GL_FLOAT,false,48,36);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,new int[]{-1});
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL20.glUniform1i(GL20.glGetUniformLocation(p,"texture0"),0);
        GL20.glUniform1i(GL20.glGetUniformLocation(p,"lightmap"),0);
        GL20.glUniform1i(GL20.glGetUniformLocation(p,"overlayMap"),0);
        GL20.glUniform3f(GL20.glGetUniformLocation(p,"light0"),0,0,1);
        GL20.glUniform3f(GL20.glGetUniformLocation(p,"light1"),0,0,1);
        GL20.glUniform1f(GL20.glGetUniformLocation(p,"fogStart"),100);
        GL20.glUniform1f(GL20.glGetUniformLocation(p,"fogEnd"),200);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(p,"Projection"),false,new org.joml.Matrix4f().get(new float[16]));
        GL11.glViewport(0,0,64,64);GL11.glClearColor(1,1,1,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glColorMask(false,false,false,false);
        rect(-.75f,.2f,-.6f,.6f,.4f,0,0,1);rect(0,.8f,-.6f,.6f,.1f,0,1,1);
        GL11.glColorMask(true,true,true,true);GL11.glDepthMask(false);
        GL20.glUniform1i(GL20.glGetUniformLocation(p,"mode"),1);
        GL20.glUniform4f(GL20.glGetUniformLocation(p,"tint"),0,0,0,1);
        for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) if(x!=0||y!=0) {
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(p,"Projection"),false,
                    new org.joml.Matrix4f().translation(x*4f/64,y*4f/64,.00001f).get(new float[16]));
            rect(-.75f,.2f,-.6f,.6f,.4f,0,0,1);
        }
        GL20.glUniform1i(GL20.glGetUniformLocation(p,"mode"),0);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(p,"Projection"),false,new org.joml.Matrix4f().get(new float[16]));
        rect(-.75f,.2f,-.6f,.6f,.4f,0,0,1);rect(0,.8f,-.6f,.6f,.1f,0,1,1);
        var data=org.lwjgl.system.MemoryUtil.memAlloc(64*64*4);
        try {
            GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,data);
            assert rgb(data,7,32)==0:"left outline missing";
            assert rgb(data,20,32)==0x0000ff:"A surface";
            assert rgb(data,39,32)==0x00ffff:"B must hide A's right outline";
            assert rgb(data,60,32)==0xffffff:"outline leaked outside group";
            assert GL11.glGetError()==GL11.GL_NO_ERROR:"GL state error";
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(data);GL11.glDepthMask(true);
            GL11.glDeleteTextures(tex);GL15.glDeleteBuffers(vbo);GL30.glDeleteVertexArrays(vao);
        }
    }
    private static int rgb(java.nio.ByteBuffer data,int x,int y) {
        int offset=(y*64+x)*4;
        return ((data.get(offset)&255)<<16)|((data.get(offset+1)&255)<<8)|(data.get(offset+2)&255);
    }
    private static void rect(float left,float right,float bottom,float top,float z,float r,float g,float b) {
        float[] positions={left,bottom,right,bottom,right,top,left,bottom,right,top,left,top};
        float[] vertices=new float[72];
        for(int i=0;i<6;i++) {
            int k=i*12;vertices[k]=positions[i*2];vertices[k+1]=positions[i*2+1];vertices[k+2]=z;
            vertices[k+3]=.5f;vertices[k+4]=.5f;vertices[k+5]=r;vertices[k+6]=g;vertices[k+7]=b;
            vertices[k+8]=1;vertices[k+11]=1;
        }
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,vertices,GL15.GL_STREAM_DRAW);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);
    }
}
