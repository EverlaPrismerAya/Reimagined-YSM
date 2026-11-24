#version 150
uniform sampler2D texture0;
uniform int alphaMode;
uniform bool maskOnly;
in vec2 uv;
out vec4 fragColor;
void main() {
    vec4 tex=texture(texture0,uv);
    if(tex.a<0.1) discard;
    if(alphaMode==1 && tex.a<0.99) discard;
    if(alphaMode==2 && tex.a>=0.99) discard;
    fragColor=maskOnly?vec4(1,1,1,tex.a):tex;
}
