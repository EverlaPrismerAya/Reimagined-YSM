#version 430 core
layout(local_size_x=64) in;
layout(std430,binding=0) readonly buffer InputBuf {uint source[];};
layout(std430,binding=1) writeonly buffer OutputBuf {uint target[];};
struct BoneData {mat4 transform;mat4 normal;ivec4 flags;};
layout(std430,binding=2) readonly buffer BoneBlock {BoneData bones[];};
uniform samplerBuffer atlasLayout;
uniform int atlasSize,overlay;
uniform mat4 outlineTransform;
uniform mat4 Projection;
uniform int outlineFirst,outlineCount,sourceFirst;
vec4 projected(uint index) {
    uint base=index*8u;
    vec3 p=vec3(uintBitsToFloat(source[base]),uintBitsToFloat(source[base+1u]),uintBitsToFloat(source[base+2u]));
    return Projection*bones[source[base+6u]&65535u].transform*vec4(p,1);
}
void main() {
    uint id=gl_GlobalInvocationID.x+uint(sourceFirst);
    uint count=source.length()/8u;
    if(id>=count || (outlineCount>0 && id>=uint(outlineFirst+outlineCount))) return;
    uint base=id*8u;
    BoneData b=bones[source[base+6u]&65535u];
    vec3 local=vec3(uintBitsToFloat(source[base]),uintBitsToFloat(source[base+1u]),uintBitsToFloat(source[base+2u]));
    vec3 p=(b.transform*vec4(local,1)).xyz;
    uint packed=source[base+5u];
    ivec3 signedNormal=ivec3(int(packed<<22u)>>22,int(packed<<12u)>>22,int(packed<<2u)>>22);
    vec3 n=normalize(mat3(b.normal)*vec3(signedNormal)/511.0);
    ivec3 q=ivec3(round(clamp(n,-1,1)*127));
    uint normal=(uint(q.x)&255u)|((uint(q.y)&255u)<<8u)|((uint(q.z)&255u)<<16u);
    bool hidden=b.flags.y!=0;
    if((source[base+6u]>>24u)!=0u) {
        uint first=id/3u*3u;
        hidden=hidden || determinant(mat3(projected(first).xyw,projected(first+1u).xyw,projected(first+2u).xyw))<=0;
    }
    if(hidden) p=vec3(2);
    for(uint band=0u;band<3u;band++) {
        if(outlineCount>0 && (band!=2u || int(id)<outlineFirst || int(id)>=outlineFirst+outlineCount)) continue;
        vec3 pos=p;
        if(outlineCount>0) {vec4 moved=outlineTransform*vec4(p,1);pos=moved.xyz/moved.w;}
        uint dst=(band*count+id)*9u;
        int tile=int(id/3u+band*(count/3u)),corner=int(id%3u);
        vec4 mapping=texelFetch(atlasLayout,tile);
        vec2 uv=vec2(uintBitsToFloat(source[base+3u]),uintBitsToFloat(source[base+4u]));
        vec2 pixel=(mapping.x<0?vec2(corner==1?-mapping.x-2:0,corner==2?mapping.y-2:0):uv*mapping.xy)+mapping.zw;
        target[dst]=floatBitsToUint(pos.x);target[dst+1u]=floatBitsToUint(pos.y);target[dst+2u]=floatBitsToUint(pos.z);
        target[dst+3u]=hidden?0u:0xffffffffu;
        target[dst+4u]=floatBitsToUint(pixel.x/atlasSize);target[dst+5u]=floatBitsToUint(pixel.y/atlasSize);
        target[dst+6u]=uint(overlay);target[dst+7u]=uint(b.flags.x);target[dst+8u]=normal;
    }
}
