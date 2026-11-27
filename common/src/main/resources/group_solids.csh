#version 430 core
layout(local_size_x=64) in;
struct BoneData {mat4 transform;mat4 normal;ivec4 flags;};
struct Solid {vec4 planes[6];vec4 lo;vec4 hi;ivec4 meta;};
layout(std430,binding=0) readonly buffer BoneBlock {BoneData bones[];};
layout(std430,binding=3) readonly buffer LocalBlock {Solid localSolids[];};
layout(std430,binding=4) buffer WorldBlock {Solid solids[];};
layout(std430,binding=5) writeonly buffer NeighborBlock {uint neighbors[];};
uniform int solidCount,stage,part;
void main() {
    uint id=gl_GlobalInvocationID.x;if(id>=uint(solidCount))return;
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
        s.lo=vec4(lo,0);s.hi=vec4(hi,0);
        if(b.flags.y!=0 || abs(determinant(mat3(b.transform)))<1e-15 || (part!=0 && s.meta.w!=part && s.meta.w!=3))s.meta.z=0;
        solids[id]=s;return;
    }
    Solid own=solids[id];uint words=(uint(solidCount)+31u)/32u;
    for(uint word=0u;word<words;word++) {
        uint bits=0u;
        for(uint bit=0u;bit<32u;bit++) {
            uint other=word*32u+bit;if(other>=uint(solidCount))break;
            Solid s=solids[other];
            if(other!=id && s.meta.z!=0 && s.meta.y==own.meta.y && all(lessThanEqual(own.lo.xyz,s.hi.xyz+vec3(1e-6))) && all(lessThanEqual(s.lo.xyz,own.hi.xyz+vec3(1e-6))))bits|=1u<<bit;
        }
        neighbors[id*words+word]=bits;
    }
}
