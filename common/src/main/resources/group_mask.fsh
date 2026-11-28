#version 150
uniform sampler2D texture0;
in vec2 uv;
out vec4 fragColor;
void main(){if(texture(texture0,uv).a<0.1)discard;fragColor=vec4(1);}
