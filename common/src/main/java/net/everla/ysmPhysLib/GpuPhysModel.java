package net.everla.ysmPhysLib;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;

/** Immutable, parent-first GPU topology. Shared by all instances of a model. */
final class GpuPhysModel implements AutoCloseable {
    final PhysDefinition definition;
    final int[] original, sorted, island;
    final int[] uploadOrder;
    final boolean[] affected;
    final int trees;
    final int buffer;
    final int schedule;
    int users;

    GpuPhysModel(PhysDefinition definition, boolean[] glowing) {
        this(definition,glowing,definition.cpuCollisionIslands());
    }

    GpuPhysModel(PhysDefinition definition,boolean[] glowing,boolean[] cpu) {
        this.definition=definition;
        original=definition.order.clone();sorted=new int[original.length];affected=definition.affected;
        for(int i=0;i<original.length;i++) sorted[original[i]]=i;
        int[] first=new int[original.length], next=new int[original.length];
        java.util.Arrays.fill(first,-1);java.util.Arrays.fill(next,-1);
        for(var joint:definition.joints) {
            int previous=-1;
            for(int end:joint.outputs()) {
                if(previous<0) first[joint.bone()]=sorted[end];else next[previous]=sorted[end];
                previous=end;
            }
        }
        boolean[] ends=new boolean[original.length];for(int end:definition.ends) ends[end]=true;
        island=definition.islands();
        int treeCount=0;
        int[] remap=new int[original.length];java.util.Arrays.fill(remap,-1);
        for(int i:original) {
            if(cpu[i]) island[i]=-1;
            else if(island[i]>=0) {
                int group=island[i];
                if(remap[group]<0) remap[group]=treeCount++;
                island[i]=remap[group];
            }
        }
        trees=Math.max(1,treeCount);
        var traversal=new java.util.ArrayList<Integer>();
        for(int i=0;i<trees*4;i++) traversal.add(0);
        for(int tree=0;tree<trees;tree++) {
            boolean[] needed=new boolean[original.length],simulation=new boolean[original.length];
            for(int i:original) if(island[i]==tree || !definition.islandView && tree==0 && island[i]<0) {
                for(int cursor=i;cursor>=0 && !needed[cursor];cursor=definition.bones.get(cursor).parentIdx) needed[cursor]=true;
            }
            for(int i:original) if(island[i]==tree && (definition.jointByBone[i]!=null || ends[i])) {
                for(int cursor=i;cursor>=0 && !simulation[cursor];cursor=definition.bones.get(cursor).parentIdx) simulation[cursor]=true;
            }
            int start=traversal.size();
            traversal.set(tree*4,start);
            for(int i:original) if(simulation[i]) traversal.add(sorted[i]);
            int end=traversal.size();traversal.set(tree*4+1,end);
            if(java.util.Arrays.equals(needed,simulation)) {
                traversal.set(tree*4+2,start);traversal.set(tree*4+3,end);
            } else {
                traversal.set(tree*4+2,traversal.size());
                for(int i:original) if(needed[i]) traversal.add(sorted[i]);
                traversal.set(tree*4+3,traversal.size());
            }
        }
        uploadOrder=traversal.subList(trees*4,traversal.size()).stream().mapToInt(Integer::intValue).distinct().sorted().toArray();
        ByteBuffer data=MemoryUtil.memCalloc(original.length*80);
        try {
            for(int i:original) {
                int parent=definition.bones.get(i).parentIdx;
                var joint=cpu[i]?null:definition.jointByBone[i];
                var pivot=definition.pivot(i);
                data.putFloat(pivot.x).putFloat(pivot.y).putFloat(pivot.z).putFloat(definition.tailDepth[i]);
                data.putInt(parent<0?-1:sorted[parent]).putInt(first[i]).putInt(next[i])
                        .putInt(joint==null?-2:joint.parent()<0?-1:sorted[joint.parent()]);
                var p=joint==null?PhysParameters.DEFAULT:joint.parameters();
                data.putFloat(p.r()).putFloat(p.gw()==null?p.g():p.gw()).putFloat(p.s()).putFloat(p.a());
                data.putFloat(p.c()).putFloat(p.air()).putFloat((p.gw()!=null?1:0)+(definition.tails[i]?2:0)).putFloat(ends[i]?1:0);
                data.putInt(island[i]).putInt(i).putInt(glowing[i]?1:0).putInt(p.bc()?1:0);
            }
            data.flip();buffer=GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,data,GL15.GL_STATIC_DRAW);
            if(GL15.glGetBufferParameteri(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=data.remaining()) {
                GL15.glDeleteBuffers(buffer);throw new IllegalStateException("GPU physics topology allocation failed");
            }
        } finally {MemoryUtil.memFree(data);}
        var indices=MemoryUtil.memAllocInt(traversal.size());
        int scheduleId=0;
        try {
            for(int index:traversal) indices.put(index);
            indices.flip();scheduleId=GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,scheduleId);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,indices,GL15.GL_STATIC_DRAW);
        } catch(RuntimeException | Error e) {
            if(scheduleId!=0) GL15.glDeleteBuffers(scheduleId);GL15.glDeleteBuffers(buffer);throw e;
        } finally {MemoryUtil.memFree(indices);}
        schedule=scheduleId;
    }

    @Override public void close() {GL15.glDeleteBuffers(buffer);GL15.glDeleteBuffers(schedule);}
}
