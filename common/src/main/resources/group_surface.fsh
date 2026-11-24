#version 150
uniform sampler2D texture0;
uniform int mode;
uniform vec4 tint;
uniform sampler2D lightmap;
uniform sampler2D overlayMap;
uniform int packedLight;
uniform int packedOverlay;
uniform bool glowing;
uniform int alphaMode;
uniform vec3 light0;
uniform vec3 light1;
uniform vec4 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform int fogShape;
in vec3 position;
in vec3 normal;
in vec2 uv;
in vec4 vertexColor;
out vec4 fragColor;
void main() {
    vec4 tex=texture(texture0,uv);
    if(tex.a<0.1) discard;
    if(alphaMode==1 && tex.a<0.99) discard;
    if(alphaMode==2 && tex.a>=0.99) discard;
    fragColor=mode==1 ? vec4(tint.rgb,tex.a*vertexColor.a) : tex*vertexColor;
    vec3 n=normalize(normal);
    float shade=min(1.0,(max(0.0,dot(normalize(light0),n))+max(0.0,dot(normalize(light1),n)))*.6+.4);
    fragColor.rgb*=shade;
    vec4 overlay=texelFetch(overlayMap,ivec2(packedOverlay&65535,(packedOverlay>>16)&65535),0);
    fragColor.rgb=mix(overlay.rgb,fragColor.rgb,overlay.a);
    ivec2 luv=glowing?ivec2(15):ivec2((packedLight&65535)/16,((packedLight>>16)&65535)/16);
    fragColor.rgb*=texelFetch(lightmap,luv,0).rgb;
    float distance=fogShape==0?length(position):max(length(position.xz),abs(position.y));
    float fog=distance<=fogStart?0.0:distance>=fogEnd?1.0:smoothstep(fogStart,fogEnd,distance);
    fragColor.rgb=mix(fragColor.rgb,fogColor.rgb,fog*fogColor.a);
}
