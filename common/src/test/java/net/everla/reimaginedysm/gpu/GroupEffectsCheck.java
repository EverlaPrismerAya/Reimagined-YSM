package net.everla.reimaginedysm.gpu;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.util.List;

/** Parser/convex-clipping regression plus actual driver shader compilation. */
public final class GroupEffectsCheck {
    private static com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel fixture;
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
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            for(String file:List.of("/group_surface.fsh","/group_cosmic.fsh")) {
                int p=ShaderUtil.linkProgram(ShaderUtil.compileShaderFromResource(GL20.GL_VERTEX_SHADER,"/group_effect.vsh"),
                        ShaderUtil.compileShaderFromResource(GL20.GL_FRAGMENT_SHADER,file));
                if(file.equals("/group_surface.fsh")) outlineOcclusion(p);
                GL20.glDeleteProgram(p);
            }
            batchedUpload();
            materialAtlas();
            System.out.println("Group effects: batched upload/range pixels, parser, inheritance, intersection/UV, outline occlusion pixels and real GL shader links passed on "+GL11.glGetString(GL11.GL_RENDERER));
        } finally {GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }

    /** Exercise production upload and range drawing, including sorted sub-batches. */
    private static void batchedUpload() throws Exception {
        var renderer=Class.forName("com.elfmcys.yesstevemodel.geckolib3.geo.GroupEffectRenderer");
        var batchType=Class.forName(renderer.getName()+"$Batch");
        var ctor=batchType.getDeclaredConstructor(int.class,List.class,int.class);ctor.setAccessible(true);
        var left=List.of(new float[]{-.9f,-.8f,0,0,0,0,0,1},new float[]{-.1f,-.8f,0,0,0,0,0,1},new float[]{-.5f,.8f,0,0,0,0,0,1});
        var right=List.of(new float[]{.1f,-.8f,0,0,0,0,0,1},new float[]{.9f,-.8f,0,0,0,0,0,1},new float[]{.5f,.8f,0,0,0,0,0,1});
        var upload=renderer.getDeclaredMethod("upload",List.class,float.class,float.class,float.class,float.class);upload.setAccessible(true);
        var emit=java.util.Arrays.stream(renderer.getDeclaredMethods()).filter(m->m.getName().equals("emit")).findFirst().orElseThrow();emit.setAccessible(true);
        var programField=renderer.getDeclaredField("currentProgram");programField.setAccessible(true);programField.setInt(null,-1);
        int p=ShaderUtil.linkProgramWith(id->{GL20.glBindAttribLocation(id,0,"Position");GL20.glBindAttribLocation(id,2,"Color");},
                ShaderUtil.compileShader(GL20.GL_VERTEX_SHADER,"#version 150\nin vec3 Position; in vec4 Color; out vec4 c; void main(){gl_Position=vec4(Position,1);c=Color;}","batch vertex"),
                ShaderUtil.compileShader(GL20.GL_FRAGMENT_SHADER,"#version 150\nin vec4 c; out vec4 color; void main(){color=c;}","batch fragment"));
        int vao=GL30.glGenVertexArrays(),vbo=GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
        GL20.glEnableVertexAttribArray(0);GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,48,0L);
        GL20.glEnableVertexAttribArray(2);GL20.glVertexAttribPointer(2,4,GL11.GL_FLOAT,false,48,20L);
        var data=org.lwjgl.system.MemoryUtil.memAlloc(64*64*4);
        try {
            upload.invoke(null,List.of(ctor.newInstance(0,left,0),ctor.newInstance(0,right,3)),0f,1f,0f,1f);
            assert GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER,GL15.GL_BUFFER_SIZE)==6*48;
            GL20.glUseProgram(p);GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glClearColor(0,0,0,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            // The second range must not accidentally redraw the first batch.
            emit.invoke(null,ctor.newInstance(0,right,3),1f,1f,1f,1f,0,null);
            GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,data);
            assert rgb(data,16,28)==0 : "range draw leaked into first batch";
            assert rgb(data,48,28)==0x00ff00 : "second batch offset/color lost";
            assert GL11.glGetError()==GL11.GL_NO_ERROR : "batched upload GL error";
        } finally {
            programField.setInt(null,0);org.lwjgl.system.MemoryUtil.memFree(data);
            GL20.glUseProgram(0);GL15.glDeleteBuffers(vbo);GL30.glDeleteVertexArrays(vao);GL20.glDeleteProgram(p);
        }
    }

    /** Production GPU albedo bake + production entity stream, with a pack-owned MRT encoder. */
    private static void materialAtlas() throws Exception {
        var renderer=Class.forName("com.elfmcys.yesstevemodel.geckolib3.geo.GroupEffectRenderer");
        var batchType=Class.forName(renderer.getName()+"$Batch");
        var ctor=batchType.getDeclaredConstructor(int.class,List.class,int.class);ctor.setAccessible(true);
        var vertices=List.of(new float[]{-.8f,-.8f,0,.5f,.5f,0,0,1},
                new float[]{.8f,-.8f,0,.5f,.5f,0,0,1},new float[]{-.8f,.8f,0,.5f,.5f,0,0,1});
        int bone=0;while(fixture.bakedBones.get(bone).glow || fixture.bakedBones.get(bone).style.cosmic()) bone++;
        var batches=List.of(ctor.newInstance(bone,vertices,0));
        var ensure=renderer.getDeclaredMethod("ensureMaterialResources",int.class);ensure.setAccessible(true);ensure.invoke(null,256);
        var field=renderer.getDeclaredField("material");field.setAccessible(true);int bake=field.getInt(null);
        // Both ordinary and cosmic material programs must link with the atlas vertex stage.
        field=renderer.getDeclaredField("materialCosmic");field.setAccessible(true);assert GL20.glIsProgram(field.getInt(null));
        field=renderer.getDeclaredField("atlas");field.setAccessible(true);int atlas=field.getInt(null);
        int source=GL11.glGenTextures(),vao=GL30.glGenVertexArrays(),vbo=GL15.glGenBuffers();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,source);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,new int[]{0xff4080c0});
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
        var upload=renderer.getDeclaredMethod("upload",List.class,float.class,float.class,float.class,float.class);upload.setAccessible(true);
        upload.invoke(null,batches,1f,1f,1f,1f);
        GL20.glEnableVertexAttribArray(0);GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,48,0);
        GL20.glEnableVertexAttribArray(1);GL20.glVertexAttribPointer(1,2,GL11.GL_FLOAT,false,48,12);
        GL20.glUseProgram(bake);GL11.glViewport(0,0,256,256);
        GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glColorMask(true,true,true,true);GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,0});
        GL20.glUniform1i(GL20.glGetUniformLocation(bake,"texture0"),0);
        GL20.glUniform1i(GL20.glGetUniformLocation(bake,"atlasColumns"),2);
        GL20.glUniform1i(GL20.glGetUniformLocation(bake,"atlasTileSize"),128);
        GL20.glUniform1i(GL20.glGetUniformLocation(bake,"atlasTriangles"),1);
        GL20.glUniform1i(GL20.glGetUniformLocation(bake,"alphaMode"),1);
        GL20.glUniform1i(GL20.glGetUniformLocation(bake,"atlasBand"),0);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
        GL20.glUniform1i(GL20.glGetUniformLocation(bake,"atlasBand"),2);
        GL20.glUniform1i(GL20.glGetUniformLocation(bake,"maskOnly"),1);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
        var entityUpload=renderer.getDeclaredMethod("uploadEntityMaterials",fixture.getClass(),List.class,int.class,int.class,int.class,int.class,int.class);
        entityUpload.setAccessible(true);
        // Material effects must not silently opt a bone into fullbright lighting.
        int ambient=0x00300020;
        var boneData=org.lwjgl.system.MemoryUtil.memAlloc(3*3*36);
        try {
            for(int index=0;index<fixture.bakedBones.size();index++) {
                var selected=fixture.bakedBones.get(index);
                boolean original=selected.glow;
                for(boolean glowing:new boolean[]{false,true}) {
                    selected.glow=glowing;
                    entityUpload.invoke(null,fixture,List.of(ctor.newInstance(index,vertices,0)),3,1,2,ambient,655360);
                    GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER,0,boneData);
                    for(int v=0;v<9;v++) assert boneData.getInt(v*36+28)==(glowing?15728880:ambient)
                            : "Unexpected fullbright on "+selected.name;
                }
                selected.glow=original;
            }
        } finally {org.lwjgl.system.MemoryUtil.memFree(boneData);}
        entityUpload.invoke(null,fixture,batches,3,1,2,ambient,655360);
        GL20.glVertexAttrib4f(1,1,1,1,1);
        int pack=ShaderUtil.linkProgramWith(id->{GL20.glBindAttribLocation(id,0,"Position");GL20.glBindAttribLocation(id,2,"UV0");GL20.glBindAttribLocation(id,5,"Normal");GL30.glBindFragDataLocation(id,0,"albedo");GL30.glBindFragDataLocation(id,1,"material");},
                ShaderUtil.compileShader(GL20.GL_VERTEX_SHADER,"#version 150\nin vec3 Position;in vec2 UV0;in vec3 Normal;out vec2 uv;out vec3 n;void main(){gl_Position=vec4(Position,1);uv=UV0;n=Normal;}","entity material fixture"),
                ShaderUtil.compileShader(GL20.GL_FRAGMENT_SHADER,"#version 150\nuniform sampler2D tex;in vec2 uv;in vec3 n;out vec4 albedo;out vec4 material;void main(){albedo=texture(tex,uv);if(albedo.a<.1)discard;material=vec4(normalize(n)*.5+.5,1);}","pack-owned material encoder"));
        // Explicit output routes: ordinary RGBA goes through this program, never straight to MRT.
        int target=GL30.glGenFramebuffers();int[] outputs={GL11.glGenTextures(),GL11.glGenTextures()};
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,target);
        for(int i=0;i<2;i++) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,outputs[i]);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,64,64,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(java.nio.ByteBuffer)null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0+i,GL11.GL_TEXTURE_2D,outputs[i],0);
        }
        GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT1,GL30.GL_COLOR_ATTACHMENT0});
        GL11.glViewport(0,0,64,64);GL20.glUseProgram(pack);GL11.glBindTexture(GL11.GL_TEXTURE_2D,atlas);
        var pixel=org.lwjgl.system.MemoryUtil.memAlloc(4);
        try {
            GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT1);GL11.glReadPixels(16,16,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixel);
            assert rgb(pixel,0,0)==0xc08040 : "material bake changed albedo or triangle UV interpolation";
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glReadPixels(16,16,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixel);
            assert (rgb(pixel,0,0)&255)==255 && Math.abs((rgb(pixel,0,0)>>16)-128)<=1 : "pack did not receive valid normals";
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT1);GL11.glDrawArrays(GL11.GL_TRIANGLES,6,3);
            GL11.glReadPixels(16,16,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixel);
            assert rgb(pixel,0,0)==0xffffff : "outline mask inherited source RGB";
            assert GL11.glGetError()==GL11.GL_NO_ERROR : "material atlas GL error";
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(pixel);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);
            GL20.glUseProgram(0);GL20.glDeleteProgram(pack);GL30.glDeleteFramebuffers(target);
            for(int texture:outputs) GL11.glDeleteTextures(texture);
            GL11.glDeleteTextures(source);GL30.glDeleteVertexArrays(vao);GL15.glDeleteBuffers(vbo);
            renderer.getMethod("shutdown").invoke(null);
        }
        System.out.println("PASS: GPU material atlas, exact albedo, entity normals, remapped pack-owned MRT outputs and white outline mask");
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
            fixture=model;
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
