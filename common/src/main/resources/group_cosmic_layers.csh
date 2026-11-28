#version 430 core
#define M_PI 3.1415926535897932384626433832795
layout(local_size_x=16) in;
struct Layer {mat4 rotation;vec4 values;};
layout(std430,binding=0) writeonly buffer Layers {Layer layers[];};
mat4 rotationMatrix(vec3 axis, float angle)
{

    axis = normalize(axis);
    float s = sin(angle);
    float c = cos(angle);
    float oc = 1.0 - c;
    
    return mat4(oc * axis.x * axis.x + c,           oc * axis.x * axis.y - axis.z * s,  oc * axis.z * axis.x + axis.y * s,  0.0,
                oc * axis.x * axis.y + axis.z * s,  oc * axis.y * axis.y + c,           oc * axis.y * axis.z - axis.x * s,  0.0,
                oc * axis.z * axis.x - axis.y * s,  oc * axis.y * axis.z + axis.x * s,  oc * axis.z * axis.z + c,           0.0,
                0.0,                                0.0,                                0.0,                                1.0);
}

void main(){
    int i=int(gl_GlobalInvocationID.x),j=i+7,k=j+1;
    float rand1=(j*j*4321+j*8)*2.0;
    float rand2=(k*k*k*239+k*37)*3.6;
    float rand3=rand1*347.4+rand2*63.4;
    vec3 axis=normalize(vec3(sin(rand1),sin(rand2),cos(rand3)));
    layers[i].rotation=rotationMatrix(axis,mod(rand3,2*M_PI));
    layers[i].values=vec4((mod(rand1,29.0)/29.0)*0.3+0.4,(mod(rand2,35.0)/35.0)*0.4+0.6,(mod(rand1,17.0)/17.0)*0.3+0.7,(16-i)*0.5+2.75);
}
