#pragma once
// 冻结的 rc10 原生产 shader，来自 GPU修复前 source-before；仅用于默认回归。
inline const char*legacyBinaryShader(){return R"(#version 310 es
precision highp int;
layout(local_size_x=128)in;
layout(std430,binding=7)readonly buffer Gray{uint grayIn[];};
layout(std430,binding=4)readonly buffer Tone{uint tone[];};
layout(std430,binding=5)writeonly buffer New{uint nextLevels[];};
layout(std430,binding=6)readonly buffer Old{uint oldLevels[];};
layout(std430,binding=0)writeonly buffer Mixed{uint mixed[];};
layout(std430,binding=8)readonly buffer Noise{uint noise[];};
uniform uint uVideo;uniform float uGamma0;uniform float uBlack;uniform float uHyst;
void main(){uint g=gl_GlobalInvocationID.x;if(g>=259200u)return;
uint old=oldLevels[g],next=0u,gw=grayIn[g];
for(uint k=0u;k<4u;k++){uint i=g*4u+k,x=i%1440u,y=i/1440u,xy=x^y;
uint gray=(gw>>(k*8u))&255u;
uint mapped=(tone[gray>>2u]>>((gray&3u)*8u))&255u;
uint b=((x&1u)<<4u)|((xy&1u)<<5u)|((x&2u)<<1u)|((xy&2u)<<2u)|((x&4u)>>2u)|((xy&4u)>>1u);
uint level;
if(uVideo==0u)level=mapped>(b*4u+2u)?30u:0u;
else{
float xv=clamp((float(mapped)-uBlack)/(255.0-uBlack),0.0,1.0);
float v=xv>0.0?pow(xv,uGamma0+(1.0-uGamma0)*xv):0.0;
float t=uVideo==1u?(float(noise[((y&63u)<<6u)|(x&63u)])+0.5)/4096.0:(float(b)*4.0+2.0)/255.0;
bool wasWhite=((old>>(k*8u))&255u)>=16u;
level=v>(wasWhite?t-uHyst:t+uHyst)?30u:0u;}
next|=level<<(k*8u);}
nextLevels[g]=next;
uint n0=(next&255u)>>1u,n1=((next>>8u)&255u)>>1u,n2=((next>>16u)&255u)>>1u,n3=(next>>24u)>>1u;
uint o0=(old&255u)>>1u,o1=((old>>8u)&255u)>>1u,o2=((old>>16u)&255u)>>1u,o3=(old>>24u)>>1u;
// Stock Generation_MixFrame_Y4(old=ctl+0x60, new=ctl+0x58): even byte = old pair, odd byte = new pair.
mixed[g]=(o0<<4u)|o1|(((n0<<4u)|n1)<<8u)|(((o2<<4u)|o3)<<16u)|(((n2<<4u)|n3)<<24u);
})";}
