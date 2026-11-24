#version 150
in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;
uniform mat4 Projection;
out vec3 position;
out vec2 uv;
out vec4 vertexColor;
out vec3 normal;
void main() {
    position=Position;
    uv=UV0;
    vertexColor=Color;
    normal=Normal;
    gl_Position=Projection*vec4(Position,1.0);
}
