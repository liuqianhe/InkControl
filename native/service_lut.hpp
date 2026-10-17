#pragma once
// Exact live-table expansion for the two layouts used by the controlled WBF
// pipeline. Input is the stock-decoded 1024-byte raw frame, not a CPU-expanded
// 65536-byte lookup table. Original WBF bytes and temperature selection stay intact.
namespace einklut {
inline constexpr const char* BuildShader=R"(#version 310 es
precision highp int;layout(local_size_x=128)in;
layout(std430,binding=1)readonly buffer Raw{uint rawWave[];};
layout(std430,binding=2)writeonly buffer Expanded{uint expanded[];};
uniform uint rawFrames;uniform uint frames;uniform uint regal;
uint rawAt(uint i){return(rawWave[i>>2u]>>((i&3u)*8u))&255u;}
uint driveCode(uint v){return v==1u?1u:v==2u?2u:0u;}
uint entry(uint index,uint f){
if(f>=rawFrames)return 0u;
uint base=f*1024u;
if(regal!=0u){uint old=index&31u,next=(index>>8u)&31u;
    return old==next?0u:driveCode(rawAt(base+old*32u+next));}
uint left=driveCode(rawAt(base+((index>>4u)&15u)*64u+((index>>12u)&15u)*2u));
uint right=driveCode(rawAt(base+(index&15u)*64u+((index>>8u)&15u)*2u));
return(left<<2u)|right;}
void main(){uint g=gl_GlobalInvocationID.x;if(g>=frames*16384u)return;
uint frame=g/16384u,index=(g%16384u)*4u,word=0u;
for(uint k=0u;k<4u;k++)word|=entry(index+k,frame)<<(k*8u);expanded[g]=word;})";

class Builder {
    GLuint program_=0,raw_=0,table_=0;size_t bytes_=0;
public:
    GLuint build(const uint8_t*raw,uint32_t rawFrames,uint32_t frames,bool regal){
        if(!raw||!rawFrames||frames<rawFrames||frames>rawFrames+1||frames>511)
            throw std::runtime_error("GPU LUT dimensions/dispatch range");
        if(!program_)program_=einkwave::linkShader(GL_COMPUTE_SHADER,BuildShader);
        if(!raw_){glGenBuffers(1,&raw_);glGenBuffers(1,&table_);}
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,raw_);glBufferData(GL_SHADER_STORAGE_BUFFER,size_t(rawFrames)*1024,raw,GL_STATIC_DRAW);
        bytes_=size_t(frames)*65536;glBindBuffer(GL_SHADER_STORAGE_BUFFER,table_);
        glBufferData(GL_SHADER_STORAGE_BUFFER,bytes_,nullptr,GL_DYNAMIC_COPY);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,raw_);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,table_);
        glUseProgram(program_);glUniform1ui(glGetUniformLocation(program_,"rawFrames"),rawFrames);
        glUniform1ui(glGetUniformLocation(program_,"frames"),frames);glUniform1ui(glGetUniformLocation(program_,"regal"),regal);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
        glDispatchCompute(frames*128,1,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);einkwave::check("GPU LUT expansion");
        return table_;
    }
    size_t bytes()const{return bytes_;}
};
}
