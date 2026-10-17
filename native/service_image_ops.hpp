#pragma once
// Independent pixel operations. CPU references are diagnostic only; production
// dispatches consume GPU buffers. No waveform, controller or renderer ownership.
#include <cstdint>
#include <cstddef>
#include <string>
namespace EinkImageOps {
enum class Levels : unsigned { Raw=0, Tone=1, Binary=2, Ordered16=3, Quaternary=4, Nearest16=5 };
struct AutoStats { uint32_t bins[6]; }; // stock x29-0x78 .. x29-0x64
inline unsigned autoBin(uint32_t rgba) {
    uint32_t c=rgba&0x00f0f0f0u;
    if(c==0x000010u||c==0xf0f0e0u)return 3;
    if(c==0x100000u||c==0xe0f0f0u)return 4;
    unsigned r=c&255u,g=(c>>8)&255u,b=(c>>16)&255u;
    return r==g&&g==b?2:0; // bin 1 and bin 5 are zero in this stock loop
}
inline uint8_t cfa(uint32_t rgba,unsigned row) {
    int r=int(rgba&255),g=int((rgba>>8)&255),b=int((rgba>>16)&255);
    int average=(r+g+b)/3,channel=row%3==0?r:row%3==1?g:b;
    int value=3*channel-2*average;
    return uint8_t(value<0?0:value>255?255:value);
}
inline uint32_t packMix(uint32_t old,uint32_t next) {
    uint32_t o0=(old&255)>>1,o1=((old>>8)&255)>>1;
    uint32_t o2=((old>>16)&255)>>1,o3=(old>>24)>>1;
    uint32_t n0=(next&255)>>1,n1=((next>>8)&255)>>1;
    uint32_t n2=((next>>16)&255)>>1,n3=(next>>24)>>1;
    return (o0<<4)|o1|(((n0<<4)|n1)<<8)|(((o2<<4)|o3)<<16)|(((n2<<4)|n3)<<24);
}
// Exact small parameter-table transcription of 0x991d0..0x992d0 / 0x992fc..0x993fc.
// ARM's unsigned wrap/truncation is intentional. It is not Adjust_Y8's table.
inline void gldTone(unsigned contrast,uint8_t*table) {
    unsigned c=contrast<=100?contrast:0;
    for(unsigned i=0;i<256;i+=2) {
        uint32_t a=(400-2*c)*(i/2),b=(110+2*c)*(i/2);
        uint32_t even=c?(i>200?200-c+(uint32_t(-11000)-200*c+b)/55:a/200):i;
        uint32_t odd=c?(i+1>200?200-c+(uint32_t(-10945)-199*c+b)/55:(200-c+a)/200):i+1;
        table[i]=uint8_t(even);table[i+1]=uint8_t(odd);
    }
}
// GPU table construction is optional: the production host may still construct
// the 256-byte control table. Bind 4, dispatch exactly one 64-thread group.
inline const char* toneShader() { return R"(#version 310 es
precision highp int;
layout(local_size_x=64)in;
layout(std430,binding=4)writeonly buffer Tone{uint tone[];};
uniform uint contrast;
uint value(uint i,uint c){
uint a=(400u-2u*c)*(i/2u),b=(110u+2u*c)*(i/2u);
if(c==0u)return i;
if((i&1u)==0u)return i>200u?200u-c+(0xffffd508u-200u*c+b)/55u:a/200u;
return i>200u?200u-c+(0xffffd53fu-199u*c+b)/55u:(200u-c+a)/200u;
}
void main(){uint g=gl_GlobalInvocationID.x;if(g>=64u)return;
uint c=contrast<=100u?contrast:0u,word=0u;
for(uint k=0u;k<4u;k++)word|=(value(g*4u+k,c)&255u)<<(k*8u);
tone[g]=word;})"; }
// Explicit host validation; no watchdog-free full-screen promise for this
// single-invocation serpentine quantizer. 0 disables the reference dispatch.
inline bool stock16SizeAllowed(unsigned w,unsigned h,size_t pixelLimit=65536) {
    return w>=2&&h>=4&&pixelLimit!=0&&size_t(w)*h<=pixelLimit&&
           size_t(w)*h<=size_t(INT32_MAX);
}
// Scalar stock reference, for offline small-image checks. Stock only diffuses
// vertically when y < height-3; odd rows contain asymmetric row-2/row-3 terms.
inline bool stock16Reference(const uint8_t*input,uint8_t*output,int32_t*error,unsigned w,unsigned h) {
    if(w<2||h<4)return false; // stock unsigned height-3 underflows for short images
    for(size_t i=0;i<size_t(w)*h;i++)error[i]=input[i];
    for(unsigned y=0;y<h;y++)for(unsigned pos=0;pos<w;pos++) {
        int direction=(y&1)?-1:1,x=direction>0?int(pos):int(w-1-pos);
        size_t i=size_t(y)*w+unsigned(x);int v=error[i],q=v/16;
        int r=v-(v<0?(v+15)&~15:v&~15),target=r>7?(v>239?255:q*16+16):q*16;
        int e=v-target,small=e>>3,large=(3*e)>>3;
        if(pos<w-1)error[size_t(int64_t(i)+direction)]+=pos%3<=1?small:small/2;
        if(y<h-3) {
            for(unsigned row=1;row<=3;row++) {
                int self=small/3;
                if(direction<0&&pos%3==2&&row==3)self=small/2;
                error[i+size_t(row)*w]+=self;
                if(pos<w-1&&!(pos&1)) {
                    int value=large/3;
                    if(pos%3==2&&((direction>0&&row==1)||(direction<0&&row==2)))value+=small/2;
                    error[size_t(int64_t(i+size_t(row)*w)+direction)]+=value;
                }
                if(pos&1)error[size_t(int64_t(i+size_t(row)*w)-direction)]+=large/3;
            }
        }
        output[i]=uint8_t(target);
    }
    return true;
}
// Exact serial dependency reference on GPU, deliberately opt-in and size-capped
// by the host. It is a correctness prototype, not a full-screen performance claim.
inline const char* stock16Shader() { return R"(#version 310 es
precision highp int;
layout(local_size_x=1)in;
layout(std430,binding=7)readonly buffer Gray{uint gray[];};
layout(std430,binding=4)readonly buffer Tone{uint tone[];};
layout(std430,binding=9)buffer Error{int errors[];};
layout(std430,binding=10)buffer Output{uint outputGray[];};
uniform uint width;uniform uint height;
void main(){uint count=width*height;if(width<2u||height<4u)return;
for(uint i=0u;i<count;i++){uint v=(gray[i>>2u]>>((i&3u)*8u))&255u;
errors[i]=int((tone[v>>2u]>>((v&3u)*8u))&255u);}
for(uint g=0u;g<(count+3u)/4u;g++)outputGray[g]=0u;
for(uint y=0u;y<height;y++)for(uint pos=0u;pos<width;pos++){
int d=(y&1u)==0u?1:-1,x=d>0?int(pos):int(width-1u-pos),i=int(y*width)+x;
int v=errors[i],q=v/16,r=v-(v<0?(v+15)&~15:v&~15);
int target=r>7?(v>239?255:q*16+16):q*16;
int e=v-target,s=e>>3,l=(3*e)>>3;
if(pos<width-1u)errors[i+d]+=pos%3u<=1u?s:s/2;
if(y<height-3u)for(uint row=1u;row<=3u;row++){
int j=i+int(row*width),self=s/3;
if(d<0&&pos%3u==2u&&row==3u)self=s/2;errors[j]+=self;
if(pos<width-1u&&(pos&1u)==0u){int value=l/3;
if(pos%3u==2u&&((d>0&&row==1u)||(d<0&&row==2u)))value+=s/2;
errors[j+d]+=value;}
if((pos&1u)!=0u)errors[j-d]+=l/3;}
outputGray[uint(i)>>2u]|=(uint(target)&255u)<<((uint(i)&3u)*8u);}})"; }
// Parallel initialization + bounded serial continuation. This avoids a single
// unbounded shader loop, not the stock quantizer's pixel dependency. No tiling,
// restart-at-row or approximate diffusion: errors survive all dispatches.
inline const char* stock16InitShader() { return R"(#version 310 es
precision highp int;
layout(local_size_x=128)in;
layout(std430,binding=7)readonly buffer Gray{uint gray[];};
layout(std430,binding=4)readonly buffer Tone{uint tone[];};
layout(std430,binding=9)writeonly buffer Error{int errors[];};
layout(std430,binding=10)writeonly buffer Output{uint outputGray[];};
uniform uint count;
void main(){uint g=gl_GlobalInvocationID.x;if(g>=(count+3u)/4u)return;
uint word=gray[g];outputGray[g]=0u;
for(uint k=0u;k<4u;k++){uint i=g*4u+k;if(i>=count)break;
uint v=(word>>(k*8u))&255u;errors[i]=int((tone[v>>2u]>>((v&3u)*8u))&255u);}})"; }
inline const char* stock16ChunkShader() { return R"(#version 310 es
precision highp int;
layout(local_size_x=1)in;
layout(std430,binding=9)buffer Error{int errors[];};
layout(std430,binding=10)buffer Output{uint outputGray[];};
uniform uint width;uniform uint height;uniform uint beginPixel;uniform uint endPixel;
void main(){if(width<2u||height<4u)return;
uint end=min(endPixel,width*height);
for(uint p=beginPixel;p<end;p++){
uint y=p/width,pos=p%width;
int d=(y&1u)==0u?1:-1,x=d>0?int(pos):int(width-1u-pos),i=int(y*width)+x;
int v=errors[i],q=v/16,r=v-(v<0?(v+15)&~15:v&~15);
int target=r>7?(v>239?255:q*16+16):q*16;
int e=v-target,s=e>>3,l=(3*e)>>3;
if(pos<width-1u)errors[i+d]+=pos%3u<=1u?s:s/2;
if(y<height-3u)for(uint row=1u;row<=3u;row++){
int j=i+int(row*width),self=s/3;
if(d<0&&pos%3u==2u&&row==3u)self=s/2;errors[j]+=self;
if(pos<width-1u&&(pos&1u)==0u){int value=l/3;
if(pos%3u==2u&&((d>0&&row==1u)||(d<0&&row==2u)))value+=s/2;
errors[j+d]+=value;}
if((pos&1u)!=0u)errors[j-d]+=l/3;}
outputGray[uint(i)>>2u]|=(uint(target)&255u)<<((uint(i)&3u)*8u);}})"; }
// Four-row circular SSBO scratch; only the first four rows are initialized.
// Scratch allocation is 4*width*sizeof(int), output is ceil(count/4)*4 bytes.
inline const char* stock16RingInitShader() { return R"(#version 310 es
precision highp int;
layout(local_size_x=128)in;
layout(std430,binding=7)readonly buffer Gray{uint gray[];};
layout(std430,binding=4)readonly buffer Tone{uint tone[];};
layout(std430,binding=9)writeonly buffer Ring{int errors[];};
layout(std430,binding=10)writeonly buffer Output{uint outputGray[];};
uniform uint width;uniform uint count;
void main(){uint g=gl_GlobalInvocationID.x;if(g>=(count+3u)/4u)return;
outputGray[g]=0u;
if(g*4u<4u*width){uint word=gray[g];
for(uint k=0u;k<4u;k++){uint i=g*4u+k;if(i>=4u*width)break;
uint v=(word>>(k*8u))&255u;errors[i]=int((tone[v>>2u]>>((v&3u)*8u))&255u);}}})"; }
// No independent tiles: each dispatch processes exactly one full stock row.
// Shared storage is 16*capacity bytes, with one serial lane and 63 transfer lanes.
// Cache current-row values are replaced by quantized output only after their
// last read; horizontal error survives in a register, future rows stay in cache.
inline std::string stock16RowCacheShader(unsigned capacity=1440) {
    return std::string("#version 310 es\n#define CACHE_WIDTH ")+std::to_string(capacity)+R"(
precision highp int;
layout(local_size_x=64)in;
layout(std430,binding=7)readonly buffer Gray{uint gray[];};
layout(std430,binding=4)readonly buffer Tone{uint tone[];};
layout(std430,binding=9)buffer Ring{int errors[];};
layout(std430,binding=10)buffer Output{uint outputGray[];};
uniform uint width;uniform uint height;uniform uint rowIndex;
shared int cache[4*CACHE_WIDTH];
void main(){if(width<2u||width>uint(CACHE_WIDTH)||height<4u||rowIndex>=height)return;
uint lane=gl_LocalInvocationID.x,y=rowIndex;
for(uint a=lane;a<4u*width;a+=64u){uint r=a/width,x=a%width;
cache[a]=errors[((y+r)&3u)*width+x];}barrier();
if(lane==0u){int d=(y&1u)==0u?1:-1,carry=0;
for(uint pos=0u;pos<width;pos++){
int x=d>0?int(pos):int(width-1u-pos),v=cache[x]+carry,q=v/16;
int rem=v-(v<0?(v+15)&~15:v&~15);
int target=rem>7?(v>239?255:q*16+16):q*16;
int e=v-target,s=e>>3,l=(3*e)>>3;
carry=pos%3u<=1u?s:s/2;
if(y<height-3u)for(uint r=1u;r<=3u;r++){
int j=int(r*width)+x,self=s/3;
if(d<0&&pos%3u==2u&&r==3u)self=s/2;cache[j]+=self;
if(pos<width-1u&&(pos&1u)==0u){int value=l/3;
if(pos%3u==2u&&((d>0&&r==1u)||(d<0&&r==2u)))value+=s/2;
cache[j+d]+=value;}
if((pos&1u)!=0u)cache[j-d]+=l/3;}
cache[x]=target;}}barrier();
// Commit the three future rows unchanged except for exact stock additions.
for(uint a=lane;a<3u*width;a+=64u){uint r=a/width+1u,x=a%width;
errors[((y+r)&3u)*width+x]=cache[r*width+x];}
// The consumed slot becomes row y+4, which has no contributions yet: stock
// only propagates to y+1..y+3. This is lifetime reuse, never an error reset.
for(uint x=lane;x<width;x+=64u){int value=0;
if(y+4u<height){uint i=(y+4u)*width+x,v=(gray[i>>2u]>>((i&3u)*8u))&255u;
value=int((tone[v>>2u]>>((v&3u)*8u))&255u);}
errors[(y&3u)*width+x]=value;}
uint start=y*width,end=start+width,firstWord=start>>2u,lastWord=(end-1u)>>2u;
// One writer per packed word; preserve bytes from adjacent rows when width%4!=0.
for(uint g=firstWord+lane;g<=lastWord;g+=64u){uint word=outputGray[g];
for(uint k=0u;k<4u;k++){uint p=g*4u+k;if(p>=start&&p<end){uint shift=k*8u;
word=(word&~(255u<<shift))|((uint(cache[p-start])&255u)<<shift);}}
outputGray[g]=word;}})";
}
// Same exact four-row ring retained in shared across 1..8 successive rows.
// Global scratch is read/written once per batch; only the consumed slot is
// reloaded from a row beyond the three-row stock propagation horizon.
inline std::string stock16RowBatchCacheShader(unsigned capacity=1440) {
    return std::string("#version 310 es\n#define CACHE_WIDTH ")+std::to_string(capacity)+R"(
precision highp int;
layout(local_size_x=64)in;
layout(std430,binding=7)readonly buffer Gray{uint gray[];};
layout(std430,binding=4)readonly buffer Tone{uint tone[];};
layout(std430,binding=9)buffer Ring{int errors[];};
layout(std430,binding=10)buffer Output{uint outputGray[];};
uniform uint width;uniform uint height;uniform uint rowIndex;uniform uint rowsPerDispatch;
shared int cache[4*CACHE_WIDTH];
void main(){if(width<2u||width>uint(CACHE_WIDTH)||height<4u||rowIndex>=height||
rowsPerDispatch==0u||rowsPerDispatch>8u)return;
uint lane=gl_LocalInvocationID.x;
for(uint a=lane;a<4u*width;a+=64u)cache[a]=errors[a];barrier();
uint end=min(rowIndex+rowsPerDispatch,height);
for(uint y=rowIndex;y<end;y++){
uint base=(y&3u)*width;
if(lane==0u){int d=(y&1u)==0u?1:-1,carry=0;
for(uint pos=0u;pos<width;pos++){
int x=d>0?int(pos):int(width-1u-pos),v=cache[base+uint(x)]+carry,q=v/16;
int rem=v-(v<0?(v+15)&~15:v&~15);
int target=rem>7?(v>239?255:q*16+16):q*16;
int e=v-target,s=e>>3,l=(3*e)>>3;
carry=pos%3u<=1u?s:s/2;
if(y<height-3u)for(uint r=1u;r<=3u;r++){
int j=int(((y+r)&3u)*width)+x,self=s/3;
if(d<0&&pos%3u==2u&&r==3u)self=s/2;cache[j]+=self;
if(pos<width-1u&&(pos&1u)==0u){int value=l/3;
if(pos%3u==2u&&((d>0&&r==1u)||(d<0&&r==2u)))value+=s/2;
cache[j+d]+=value;}
if((pos&1u)!=0u)cache[j-d]+=l/3;}
cache[base+uint(x)]=target;}}barrier();
uint start=y*width,stop=start+width,firstWord=start>>2u,lastWord=(stop-1u)>>2u;
for(uint g=firstWord+lane;g<=lastWord;g+=64u){uint word=outputGray[g];
for(uint k=0u;k<4u;k++){uint p=g*4u+k;if(p>=start&&p<stop){uint shift=k*8u;
word=(word&~(255u<<shift))|((uint(cache[base+p-start])&255u)<<shift);}}
outputGray[g]=word;}
// Complete all packed-word reads before any lane reuses the consumed row.
memoryBarrierBuffer();barrier();
for(uint x=lane;x<width;x+=64u){int value=0;
if(y+4u<height){uint i=(y+4u)*width+x,v=(gray[i>>2u]>>((i&3u)*8u))&255u;
value=int((tone[v>>2u]>>((v&3u)*8u))&255u);}
cache[base+x]=value;}barrier();
}
for(uint a=lane;a<4u*width;a+=64u)errors[a]=cache[a];})";
}
// Certified domain: tone maps to uint8, row's accumulated vertical contribution
// is [-3,0], incoming horizontal carry is {-1,0}. Stock error stays [-8,7], so
// s/3 and s/2 are always 0; each earlier row contributes at most one {-1,0}
// neighbor. This closes the invariant for any width>=2,height>=4 frame.
// Exact two-state function composition, not a tile/reset approximation.
inline std::string stock16PrefixCacheShader(unsigned capacity=1440) {
    unsigned scan=1;while(scan<capacity)scan*=2;
    return std::string("#version 310 es\n#define CACHE_WIDTH ")+std::to_string(capacity)+
        "\n#define SCAN_SIZE "+std::to_string(scan)+R"(
precision highp int;
layout(local_size_x=128)in;
layout(std430,binding=7)readonly buffer Gray{uint gray[];};
layout(std430,binding=4)readonly buffer Tone{uint tone[];};
layout(std430,binding=9)buffer Ring{int errors[];};
layout(std430,binding=10)buffer Output{uint outputGray[];};
layout(std430,binding=8)buffer Domain{uint violations;};
uniform uint width;uniform uint height;uniform uint rowIndex;uniform uint rowsPerDispatch;
shared int cache[4*CACHE_WIDTH];shared uint functions[SCAN_SIZE];
int quant(int v){int q=v/16,r=v-(v<0?(v+15)&~15:v&~15);
return r>7?(v>239?255:q*16+16):q*16;}
uint apply(uint f,uint state){return (f>>state)&1u;}
uint compose(uint left,uint right){return apply(right,apply(left,0u))|(apply(right,apply(left,1u))<<1u);}
uint nextState(int v,uint pos){int e=v-quant(v),s=e>>3;
return uint((pos%3u<=1u?s:s/2)+1);}
void main(){if(width<2u||width>uint(CACHE_WIDTH)||height<4u||rowIndex>=height||
rowsPerDispatch==0u||rowsPerDispatch>8u)return;
uint lane=gl_LocalInvocationID.x,n=1u;while(n<width)n*=2u;
for(uint a=lane;a<4u*width;a+=128u)cache[a]=errors[a];barrier();
uint end=min(rowIndex+rowsPerDispatch,height);
for(uint y=rowIndex;y<end;y++){
uint base=(y&3u)*width;int d=(y&1u)==0u?1:-1;
for(uint pos=lane;pos<n;pos+=128u){uint f=2u;
if(pos<width){uint x=d>0?pos:width-1u-pos;int v=cache[base+x];
if(v < -3 || v > 255)atomicAdd(violations,1u);
f=nextState(v-1,pos)|(nextState(v,pos)<<1u);}functions[pos]=f;}barrier();
// Exclusive Blelloch scan over associative ordered function composition.
for(uint stride=2u;stride<=n;stride*=2u){
for(uint j=(lane+1u)*stride-1u;j<n;j+=128u*stride)
functions[j]=compose(functions[j-stride/2u],functions[j]);barrier();}
if(lane==0u)functions[n-1u]=2u;barrier();
for(uint stride=n;stride>=2u;stride/=2u){
for(uint j=(lane+1u)*stride-1u;j<n;j+=128u*stride){uint left=functions[j-stride/2u],before=functions[j];
functions[j-stride/2u]=before;functions[j]=compose(before,left);}barrier();}
for(uint pos=lane;pos<width;pos+=128u){int x=d>0?int(pos):int(width-1u-pos);
int v=cache[base+uint(x)]+int(apply(functions[pos],1u))-1,target=quant(v),e=v-target;
int coefficient=((3*e)>>3)/3;
// All self/asymmetric terms are exactly zero in the certified input domain.
if(y<height-3u&&coefficient!=0){int neighbor=-1;
if((pos&1u)!=0u)neighbor=x-d;
else if(pos<width-1u)neighbor=x+d;
if(neighbor>=0)for(uint r=1u;r<=3u;r++)atomicAdd(cache[((y+r)&3u)*width+uint(neighbor)],coefficient);}
cache[base+uint(x)]=target;}barrier();
uint start=y*width,stop=start+width,firstWord=start>>2u,lastWord=(stop-1u)>>2u;
for(uint g=firstWord+lane;g<=lastWord;g+=128u){uint word=outputGray[g];
for(uint k=0u;k<4u;k++){uint p=g*4u+k;if(p>=start&&p<stop){uint shift=k*8u;
word=(word&~(255u<<shift))|((uint(cache[base+p-start])&255u)<<shift);}}
outputGray[g]=word;}memoryBarrierBuffer();barrier();
for(uint x=lane;x<width;x+=128u){int value=0;
if(y+4u<height){uint i=(y+4u)*width+x,v=(gray[i>>2u]>>((i&3u)*8u))&255u;
value=int((tone[v>>2u]>>((v&3u)*8u))&255u);}cache[base+x]=value;}barrier();
}
for(uint a=lane;a<4u*width;a+=128u)errors[a]=cache[a];})";
}
inline const char* statsShader() { return R"(#version 310 es
precision highp float;precision highp int;
layout(local_size_x=128)in;
uniform highp sampler2D source;
uniform uint count;uniform uint textureWidth;
layout(std430,binding=8)buffer Statistics{uint bins[6];};
shared uint localBins[6];
uint classify(uvec3 c){c&=uvec3(240u);uint v=c.r|(c.g<<8u)|(c.b<<16u);
if(v==0x10u||v==0xf0f0e0u)return 3u;
if(v==0x100000u||v==0xe0f0f0u)return 4u;
return c.r==c.g&&c.g==c.b?2u:0u;}
void main(){uint t=gl_LocalInvocationID.x;if(t<6u)localBins[t]=0u;barrier();
uint p=gl_GlobalInvocationID.x;
if(p<count){uvec3 c=uvec3(texelFetch(source,ivec2(int(p%textureWidth),int(p/textureWidth)),0).rgb*255.0+0.5);
atomicAdd(localBins[classify(c)],1u);}barrier();
if(t<6u)atomicAdd(bins[t],localBins[t]);})"; }
// Materialise only when stock CPU consumers still need RGBA. This bridge costs
// a full GPU readback and CPU copy, but never a CPU transpose or GPU RGBA upload.
inline const char* workShader() { return R"(#version 310 es
precision highp float;precision highp int;
layout(local_size_x=128)in;
uniform highp sampler2D source;
uniform uint width;uniform uint height;uniform uint textureWidth;
layout(std430,binding=3)writeonly buffer Work{uint rgba[];};
void main(){uint p=gl_GlobalInvocationID.x;if(p>=width*height)return;
uint i=(p%width)*height+p/width;
uvec4 c=uvec4(texelFetch(source,ivec2(int(i%textureWidth),int(i/textureWidth)),0)*255.0+0.5);
rgba[p]=c.r|(c.g<<8u)|(c.b<<16u)|(c.a<<24u);})"; }
inline const char* levelsShader() { return R"(#version 310 es
precision highp int;
layout(local_size_x=128)in;
layout(std430,binding=7)readonly buffer Gray{uint gray[];};
layout(std430,binding=4)readonly buffer Tone{uint tone[];};
layout(std430,binding=6)readonly buffer Old{uint oldLevels[];};
layout(std430,binding=5)writeonly buffer New{uint newLevels[];};
layout(std430,binding=0)writeonly buffer Mix{uint mixed[];};
uniform uint count;uniform uint width;uniform uint kind;
void main(){uint g=gl_GlobalInvocationID.x;if(g>=(count+3u)/4u)return;
uint next=0u,old=oldLevels[g],gw=gray[g];
for(uint k=0u;k<4u;k++){uint i=g*4u+k;if(i>=count)break;
uint v=(gw>>(k*8u))&255u;
if(kind!=0u)v=(tone[v>>2u]>>((v&3u)*8u))&255u;
if(kind==2u)v=v>=128u?255u:0u;
if(kind==3u){uint x=i%width,y=i/width,z=x^y;
uint b=((x&1u)<<4u)|((z&1u)<<5u)|((x&2u)<<1u)|((z&2u)<<2u)|((x&4u)>>2u)|((z&4u)>>1u);
// Explicit alternative algorithm; this is NOT stock DitherSStyle16.
uint q=v>>4u;if(q<15u&&((v&15u)*64u)>b*16u+8u)q++;v=q*16u;}
if(kind==4u){uint q=min((v+32u)>>6u,3u);v=q*80u;}
if(kind==5u)v=min((v+8u)>>4u,15u)*16u;
next|=((v>>3u)&30u)<<(k*8u);}
newLevels[g]=next;
uint o0=(old&255u)>>1u,o1=((old>>8u)&255u)>>1u,o2=((old>>16u)&255u)>>1u,o3=(old>>24u)>>1u;
uint n0=(next&255u)>>1u,n1=((next>>8u)&255u)>>1u,n2=((next>>16u)&255u)>>1u,n3=(next>>24u)>>1u;
mixed[g]=(o0<<4u)|o1|(((n0<<4u)|n1)<<8u)|(((o2<<4u)|o3)<<16u)|(((n2<<4u)|n3)<<24u);})"; }
inline const char* cfaShader() { return R"(#version 310 es
precision highp int;
layout(local_size_x=128)in;
layout(std430,binding=3)readonly buffer RGBA{uint rgba[];};
layout(std430,binding=7)writeonly buffer CFA{uint gray[];};
uniform uint width;uniform uint count;
void main(){uint g=gl_GlobalInvocationID.x;if(g>=(count+3u)/4u)return;uint word=0u;
for(uint k=0u;k<4u;k++){uint i=g*4u+k;if(i>=count)break;uint c=rgba[i];
int r=int(c&255u),v=int((c>>8u)&255u),b=int((c>>16u)&255u),average=(r+v+b)/3;
uint row=(i/width)%3u;int channel=row==0u?r:row==1u?v:b;
word|=uint(clamp(3*channel-2*average,0,255))<<(k*8u);}gray[g]=word;})"; }
#ifndef EINK_IMAGE_OPS_REFERENCE_ONLY
inline GLuint link(const char*source) {
    GLuint s=compile(GL_COMPUTE_SHADER,source),p=glCreateProgram();
    glAttachShader(p,s);glLinkProgram(p);glDeleteShader(s);
    GLint ok=0;glGetProgramiv(p,GL_LINK_STATUS,&ok);
    if(!ok){char log[2048];glGetProgramInfoLog(p,sizeof(log),nullptr,log);glDeleteProgram(p);throw std::runtime_error(log);}
    return p;
}
inline void uniform(GLuint p,const char*name,unsigned value) {glUniform1ui(glGetUniformLocation(p,name),value);}
inline void dispatchLevels(GLuint p,GLuint gray,GLuint tone,GLuint old,GLuint next,GLuint mixed,
                           unsigned count,unsigned width,Levels kind) {
    if(!width||!count)throw std::invalid_argument("image ops require nonzero width/count");
    glUseProgram(p);uniform(p,"count",count);uniform(p,"width",width);uniform(p,"kind",unsigned(kind));
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,gray);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,4,tone);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,6,old);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,5,next);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,mixed);
    glDispatchCompute((count+511)/512,1,1);
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
}
inline void dispatchStock16(GLuint p,GLuint gray,GLuint tone,GLuint error,GLuint output,
                            unsigned w,unsigned h,size_t pixelLimit=16384) {
    // The old single loop fails the 65536-pixel Mesa bytecode comparison.
    if(!stock16SizeAllowed(w,h,pixelLimit>16384?16384:pixelLimit))
        throw std::invalid_argument("legacy serial stock16 size limit (16384)");
    glUseProgram(p);uniform(p,"width",w);uniform(p,"height",h);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,gray);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,4,tone);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,9,error);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,10,output);
    glDispatchCompute(1,1,1);
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
}
inline void dispatchStock16Chunks(GLuint initProgram,GLuint chunkProgram,GLuint gray,GLuint tone,
                                 GLuint error,GLuint output,unsigned w,unsigned h,
                                 size_t pixelLimit=65536,unsigned chunkPixels=4096) {
    if(!stock16SizeAllowed(w,h,pixelLimit)||!chunkPixels||chunkPixels>4096)
        throw std::invalid_argument("stock16 chunk/size limit");
    const unsigned count=w*h;
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,gray);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,4,tone);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,9,error);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,10,output);
    glUseProgram(initProgram);uniform(initProgram,"count",count);
    glDispatchCompute((count+511)/512,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    glUseProgram(chunkProgram);uniform(chunkProgram,"width",w);uniform(chunkProgram,"height",h);
    for(unsigned begin=0;begin<count;begin+=chunkPixels) {
        uniform(chunkProgram,"beginPixel",begin);uniform(chunkProgram,"endPixel",begin+chunkPixels<count?begin+chunkPixels:count);
        glDispatchCompute(1,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
}
inline void dispatchStock16Rows(GLuint initProgram,GLuint rowProgram,GLuint gray,GLuint tone,
                               GLuint ring,GLuint output,unsigned w,unsigned h,
                               size_t pixelLimit=65536,unsigned shaderCapacity=1440) {
    GLint maxShared=0;glGetIntegerv(GL_MAX_COMPUTE_SHARED_MEMORY_SIZE,&maxShared);
    if(!stock16SizeAllowed(w,h,pixelLimit)||w>shaderCapacity||!shaderCapacity||
       size_t(shaderCapacity)*16>size_t(maxShared))
        throw std::invalid_argument("stock16 row cache size/shared budget");
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,gray);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,4,tone);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,9,ring);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,10,output);
    glUseProgram(initProgram);uniform(initProgram,"width",w);uniform(initProgram,"count",w*h);
    glDispatchCompute((w*h+511)/512,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    glUseProgram(rowProgram);uniform(rowProgram,"width",w);uniform(rowProgram,"height",h);
    for(unsigned y=0;y<h;y++) {
        uniform(rowProgram,"rowIndex",y);glDispatchCompute(1,1,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
}
inline void dispatchStock16RowBatches(GLuint initProgram,GLuint batchProgram,GLuint gray,GLuint tone,
                                     GLuint ring,GLuint output,unsigned w,unsigned h,
                                     size_t pixelLimit=65536,unsigned rowsPerDispatch=4,
                                     unsigned shaderCapacity=1440) {
    GLint maxShared=0;glGetIntegerv(GL_MAX_COMPUTE_SHARED_MEMORY_SIZE,&maxShared);
    if(!stock16SizeAllowed(w,h,pixelLimit)||w>shaderCapacity||!shaderCapacity||
       rowsPerDispatch<1||rowsPerDispatch>8||size_t(shaderCapacity)*16>size_t(maxShared))
        throw std::invalid_argument("stock16 batch cache size/shared budget");
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,gray);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,4,tone);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,9,ring);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,10,output);
    glUseProgram(initProgram);uniform(initProgram,"width",w);uniform(initProgram,"count",w*h);
    glDispatchCompute((w*h+511)/512,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    glUseProgram(batchProgram);uniform(batchProgram,"width",w);uniform(batchProgram,"height",h);
    uniform(batchProgram,"rowsPerDispatch",rowsPerDispatch);
    for(unsigned y=0;y<h;y+=rowsPerDispatch) {
        uniform(batchProgram,"rowIndex",y);glDispatchCompute(1,1,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
}
inline void dispatchStock16Prefix(GLuint initProgram,GLuint prefixProgram,GLuint gray,GLuint tone,
                                 GLuint ring,GLuint output,GLuint proof,unsigned w,unsigned h,
                                 size_t pixelLimit=65536,unsigned rowsPerDispatch=4,
                                 unsigned shaderCapacity=1440) {
    GLint maxShared=0;glGetIntegerv(GL_MAX_COMPUTE_SHARED_MEMORY_SIZE,&maxShared);
    unsigned scan=1;while(scan<shaderCapacity)scan*=2;
    if(!stock16SizeAllowed(w,h,pixelLimit)||w>shaderCapacity||!shaderCapacity||
       rowsPerDispatch<1||rowsPerDispatch>8||size_t(shaderCapacity)*16+size_t(scan)*4>size_t(maxShared))
        throw std::invalid_argument("stock16 prefix size/shared budget");
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,proof);uint32_t zero=0;
    glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,sizeof(zero),&zero);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,8,proof);
    dispatchStock16RowBatches(initProgram,prefixProgram,gray,tone,ring,output,w,h,
                             pixelLimit,rowsPerDispatch,shaderCapacity);
}
#endif
} // namespace EinkImageOps
