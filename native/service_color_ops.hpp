#pragma once
#include <cstddef>
#include <cstdint>
#include <climits>
#include <string>
#include <stdexcept>

// Exact stock DitherColorStyle (0x984ec) / DitherColorVideoStyle (0x97ddc).
// Independent opt-in kernels. They do not change the default blue video path.
// Bindings: 0 packed Y8 input, 1 packed RGBA, 2 int32 error[pixels],
// 3 packed binary Y8 output, 4 int32 history[4*pixels] = R,G,B,error.
// Video history must persist across frames; caller explicitly seeds it once.
// The host initializes with initShader, then calls chunkShader in increasing
// [beginPixel,endPixel) order with SSBO barriers. No pixel parallelism is claimed.
namespace EinkColorOps {
enum class Algorithm : unsigned { Color=0, Video=1 };
inline bool sizeAllowed(unsigned w,unsigned h,size_t limit=65536) {
    return w>=2 && h>=4 && limit && uint64_t(w)*h<=limit &&
           uint64_t(w)*h<=uint64_t(INT32_MAX)/4;
}
inline const char* initShader() { return R"(#version 310 es
precision highp int;
layout(local_size_x=128)in;
layout(std430,binding=0)readonly buffer Gray{uint gray[];};
layout(std430,binding=2)writeonly buffer Error{int errors[];};
layout(std430,binding=3)writeonly buffer Output{uint outputGray[];};
uniform uint count;
void main(){uint g=gl_GlobalInvocationID.x;if(g>=(count+3u)/4u)return;
uint word=gray[g];outputGray[g]=0u;
for(uint k=0u;k<4u;k++){uint i=g*4u+k;if(i>=count)break;
errors[i]=int((word>>(8u*k))&255u);}})"; }
inline const char* chunkShader() { return R"(#version 310 es
precision highp int;
layout(local_size_x=1)in;
layout(std430,binding=1)readonly buffer RGB{uint rgba[];};
layout(std430,binding=2)buffer Error{int errors[];};
layout(std430,binding=3)buffer Output{uint outputGray[];};
layout(std430,binding=4)buffer History{int history[];};
uniform uint width;uniform uint height;uniform uint beginPixel;uniform uint endPixel;
uniform uint algorithm;
void main(){if(width<2u||height<4u)return;
uint end=min(endPixel,width*height);
for(uint p=beginPixel;p<end;p++){
uint y=p/width,pos=p%width;bool reverse=(y&1u)!=0u;
int d=reverse?-1:1,x=reverse?int(width-1u-pos):int(pos),i=int(y*width)+x;
int r=0,g=0,b=0,delta=0;bool video=algorithm==1u;
if(video){uint color=rgba[i];r=int(color&255u);g=int((color>>8u)&255u);b=int((color>>16u)&255u);
delta=abs(r-history[4*i])+abs(g-history[4*i+1])+abs(b-history[4*i+2]);
if(delta<10)errors[i]=history[4*i+3];}
int value=errors[i],e=value>127?value-255:value;
int small=e>>4,large=(7*e)>>4,five=(5*e)>>4,three=(3*e)>>4;
bool special=pos%3u==2u;
int extra=video?(5*e)>>6:five/2;
if(pos<width-1u)errors[i+d]+=special?extra:five;
if(y<height-3u){
for(uint row=1u;row<=3u;row++){
int j=i+int(row*width);
int self=large/3;
// This asymmetric instruction in stock video is intentional (0x983b0).
if(video&&reverse&&special&&row==2u)self=(7*e)>>6;
errors[j]+=self;
if(pos<width-1u&&(pos&1u)==0u){int v=small/3;
// In BOTH directions the rem3=2 diagonal bonus goes to row+1.
// Reverse addresses use a pointer one int before the current pixel.
if(special&&row==1u)v+=extra;
errors[j+d]+=v;}
if((pos&1u)!=0u)errors[j-d]+=three/3;
}}
if(video&&delta>=10){history[4*i]=r;history[4*i+1]=g;history[4*i+2]=b;history[4*i+3]=errors[i];}
int finalValue=video?history[4*i+3]:errors[i];
uint shift=(uint(i)&3u)*8u,mask=255u<<shift;
outputGray[uint(i)>>2u]=(outputGray[uint(i)>>2u]&~mask)|((finalValue>127?255u:0u)<<shift);
}})"; }
// Preserve the stock serial dependency within each row while keeping its four
// live error rows in shared memory. No tile boundary resets the error field.
// This variant requires packed complete rows; other sizes retain chunkShader.
inline std::string rowCacheShader(unsigned capacity=1440){
    if(!capacity||capacity%4)throw std::invalid_argument("color row capacity must be divisible by four");
    return "#version 310 es\n#define CAPACITY "+std::to_string(capacity)+R"(
precision highp int;
layout(local_size_x=128)in;
layout(std430,binding=0)readonly buffer Gray{uint gray[];};
layout(std430,binding=1)readonly buffer RGB{uint rgba[];};
layout(std430,binding=2)buffer Error{int errors[];};
layout(std430,binding=3)buffer Output{uint outputGray[];};
layout(std430,binding=4)buffer History{int history[];};
layout(std430,binding=6)buffer Ring{int ring[];};
uniform uint width;uniform uint height;uniform uint rowIndex;uniform uint rowsPerDispatch;uniform uint algorithm;
shared int cache[4*CAPACITY];shared int stable[CAPACITY];
void main(){uint lane=gl_LocalInvocationID.x;
if(width>uint(CAPACITY)||width%4u!=0u||height<4u)return;
for(uint a=lane;a<4u*width;a+=128u){
    if(rowIndex==0u){uint value=(gray[a>>2u]>>((a&3u)*8u))&255u;cache[a]=int(value);}
    else cache[a]=ring[a];
}barrier();
for(uint y=rowIndex;y<min(rowIndex+rowsPerDispatch,height);y++){
uint base=(y&3u)*width;
// Parallel video history reads and RGB updates do not depend on diffusion.
for(uint x=lane;x<width;x+=128u){uint i=y*width+x;int previous=-2147483647;
    if(algorithm==1u){uint color=rgba[i];int r=int(color&255u),g=int((color>>8u)&255u),b=int((color>>16u)&255u);
        int delta=abs(r-history[4u*i])+abs(g-history[4u*i+1u])+abs(b-history[4u*i+2u]);
        if(delta<10)previous=history[4u*i+3u];
        else{history[4u*i]=r;history[4u*i+1u]=g;history[4u*i+2u]=b;}
    }stable[x]=previous;
}barrier();
if(lane==0u){bool reverse=(y&1u)!=0u;int d=reverse?-1:1;
for(uint pos=0u;pos<width;pos++){
    uint x=reverse?width-1u-pos:pos;uint a=base+x,i=y*width+x;
    bool unchanged=algorithm==1u&&stable[x]!=-2147483647;
    if(unchanged)cache[a]=stable[x];
    int value=cache[a],e=value>127?value-255:value;
    int small=e>>4,large=(7*e)>>4,five=(5*e)>>4,three=(3*e)>>4;
    bool special=pos%3u==2u;int extra=algorithm==1u?(5*e)>>6:five/2;
    if(pos<width-1u)cache[int(a)+d]+=special?extra:five;
    if(y<height-3u){for(uint row=1u;row<=3u;row++){
        uint j=((y+row)&3u)*width+x;int self=large/3;
        if(algorithm==1u&&reverse&&special&&row==2u)self=(7*e)>>6;
        cache[j]+=self;
        if(pos<width-1u&&(pos&1u)==0u){int v=small/3;if(special&&row==1u)v+=extra;cache[int(j)+d]+=v;}
        if((pos&1u)!=0u)cache[int(j)-d]+=three/3;
    }}
    if(algorithm==1u&&!unchanged)history[4u*i+3u]=cache[a];
}}barrier();
for(uint x=lane;x<width;x+=128u)errors[y*width+x]=cache[base+x];
for(uint g=lane;g<width/4u;g+=128u){uint word=0u;
    for(uint k=0u;k<4u;k++){uint x=g*4u+k;int value=algorithm==1u&&stable[x]!=-2147483647?stable[x]:cache[base+x];
        word|=(value>127?255u:0u)<<(k*8u);}
    outputGray[y*width/4u+g]=word;
}barrier();
for(uint x=lane;x<width;x+=128u){int value=0;
    if(y+4u<height){uint i=(y+4u)*width+x;value=int((gray[i>>2u]>>((i&3u)*8u))&255u);}
    cache[base+x]=value;
}barrier();
}
for(uint a=lane;a<4u*width;a+=128u)ring[a]=cache[a];
})";
}
// A 128-state exact horizontal transfer function. Composition evaluates all
// possible incoming carries in [-64,63]; a four-byte domain counter prevents
// committing an out-of-domain result. Vertical additions are integer atomics,
// preserving the original sum without changing its serpentine row order.
inline std::string prefixCacheShader(unsigned capacity=1440){
    if(!capacity||capacity%4)throw std::invalid_argument("color prefix capacity");
    return "#version 310 es\n#define CAPACITY "+std::to_string(capacity)+R"(
precision highp int;
layout(local_size_x=128)in;
layout(std430,binding=0)readonly buffer Gray{uint gray[];};
layout(std430,binding=1)readonly buffer RGB{uint rgba[];};
layout(std430,binding=2)buffer Error{int errors[];};
layout(std430,binding=3)buffer Output{uint outputGray[];};
layout(std430,binding=4)buffer History{int history[];};
layout(std430,binding=5)buffer Proof{uint violations;};
layout(std430,binding=6)buffer Ring{int ring[];};
uniform uint width;uniform uint height;uniform uint rowIndex;uniform uint rowsPerDispatch;uniform uint algorithm;
shared int cache[4*CAPACITY];shared uint unchanged[(CAPACITY+31)/32];
shared uint functions[64*32];shared int carry;
bool stable(uint x){return (unchanged[x>>5u]&(1u<<(x&31u)))!=0u;}
int apply(uint pos,int value){uint c=uint(value+64),word=functions[pos*32u+c/4u];return int((word>>((c&3u)*8u))&255u)-64;}
int errorOf(int value){return value>127?value-255:value;}
int transfer(uint pos,int value){int e=errorOf(value);return pos%3u==2u?(algorithm==1u?(5*e)>>6:((5*e)>>4)/2):(5*e)>>4;}
void main(){uint lane=gl_LocalInvocationID.x;
if(width>uint(CAPACITY)||width%4u!=0u||height<4u)return;
for(uint a=lane;a<4u*width;a+=128u){
    if(rowIndex==0u)cache[a]=int((gray[a>>2u]>>((a&3u)*8u))&255u);
    else cache[a]=ring[a];
}barrier();
for(uint y=rowIndex;y<min(rowIndex+rowsPerDispatch,height);y++){
uint base=(y&3u)*width;bool reverse=(y&1u)!=0u;int d=reverse?-1:1;
for(uint a=lane;a<(width+31u)/32u;a+=128u)unchanged[a]=0u;
if(lane==0u)carry=0;barrier();
if(algorithm==1u){for(uint x=lane;x<width;x+=128u){uint i=y*width+x,color=rgba[i];
    int r=int(color&255u),g=int((color>>8u)&255u),b=int((color>>16u)&255u);
    int delta=abs(r-history[4u*i])+abs(g-history[4u*i+1u])+abs(b-history[4u*i+2u]);
    if(delta<10){cache[base+x]=history[4u*i+3u];atomicOr(unchanged[x>>5u],1u<<(x&31u));}
    else{history[4u*i]=r;history[4u*i+1u]=g;history[4u*i+2u]=b;}
}}barrier();
for(uint start=0u;start<width;start+=64u){uint count=min(64u,width-start);
    for(uint a=lane;a<64u*32u;a+=128u){uint p=a/32u,q=a%32u,word=0u,pos=start+p;
        uint x=reverse?width-1u-min(pos,width-1u):min(pos,width-1u);
        for(uint k=0u;k<4u;k++){int incoming=int(q*4u+k)-64,next=incoming;
            if(p<count){int value=cache[base+x]+(stable(x)?0:incoming);
                next=pos+1u==width?0:transfer(pos,value);
                if(next < -64 || next > 63){atomicAdd(violations,1u);next=clamp(next,-64,63);}}
            word|=uint(next+64)<<(k*8u);
        }functions[a]=word;
    }barrier();
    // Each lane retains its replacement word across the read/write barrier;
    // no function can observe a partially updated predecessor in this step.
    for(uint step=1u;step<64u;step*=2u){
        // All 16 words assigned to a lane are computed before any store.
        uint replacement[16];
        for(uint j=0u;j<16u;j++){uint a=lane+j*128u,p=a/32u,q=a%32u,word=functions[a];
            if(p>=step){uint left=functions[(p-step)*32u+q];word=0u;
                for(uint k=0u;k<4u;k++){int previous=int((left>>(k*8u))&255u)-64;
                    word|=uint(apply(p,previous)+64)<<(k*8u);}}
            replacement[j]=word;
        }barrier();
        for(uint j=0u;j<16u;j++)functions[lane+j*128u]=replacement[j];barrier();
    }
    // Four consecutive scan pixels form one packed output word in either
    // direction. No shared packed output is modified by multiple lanes.
    for(uint g=lane;g<count/4u;g+=128u){uint word=0u;
        for(uint k=0u;k<4u;k++){uint p=g*4u+k,pos=start+p,x=reverse?width-1u-pos:pos;
            uint i=y*width+x,a=base+x;int incoming=p==0u?carry:apply(p-1u,carry);
            int value=cache[a]+(stable(x)?0:incoming),e=errorOf(value);
            errors[i]=value;uint shift=reverse?(3u-k)*8u:k*8u;
            word|=(value>127?255u:0u)<<shift;
            if(algorithm==1u&&!stable(x))history[4u*i+3u]=value;
            int small=e>>4,large=(7*e)>>4,three=(3*e)>>4;
            bool special=pos%3u==2u;int extra=algorithm==1u?(5*e)>>6:((5*e)>>4)/2;
            if(y<height-3u){for(uint row=1u;row<=3u;row++){
                uint j=((y+row)&3u)*width+x;int self=large/3;
                if(algorithm==1u&&reverse&&special&&row==2u)self=(7*e)>>6;
                atomicAdd(cache[j],self);
                if(pos<width-1u&&(pos&1u)==0u){int v=small/3;if(special&&row==1u)v+=extra;atomicAdd(cache[int(j)+d],v);}
                if((pos&1u)!=0u)atomicAdd(cache[int(j)-d],three/3);
            }}
        }
        uint group=reverse?(width-start)/4u-1u-g:start/4u+g;outputGray[y*width/4u+group]=word;
    }barrier();
    if(lane==0u)carry=apply(count-1u,carry);barrier();
}barrier();
for(uint x=lane;x<width;x+=128u){int value=0;
    if(y+4u<height){uint i=(y+4u)*width+x;value=int((gray[i>>2u]>>((i&3u)*8u))&255u);}cache[base+x]=value;
}barrier();
}
for(uint a=lane;a<4u*width;a+=128u)ring[a]=cache[a];
})";
}
// Exact row solver: the carry recurrence is triangular, so every fixed point
// equals the original left-to-right (or right-to-left) result. Rows that have
// not reached a fixed point after 32 iterations are evaluated serially on GPU.
// No speculative history or vertical error is committed before the row solves.
inline std::string convergedCacheShader(unsigned capacity=1440){
    if(!capacity||capacity%4)throw std::invalid_argument("color converged capacity");
    return "#version 310 es\n#define CAPACITY "+std::to_string(capacity)+R"(
precision highp int;
layout(local_size_x=128)in;
layout(std430,binding=0)readonly buffer Gray{uint gray[];};
layout(std430,binding=1)readonly buffer RGB{uint rgba[];};
layout(std430,binding=2)buffer Error{int errors[];};
layout(std430,binding=3)buffer Output{uint outputGray[];};
layout(std430,binding=4)buffer History{int history[];};
layout(std430,binding=5)buffer Proof{uint serialRows;};
layout(std430,binding=6)buffer Ring{int ring[];};
uniform uint width;uniform uint height;uniform uint rowIndex;uniform uint rowsPerDispatch;uniform uint algorithm;
shared int cache[4*CAPACITY];shared int carries[CAPACITY];
shared uint unchanged[(CAPACITY+31)/32];shared uint changed;
bool stable(uint x){return (unchanged[x>>5u]&(1u<<(x&31u)))!=0u;}
int errorOf(int value){return value>127?value-255:value;}
int transfer(uint pos,int value){int e=errorOf(value);return pos%3u==2u?(algorithm==1u?(5*e)>>6:((5*e)>>4)/2):(5*e)>>4;}
void main(){uint lane=gl_LocalInvocationID.x;
if(width>uint(CAPACITY)||width%4u!=0u||height<4u)return;
for(uint a=lane;a<4u*width;a+=128u)cache[a]=rowIndex==0u?int((gray[a>>2u]>>((a&3u)*8u))&255u):ring[a];barrier();
for(uint y=rowIndex;y<min(rowIndex+rowsPerDispatch,height);y++){
uint base=(y&3u)*width;bool reverse=(y&1u)!=0u;int d=reverse?-1:1;
for(uint a=lane;a<(width+31u)/32u;a+=128u)unchanged[a]=0u;
for(uint p=lane;p<width;p+=128u)carries[p]=0;barrier();
if(algorithm==1u){for(uint x=lane;x<width;x+=128u){uint i=y*width+x,color=rgba[i];
    int r=int(color&255u),g=int((color>>8u)&255u),b=int((color>>16u)&255u);
    int delta=abs(r-history[4u*i])+abs(g-history[4u*i+1u])+abs(b-history[4u*i+2u]);
    if(delta<10){cache[base+x]=history[4u*i+3u];atomicOr(unchanged[x>>5u],1u<<(x&31u));}
    else{history[4u*i]=r;history[4u*i+1u]=g;history[4u*i+2u]=b;}
}}barrier();
for(uint iteration=0u;iteration<32u;iteration++){
    if(lane==0u)changed=0u;barrier();
    int replacement[(CAPACITY+127)/128];uint localChanged=0u;
    for(uint j=0u;j<uint((CAPACITY+127)/128);j++){uint p=lane+j*128u;int next=0;
        if(p<width){uint x=reverse?width-1u-p:p;int incoming=p==0u?0:carries[p-1u];
            int value=cache[base+x]+(stable(x)?0:incoming);next=p+1u==width?0:transfer(p,value);
            if(next!=carries[p])localChanged=1u;}
        replacement[j]=next;
    }barrier();
    for(uint j=0u;j<uint((CAPACITY+127)/128);j++){uint p=lane+j*128u;if(p<width)carries[p]=replacement[j];}
    if(localChanged!=0u)atomicOr(changed,1u);barrier();
    // Every lane must snapshot the stop decision before lane zero can reset
    // changed for the next iteration; otherwise lanes can leave the loop at
    // different iterations and deadlock its workgroup barriers.
    bool solved=changed==0u;barrier();if(solved)break;
}
if(changed!=0u){if(lane==0u){atomicAdd(serialRows,1u);int incoming=0;
    for(uint p=0u;p<width;p++){uint x=reverse?width-1u-p:p;int value=cache[base+x]+(stable(x)?0:incoming);
        incoming=p+1u==width?0:transfer(p,value);carries[p]=incoming;}}
}barrier();
for(uint g=lane;g<width/4u;g+=128u){uint word=0u;
    for(uint k=0u;k<4u;k++){uint x=g*4u+k,pos=reverse?width-1u-x:x,i=y*width+x;
        int incoming=pos==0u?0:carries[pos-1u],value=cache[base+x]+(stable(x)?0:incoming),e=errorOf(value);
        errors[i]=value;word|=(value>127?255u:0u)<<(k*8u);
        if(algorithm==1u&&!stable(x))history[4u*i+3u]=value;
        int small=e>>4,large=(7*e)>>4,three=(3*e)>>4;
        bool special=pos%3u==2u;int extra=algorithm==1u?(5*e)>>6:((5*e)>>4)/2;
        if(y<height-3u){for(uint row=1u;row<=3u;row++){
            uint j=((y+row)&3u)*width+x;int self=large/3;
            if(algorithm==1u&&reverse&&special&&row==2u)self=(7*e)>>6;
            atomicAdd(cache[j],self);
            if(pos<width-1u&&(pos&1u)==0u){int v=small/3;if(special&&row==1u)v+=extra;atomicAdd(cache[int(j)+d],v);}
            if((pos&1u)!=0u)atomicAdd(cache[int(j)-d],three/3);
        }}
    }outputGray[y*width/4u+g]=word;
}barrier();
for(uint x=lane;x<width;x+=128u){int value=0;
    if(y+4u<height){uint i=(y+4u)*width+x;value=int((gray[i>>2u]>>((i&3u)*8u))&255u);}cache[base+x]=value;
}barrier();
}
for(uint a=lane;a<4u*width;a+=128u)ring[a]=cache[a];
})";
}
} // namespace EinkColorOps
