#version 150
in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;
uniform int atlasColumns;
uniform int atlasTriangles;
uniform int atlasBand;
uniform int atlasTileSize;
out vec3 position;
out vec2 uv;
out vec4 vertexColor;
out vec3 normal;
void main() {
    int triangle=gl_VertexID/3 + atlasBand*atlasTriangles;
    int corner=gl_VertexID%3;
    // A one-texel inset keeps nearest samples off neighbouring tiles.
    float edge=float(atlasTileSize);
    vec2 local=corner==0?vec2(1):corner==1?vec2(edge-1,1):vec2(1,edge-1);
    vec2 pixel=vec2(triangle%atlasColumns,triangle/atlasColumns)*edge+local;
    gl_Position=vec4(pixel/(float(atlasColumns)*edge)*2.0-1.0,0,1);
    position=Position;uv=UV0;vertexColor=vec4(1);normal=Normal;
}
