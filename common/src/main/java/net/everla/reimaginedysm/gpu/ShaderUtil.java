package net.everla.reimaginedysm.gpu;

import org.lwjgl.opengl.*;
import com.mojang.blaze3d.platform.GlStateManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

public final class ShaderUtil {
    private ShaderUtil() {
    }

    public static String loadResource(String path) throws IOException {
        try (InputStream in = ShaderUtil.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("resource not found: " + path);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return r.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    public static int compileShader(int glType, String src, String name) {
        int sh = GL20.glCreateShader(glType);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(sh);
            GL20.glDeleteShader(sh);
            throw new RuntimeException("Compile failed (" + name + "): " + log);
        }
        return sh;
    }

    public static int compileShaderFromResource(int glType, String resourcePath) throws IOException {
        return compileShader(glType, loadResource(resourcePath), resourcePath);
    }

    public static int linkProgram(int... shaderIds) {
        return linkProgramWith(null, shaderIds);
    }

    public static int linkProgramWith(IntConsumer preLink, int... shaderIds) {
        int prog = GL20.glCreateProgram();
        for (int sh : shaderIds) GL20.glAttachShader(prog, sh);
        if (preLink != null) preLink.accept(prog);
        GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0) {
            String log = GL20.glGetProgramInfoLog(prog);
            GL20.glDeleteProgram(prog);
            for (int sh : shaderIds) GL20.glDeleteShader(sh);
            throw new RuntimeException("Link failed: " + log);
        }
        for (int sh : shaderIds) {
            GL20.glDetachShader(prog, sh);
            GL20.glDeleteShader(sh);
        }
        return prog;
    }
    public static void requireBuffer(int target,long bytes){
        int error=GL11.glGetError();long actual=GL32.glGetBufferParameteri64(target,GL15.GL_BUFFER_SIZE);
        if(error!=GL11.GL_NO_ERROR||actual!=bytes)throw new IllegalStateException("GPU buffer allocation failed: requested "+bytes+", allocated "+actual+", error 0x"+Integer.toHexString(error));
    }
    /** Raw RGBA arrays are tightly packed, independent of NativeImage's last upload. */
    public static final class SavedPixelUnpackState implements AutoCloseable {
        private static final int[] PARAMETERS={GL11.GL_UNPACK_ALIGNMENT,GL11.GL_UNPACK_ROW_LENGTH,
                GL11.GL_UNPACK_SKIP_PIXELS,GL11.GL_UNPACK_SKIP_ROWS,GL12.GL_UNPACK_IMAGE_HEIGHT,GL12.GL_UNPACK_SKIP_IMAGES};
        private final int[] previous=new int[PARAMETERS.length];
        private final int buffer=GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        public SavedPixelUnpackState() {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);
            for(int i=0;i<PARAMETERS.length;i++) {
                previous[i]=GL11.glGetInteger(PARAMETERS[i]);
                GL11.glPixelStorei(PARAMETERS[i],i==0?1:0);
            }
        }
        public void close(){for(int i=0;i<PARAMETERS.length;i++)GL11.glPixelStorei(PARAMETERS[i],previous[i]);GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,buffer);}
    }
    /** Preserve indexed ranges, generic SSBO binding and the caller's GL program. */
    public static class SavedComputeState implements AutoCloseable {
        private final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        private final int generic=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        private final int[] buffers;
        private final long[] starts,sizes;
        public SavedComputeState(int count) {
            buffers=new int[count];starts=new long[count];sizes=new long[count];
            for(int i=0;i<count;i++) {
                buffers[i]=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i);
                starts[i]=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,i);
                sizes[i]=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,i);
            }
        }
        @Override public void close() {
            GlStateManager._glUseProgram(program);
            for(int i=0;i<buffers.length;i++) {
                if(buffers[i]!=0 && sizes[i]>0) GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,i,buffers[i],starts[i],sizes[i]);
                else GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,buffers[i]);
            }
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,generic);
        }
    }
}
