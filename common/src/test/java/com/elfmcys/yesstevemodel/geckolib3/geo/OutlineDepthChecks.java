package com.elfmcys.yesstevemodel.geckolib3.geo;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;

/** Offscreen checks for the actual depth state shared by the outline paths. */
public final class OutlineDepthChecks {
    public static void run() {
        int fbo=GL30.glGenFramebuffers(),color=GL30.glGenRenderbuffers(),depth=GL30.glGenRenderbuffers();
        int vao=GL30.glGenVertexArrays(),program=GL20.glCreateProgram();
        int vertex=shader(GL20.GL_VERTEX_SHADER,"""
                #version 150
                uniform float z;
                void main() {
                    vec2 p=gl_VertexID==0?vec2(-1,-1):gl_VertexID==1?vec2(3,-1):vec2(-1,3);
                    gl_Position=vec4(p,z,1);
                }
                """);
        int fragment=shader(GL20.GL_FRAGMENT_SHADER,"""
                #version 150
                uniform vec3 tint;
                out vec4 fragColor;
                void main() {fragColor=vec4(tint,1);}
                """);
        try {
            GL20.glAttachShader(program,vertex);GL20.glAttachShader(program,fragment);GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new AssertionError(GL20.glGetProgramInfoLog(program));
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,color);GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER,GL11.GL_RGBA8,8,8);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL30.GL_RENDERBUFFER,color);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,depth);GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER,GL14.GL_DEPTH_COMPONENT24,8,8);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_RENDERBUFFER,depth);
            if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("Outline framebuffer");
            GL11.glViewport(0,0,8,8);RenderSystem.disableBlend();RenderSystem.disableCull();RenderSystem.colorMask(true,true,true,true);
            GL30.glBindVertexArray(vao);GL20.glUseProgram(program);
            for(boolean nearFirst:new boolean[]{true,false}) {
                clear();
                GroupEffectRenderer.outlineDepth(true);draw(program,nearFirst?-.4f:.4f,nearFirst?1:0,0,nearFirst?0:1);
                GroupEffectRenderer.outlineDepth(true);draw(program,nearFirst?.4f:-.4f,nearFirst?0:1,0,nearFirst?1:0);
                expect(1,0,0,"Far outline covered near outline; order="+nearFirst);
                draw(program,.6f,0,1,0);expect(1,0,0,"Later geometry covered near outline");
            }
            clear();draw(program,-.8f,0,1,0);
            // Simulate incoming state from a pass that disabled depth testing.
            RenderSystem.disableDepthTest();GroupEffectRenderer.outlineDepth(true);draw(program,-.4f,1,0,0);
            expect(0,1,0,"Outline passed through foreground occluder");
            clear();GroupEffectRenderer.outlineDepth(false);draw(program,-.4f,1,0,0);
            if(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK))throw new AssertionError("Transparent outline writes depth");
            GroupEffectRenderer.outlineDepth(true);draw(program,.4f,0,0,1);expect(0,0,1,"Transparent outline claimed opaque depth");
            System.out.println("Outline depth ordering, foreground occlusion and transparent depth policy passed");
        } finally {
            GL20.glUseProgram(0);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glBindVertexArray(0);
            GL20.glDeleteProgram(program);GL20.glDeleteShader(vertex);GL20.glDeleteShader(fragment);
            GL30.glDeleteVertexArrays(vao);GL30.glDeleteRenderbuffers(color);GL30.glDeleteRenderbuffers(depth);GL30.glDeleteFramebuffers(fbo);
        }
    }
    private static int shader(int type,String source) {
        int shader=GL20.glCreateShader(type);GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);
        if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new AssertionError(GL20.glGetShaderInfoLog(shader));
        return shader;
    }
    private static void clear() {
        RenderSystem.depthMask(true);GL11.glClearDepth(1);GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
    }
    private static void draw(int program,float z,float r,float g,float b) {
        GL20.glUniform1f(GL20.glGetUniformLocation(program,"z"),z);GL20.glUniform3f(GL20.glGetUniformLocation(program,"tint"),r,g,b);
        GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
    }
    private static void expect(float r,float g,float b,String message) {
        float[] pixel=new float[4];GL11.glReadPixels(4,4,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,pixel);
        if(Math.abs(pixel[0]-r)>.01 || Math.abs(pixel[1]-g)>.01 || Math.abs(pixel[2]-b)>.01)throw new AssertionError(message);
    }
}
