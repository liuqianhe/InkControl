#pragma once
// 生产与独立 GLES probe 使用同一份 shader；不包含 Android 服务依赖。
namespace einkalg {
inline const char*binaryShader(){return R"(#version 310 es
precision highp int;
precision highp float;
layout(local_size_x=128)in;
layout(std430,binding=7)readonly buffer Gray{uint grayIn[];};
layout(std430,binding=4)readonly buffer Tone{uint tone[];};
layout(std430,binding=5)writeonly buffer New{uint nextLevels[];};
layout(std430,binding=6)readonly buffer Old{uint oldLevels[];};
layout(std430,binding=0)writeonly buffer Mixed{uint mixed[];};
layout(std430,binding=8)readonly buffer Noise{uint noise[];};
uniform uint uAlgorithm;uniform uint uVideoTone;uniform uint count;uniform uint width;
uniform float uGamma0;uniform float uBlack;uniform float uHyst;
void main(){uint g=gl_GlobalInvocationID.x;if(g>=(count+3u)/4u)return;
uint old=oldLevels[g],next=0u,gw=grayIn[g];
for(uint k=0u;k<4u;k++){uint i=g*4u+k;if(i>=count)break;
uint x=i%width,y=i/width,xy=x^y;
uint gray=(gw>>(k*8u))&255u;
uint mapped=(tone[gray>>2u]>>((gray&3u)*8u))&255u;
uint b=((x&1u)<<4u)|((xy&1u)<<5u)|((x&2u)<<1u)|((xy&2u)<<2u)|((x&4u)>>2u)|((xy&4u)>>1u);
uint level;
// 默认普通 Bayer 保留原整数比较，避免归一化改变相等边界。
if(uAlgorithm==0u&&uVideoTone==0u&&uHyst==0.0)level=mapped>(b*4u+2u)?30u:0u;
else if(uAlgorithm==0u&&uVideoTone==0u){
bool wasWhite=((old>>(k*8u))&255u)>=16u;
// 普通迟滞只有整数像素偏移（当前12）。先取整，再比较，防止Mali融合
// float加乘后让原本相等的 mapped=threshold-h 被判为白色。
int h=int(round(uHyst*255.0));
level=int(mapped)>(int(b*4u+2u)+(wasWhite?-h:h))?30u:0u;
}
else{
float v=float(mapped)/255.0;
if(uVideoTone!=0u){
float xv=clamp((float(mapped)-uBlack)/(255.0-uBlack),0.0,1.0);
v=xv>0.0?pow(xv,uGamma0+(1.0-uGamma0)*xv):0.0;
}
float t=uAlgorithm==1u?(float(noise[((y&63u)<<6u)|(x&63u)])+0.5)/4096.0:
        uAlgorithm==2u?0.5:(float(b)*4.0+2.0)/255.0;
bool wasWhite=((old>>(k*8u))&255u)>=16u;
// 不夹紧偏移阈值：保持旧视频 default 的 t-h / t+h 和严格大于比较。
level=v>(wasWhite?t-uHyst:t+uHyst)?30u:0u;}
next|=level<<(k*8u);}
nextLevels[g]=next;
uint n0=(next&255u)>>1u,n1=((next>>8u)&255u)>>1u,n2=((next>>16u)&255u)>>1u,n3=(next>>24u)>>1u;
uint o0=(old&255u)>>1u,o1=((old>>8u)&255u)>>1u,o2=((old>>16u)&255u)>>1u,o3=(old>>24u)>>1u;
// PairY4：偶数字节 old pair，奇数字节 new pair；等级仍是偶数 0..30。
mixed[g]=(o0<<4u)|o1|(((n0<<4u)|n1)<<8u)|(((o2<<4u)|o3)<<16u)|(((n2<<4u)|n3)<<24u);
})";}
} // namespace einkalg
