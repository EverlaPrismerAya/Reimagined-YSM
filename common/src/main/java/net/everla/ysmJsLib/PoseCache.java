package net.everla.ysmJsLib;

import com.elfmcys.yesstevemodel.geckolib3.core.processor.IBone;
import java.util.IdentityHashMap;
import java.util.Map;

/** Tick-rate script output, replayed against each render's fresh animation pose. */
public final class PoseCache {
    private static final class Transform {
        final float[] current=new float[18],previous=new float[18],base=new float[9];
        boolean applied;
        Transform(){identity(current);identity(previous);}
    }
    private final Map<IBone,Transform> transforms=new IdentityHashMap<>();
    private long tick=Long.MIN_VALUE;
    private Object world;
    private boolean initial;
    private static void identity(float[] v){java.util.Arrays.fill(v,0);for(int i:new int[]{6,7,8,15,16,17})v[i]=1;}
    public boolean sample(long next,Object currentWorld) {
        if(tick==next && world==currentWorld)return false;
        restore();
        initial=tick==Long.MIN_VALUE || next<tick || next-tick>1 || world!=currentWorld;
        tick=next;world=currentWorld;
        for(var t:transforms.values()){System.arraycopy(t.current,0,t.previous,0,18);identity(t.current);}
        return true;
    }
    public void record(IBone bone,String operation,float x,float y,float z,boolean interpolate) {
        var t=transforms.computeIfAbsent(bone,b->new Transform());int offset=interpolate?9:0;
        switch(operation){
            case "move"->{t.current[offset]+=x;t.current[offset+1]+=y;t.current[offset+2]+=z;}
            case "rotate"->{t.current[offset+3]+=(float)Math.toRadians(x);t.current[offset+4]+=(float)Math.toRadians(y);t.current[offset+5]+=(float)Math.toRadians(z);}
            case "scale"->{t.current[offset+6]*=x;t.current[offset+7]*=y;t.current[offset+8]*=z;}
            default->throw new IllegalArgumentException(operation);
        }
    }
    public void finish(){if(initial)for(var t:transforms.values())System.arraycopy(t.current,0,t.previous,0,18);}
    public void apply(float partial) {
        float alpha=Math.max(0,Math.min(1,partial));
        for(var entry:transforms.entrySet()) {
            IBone b=entry.getKey();var t=entry.getValue();var v=t.base;
            v[0]=b.getPositionX();v[1]=b.getPositionY();v[2]=b.getPositionZ();v[3]=b.getRotationX();v[4]=b.getRotationY();v[5]=b.getRotationZ();v[6]=b.getScaleX();v[7]=b.getScaleY();v[8]=b.getScaleZ();t.applied=true;
            b.setPositionX(v[0]+value(t,0,alpha));b.setPositionY(v[1]+value(t,1,alpha));b.setPositionZ(v[2]+value(t,2,alpha));
            b.setRotationX(v[3]+value(t,3,alpha));b.setRotationY(v[4]+value(t,4,alpha));b.setRotationZ(v[5]+value(t,5,alpha));
            b.setScaleX(v[6]*value(t,6,alpha));b.setScaleY(v[7]*value(t,7,alpha));b.setScaleZ(v[8]*value(t,8,alpha));
        }
    }
    private static float value(Transform t,int index,float alpha){float lerp=t.previous[index+9]+(t.current[index+9]-t.previous[index+9])*alpha;return index<6?t.current[index]+lerp:t.current[index]*lerp;}
    public void restore(){for(var entry:transforms.entrySet()){var t=entry.getValue();if(!t.applied)continue;var b=entry.getKey();var v=t.base;b.setPositionX(v[0]);b.setPositionY(v[1]);b.setPositionZ(v[2]);b.setRotationX(v[3]);b.setRotationY(v[4]);b.setRotationZ(v[5]);b.setScaleX(v[6]);b.setScaleY(v[7]);b.setScaleZ(v[8]);t.applied=false;}}
}
