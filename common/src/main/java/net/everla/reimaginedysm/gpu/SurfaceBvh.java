package net.everla.reimaginedysm.gpu;

import java.nio.ByteBuffer;
import java.util.*;

/** Immutable balanced forest, one stackless tree per surface group. GPU refits only bounds. */
public final class SurfaceBvh {
    public record Leaf(int solid,int group,float[] bounds) {}
    private static final class Node {int left=-1,right=-1,escape,solid=-1,depth;}
    private final List<Node> nodes=new ArrayList<>();
    public final int[] roots;
    public final int depth;
    public SurfaceBvh(List<Leaf> leaves) {
        roots=new int[leaves.size()];var groups=new LinkedHashMap<Integer,List<Leaf>>();
        for(var leaf:leaves)groups.computeIfAbsent(leaf.group(),g->new ArrayList<>()).add(leaf);
        for(var group:groups.values()){int root=build(group,0);for(var leaf:group)roots[leaf.solid()]=root;}
        depth=nodes.stream().mapToInt(n->n.depth).max().orElse(0);
    }
    private int build(List<Leaf> leaves,int level) {
        int index=nodes.size();var node=new Node();node.depth=level;nodes.add(node);
        if(leaves.size()==1)node.solid=leaves.get(0).solid();
        else {
            float[] lo={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},hi={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
            for(var leaf:leaves)for(int a=0;a<3;a++){float c=leaf.bounds()[a]+leaf.bounds()[a+3];lo[a]=Math.min(lo[a],c);hi[a]=Math.max(hi[a],c);}
            int axis=hi[1]-lo[1]>hi[0]-lo[0]?1:0;if(hi[2]-lo[2]>hi[axis]-lo[axis])axis=2;final int split=axis;
            leaves.sort(Comparator.comparingDouble(l->l.bounds()[split]+l.bounds()[split+3]));int middle=leaves.size()/2;
            node.left=build(new ArrayList<>(leaves.subList(0,middle)),level+1);node.right=build(new ArrayList<>(leaves.subList(middle,leaves.size())),level+1);
        }
        node.escape=nodes.size();return index;
    }
    public int size(){return nodes.size();}
    public void write(ByteBuffer out){for(var n:nodes){for(int i=0;i<8;i++)out.putFloat(0);out.putInt(n.left).putInt(n.right).putInt(n.escape).putInt(n.solid);out.putInt(n.depth).putInt(0).putInt(0).putInt(0);}}
}
