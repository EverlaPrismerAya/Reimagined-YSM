// Injected only into the GPU variants; geometry and adjacency remain GPU resident.
in vec3 position;
in vec3 normal;
flat in uint surfaceCube;
struct SurfaceSolid {vec4 planes[6];vec4 lo;vec4 hi;ivec4 meta;};
layout(std430,binding=4) readonly buffer SurfaceBlock {SurfaceSolid solids[];};
struct SurfaceBvhNode {vec4 lo;vec4 hi;ivec4 links;ivec4 info;};
layout(std430,binding=5) readonly buffer SurfaceNeighbors {SurfaceBvhNode surfaceTree[];};
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
    int node=floatBitsToInt(solids[surfaceCube].lo.w);int end=surfaceTree[node].links.z;
    while(node<end) {
        SurfaceBvhNode n=surfaceTree[node];
        if(any(lessThan(position,n.lo.xyz-vec3(1e-6))) || any(greaterThan(position,n.hi.xyz+vec3(1e-6)))) {node=n.links.z;continue;}
        int other=n.links.w;
        if(other<0){node=n.links.x;continue;}
        node=n.links.z;
        if(other==int(surfaceCube))continue;
        SurfaceSolid s=solids[other];bool inside=true;
        for(int i=0;i<6;i++) {
            vec4 plane=s.planes[i];
            float epsilon=dot(plane.xyz,normalize(normal))>.999 && uint(other)>surfaceCube?-1e-6:1e-6;
            if(dot(plane.xyz,position)-plane.w>epsilon){inside=false;break;}
        }
        if(inside)return true;
    }
    return false;
}
