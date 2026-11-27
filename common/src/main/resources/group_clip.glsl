// Injected only into the GPU variants; geometry and adjacency remain GPU resident.
in vec3 position;
in vec3 normal;
flat in uint surfaceCube;
struct SurfaceSolid {vec4 planes[6];vec4 lo;vec4 hi;ivec4 meta;};
layout(std430,binding=4) readonly buffer SurfaceBlock {SurfaceSolid solids[];};
layout(std430,binding=5) readonly buffer SurfaceNeighbors {uint neighbors[];};
uniform bool surfaceClipping;
uniform int solidCount;
uniform bool silhouetteMaskEnabled, silhouetteAtlas;
uniform sampler2D silhouetteMask;
uniform vec4 silhouetteViewport;
uniform mat4 silhouetteProjection;
bool rysmOutlineMasked() {
    if(!silhouetteMaskEnabled)return false;
    vec2 uv;
    if(silhouetteAtlas) {vec4 p=silhouetteProjection*vec4(position,1);uv=p.xy/p.w*.5+.5;}
    else uv=(gl_FragCoord.xy-silhouetteViewport.xy)/silhouetteViewport.zw;
    return texture(silhouetteMask,uv).r>.5;
}
bool rysmInterior() {
    if(!surfaceClipping)return false;
    uint words=(uint(solidCount)+31u)/32u;
    for(uint word=0u;word<words;word++) {
        uint bits=neighbors[surfaceCube*words+word];
        while(bits!=0u) {
            uint other=word*32u+uint(findLSB(bits));bits&=bits-1u;
            SurfaceSolid s=solids[other];bool inside=true;
            for(int i=0;i<6;i++) {
                vec4 plane=s.planes[i];
                // Keep exactly one copy of coincident exterior faces.
                float epsilon=dot(plane.xyz,normalize(normal))>.999 && other>surfaceCube?-1e-6:1e-6;
                if(dot(plane.xyz,position)-plane.w>epsilon){inside=false;break;}
            }
            if(inside)return true;
        }
    }
    return false;
}
