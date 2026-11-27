#version 430 core
layout(location=0) in vec3 Position;
layout(location=1) in vec2 UV0;
layout(location=2) in vec4 Normal;
layout(location=3) in uint BoneId;
layout(location=4) in float Cullable;
struct BoneData {mat4 transform;mat4 normal;ivec4 flags;};
layout(std430,binding=0) readonly buffer BoneBlock {BoneData bones[];};
layout(std430,binding=1) readonly buffer MeshBlock {uint source[];};
uniform mat4 Projection;
uniform vec4 color;
uniform int atlasColumns,atlasTriangles,atlasBand,atlasTileSize;
uniform bool materialPass;
out vec3 position;
out vec2 uv;
out vec4 vertexColor;
out vec3 normal;
flat out uint surfaceCube;
vec4 projected(uint index) {
    uint base=index*8u;
    vec3 p=vec3(uintBitsToFloat(source[base]),uintBitsToFloat(source[base+1u]),uintBitsToFloat(source[base+2u]));
    return Projection*bones[source[base+6u]&65535u].transform*vec4(p,1);
}
void main() {
    BoneData b=bones[BoneId];
    position=(b.transform*vec4(Position,1)).xyz;
    normal=normalize(mat3(b.normal)*Normal.xyz);
    uv=UV0;vertexColor=color;
    surfaceCube=source[uint(gl_VertexID)*8u+7u];
    gl_Position=Projection*vec4(position,1);
    if(materialPass) {
        int triangle=gl_VertexID/3+atlasBand*atlasTriangles;
        int corner=gl_VertexID%3;
        float edge=float(atlasTileSize);
        vec2 local=corner==0?vec2(1):corner==1?vec2(edge-1,1):vec2(1,edge-1);
        vec2 pixel=vec2(triangle%atlasColumns,triangle/atlasColumns)*edge+local;
        gl_Position=vec4(pixel/(float(atlasColumns)*edge)*2-1,0,1);
    }
    bool culled=false;
    if(Cullable>0) {
        uint first=uint(gl_VertexID/3)*3u;
        vec4 p0=projected(first),p1=projected(first+1u),p2=projected(first+2u);
        culled=determinant(mat3(p0.xyw,p1.xyw,p2.xyw))<=0;
    }
    if(b.flags.y!=0 || culled) gl_Position=vec4(2,2,2,1);
}
