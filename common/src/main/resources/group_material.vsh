#version 150
in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;
uniform samplerBuffer atlasLayout;
uniform int atlasSize;
uniform int atlasTriangles;
uniform int atlasBand;
out vec3 position;
out vec2 uv;
out vec4 vertexColor;
out vec3 normal;
void main() {
    int triangle=gl_VertexID/3 + atlasBand*atlasTriangles;
    int corner=gl_VertexID%3;
    vec4 mapping=texelFetch(atlasLayout,triangle);
    vec2 pixel=mapping.x<0?vec2(corner==1?-mapping.x-2:0,corner==2?mapping.y-2:0):UV0*mapping.xy;
    gl_Position=vec4((pixel+mapping.zw)/float(atlasSize)*2.0-1.0,0,1);
    position=Position;uv=UV0;vertexColor=vec4(1);normal=Normal;
}
