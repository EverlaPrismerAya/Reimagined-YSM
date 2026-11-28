#version 150
layout(triangles) in;
layout(triangle_strip, max_vertices=4) out;
uniform int atlasSize;
uniform bool materialPass;
in vec3 atlas_position[];
in vec2 atlas_uv[];
in vec4 atlas_vertexColor[];
in vec3 atlas_normal[];
flat in uint atlas_surfaceCube[];
out vec3 position;
out vec2 uv;
out vec4 vertexColor;
out vec3 normal;
flat out uint surfaceCube;

void emitSample(vec4 p, vec3 weights) {
    gl_Position=p;
    position=atlas_position[0]*weights.x+atlas_position[1]*weights.y+atlas_position[2]*weights.z;
    uv=atlas_uv[0]*weights.x+atlas_uv[1]*weights.y+atlas_uv[2]*weights.z;
    vertexColor=atlas_vertexColor[0]*weights.x+atlas_vertexColor[1]*weights.y+atlas_vertexColor[2]*weights.z;
    normal=atlas_normal[0]*weights.x+atlas_normal[1]*weights.y+atlas_normal[2]*weights.z;
    surfaceCube=atlas_surfaceCube[0];
    EmitVertex();
}
void main() {
    if (!materialPass) {
        for(int i=0;i<3;i++) emitSample(gl_in[i].gl_Position,vec3(i==0?1:0,i==1?1:0,i==2?1:0));
        EndPrimitive();
        return;
    }
    vec2 a=(gl_in[0].gl_Position.xy*.5+.5)*float(atlasSize);
    vec2 b=(gl_in[1].gl_Position.xy*.5+.5)*float(atlasSize);
    vec2 c=(gl_in[2].gl_Position.xy*.5+.5)*float(atlasSize);
    mat2 edges=mat2(b-a,c-a);
    // Hidden/back-facing GPU triangles have all three positions collapsed.
    if(abs(determinant(edges))<1e-6) return;
    vec2 lo=floor(min(a,min(b,c))+1e-4)-1;
    vec2 hi=ceil(max(a,max(b,c))-1e-4)+1;
    // Extend the source-aligned sampling grid through a one-texel gutter.
    // Extrapolation also handles rotated and mirrored UVs without a second grid.
    for(int i=0;i<4;i++) {
        vec2 pixel=mix(lo,hi,vec2(i%2,i/2));
        vec2 w=inverse(edges)*(pixel-a);
        emitSample(vec4(pixel/float(atlasSize)*2-1,0,1),vec3(1-w.x-w.y,w));
    }
    EndPrimitive();
}
