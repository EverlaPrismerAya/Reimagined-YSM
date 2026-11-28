#version 430 core
layout(local_size_x=64) in;
struct BoneData {mat4 transform;mat4 normal;ivec4 flags;};
struct Solid {vec4 planes[6];vec4 lo;vec4 hi;ivec4 meta;};
layout(std430,binding=0) readonly buffer BoneBlock {BoneData bones[];};
layout(std430,binding=3) readonly buffer LocalBlock {Solid localSolids[];};
layout(std430,binding=4) buffer WorldBlock {Solid solids[];};
struct BvhNode {vec4 lo;vec4 hi;ivec4 links;ivec4 info;};
layout(std430,binding=5) buffer NeighborBlock {BvhNode tree[];};
uniform int solidCount,stage,part,nodeCount,refitDepth;
void main() {
    uint id=gl_GlobalInvocationID.x;
    if(stage==0 && id>=uint(solidCount) || stage!=0 && id>=uint(nodeCount))return;
    if(stage==0) {
        Solid s=localSolids[id];BoneData b=bones[s.meta.x];
        vec3 lo=vec3(1e30),hi=vec3(-1e30);
        for(int i=0;i<8;i++) {
            vec3 p=(b.transform*vec4(mix(s.lo.xyz,s.hi.xyz,vec3(i&1,(i>>1)&1,(i>>2)&1)),1)).xyz;
            lo=min(lo,p);hi=max(hi,p);
        }
        for(int i=0;i<6;i++) {
            vec3 n=mat3(b.normal)*s.planes[i].xyz;
            float d=s.planes[i].w+dot(n,b.transform[3].xyz);
            float lengthN=length(n);s.planes[i]=lengthN>1e-15?vec4(n,d)/lengthN:vec4(0);
        }
        s.lo=vec4(lo,s.lo.w);s.hi=vec4(hi,0);
        if(b.flags.y!=0 || abs(determinant(mat3(b.transform)))<1e-15 || (part!=0 && s.meta.w!=part && s.meta.w!=3))s.meta.z=0;
        solids[id]=s;return;
    }
    BvhNode node=tree[id];if(node.info.x!=refitDepth)return;
    if(node.links.w>=0) {
        Solid s=solids[node.links.w];node.lo=s.meta.z!=0?s.lo:vec4(1e30);node.hi=s.meta.z!=0?s.hi:vec4(-1e30);
    }else {node.lo=min(tree[node.links.x].lo,tree[node.links.y].lo);node.hi=max(tree[node.links.x].hi,tree[node.links.y].hi);}
    tree[id]=node;
}
