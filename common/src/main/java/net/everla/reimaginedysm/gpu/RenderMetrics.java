package net.everla.reimaginedysm.gpu;

import java.util.LinkedHashMap;
import java.util.Map;

/** Opt in with -Drysm.renderMetrics=true. Render-thread counters, no work when disabled. */
public final class RenderMetrics {
    public static final boolean ENABLED=Boolean.getBoolean("rysm.renderMetrics");
    private static final Map<String,Long> counters=new LinkedHashMap<>();
    private static final Map<String,long[]> samples=new LinkedHashMap<>();
    private static final Map<String,Integer> cursors=new LinkedHashMap<>();
    private record Timer(int first,int last) {}
    private static final Map<Integer,Timer> active=new LinkedHashMap<>();
    private static final java.util.ArrayDeque<Timer> pending=new java.util.ArrayDeque<>();
    private RenderMetrics(){}
    public static int beginGpu(){
        if(!ENABLED||!org.lwjgl.opengl.GL.getCapabilities().OpenGL33)return 0;
        poll();if(pending.size()+active.size()>=8)return 0;
        int first=org.lwjgl.opengl.GL15.glGenQueries(),last=org.lwjgl.opengl.GL15.glGenQueries();active.put(first,new Timer(first,last));org.lwjgl.opengl.GL33.glQueryCounter(first,org.lwjgl.opengl.GL33.GL_TIMESTAMP);return first;
    }
    public static void endGpu(int first){if(first==0)return;var timer=active.remove(first);if(timer!=null){org.lwjgl.opengl.GL33.glQueryCounter(timer.last(),org.lwjgl.opengl.GL33.GL_TIMESTAMP);pending.add(timer);}}
    private static void poll(){while(!pending.isEmpty()){
        var timer=pending.peek();if(org.lwjgl.opengl.GL15.glGetQueryObjecti(timer.last(),org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE)==0)break;
        time("renderGpu",org.lwjgl.opengl.GL33.glGetQueryObjectui64(timer.last(),org.lwjgl.opengl.GL15.GL_QUERY_RESULT)-org.lwjgl.opengl.GL33.glGetQueryObjectui64(timer.first(),org.lwjgl.opengl.GL15.GL_QUERY_RESULT));
        org.lwjgl.opengl.GL15.glDeleteQueries(timer.first());org.lwjgl.opengl.GL15.glDeleteQueries(timer.last());pending.remove();
    }}
    public static void add(String name,long count){if(ENABLED)counters.merge(name,count,Long::sum);}
    public static void time(String name,long nanos){if(!ENABLED)return;var values=samples.computeIfAbsent(name,k->new long[256]);int cursor=cursors.getOrDefault(name,0);values[cursor&255]=nanos;cursors.put(name,cursor+1);}
    public static Map<String,Long> snapshot(){var out=new LinkedHashMap<>(counters);samples.forEach((name,values)->{int count=Math.min(256,cursors.get(name));long[] sorted=java.util.Arrays.copyOf(values,count);java.util.Arrays.sort(sorted);if(count>0){out.put(name+"MedianNs",sorted[count/2]);out.put(name+"P95Ns",sorted[Math.min(count-1,(int)Math.ceil(count*.95)-1)]);}});return Map.copyOf(out);}
    public static void clear(){if(ENABLED){for(var timer:pending){org.lwjgl.opengl.GL15.glDeleteQueries(timer.first());org.lwjgl.opengl.GL15.glDeleteQueries(timer.last());}for(var timer:active.values()){org.lwjgl.opengl.GL15.glDeleteQueries(timer.first());org.lwjgl.opengl.GL15.glDeleteQueries(timer.last());}}active.clear();pending.clear();counters.clear();samples.clear();cursors.clear();}
}
