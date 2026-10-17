#pragma once
// Exact stock video is opt-in through its existing 'stock' property. The blue
// video default retains its existing algorithm. History stays GPU-resident and
// is bridged only before the original CPU video consumer can run.
#include <array>
#include "service_color_ops.hpp"
static std::array<GLuint,7> stockColorBuffers{};
static GLuint stockColorTone,stockColorInit,stockColorRows;
static bool colorHistoryValid,colorHistoryDirty;
static constexpr uintptr_t STOCK_COLOR_HISTORY=0x523ab2c;
static constexpr size_t STOCK_COLOR_HISTORY_BYTES=PIXELS*16;
static void syncCpuColorHistory(bool invalidate){
    if(colorHistoryDirty){
        finishPixels();glBindBuffer(GL_SHADER_STORAGE_BUFFER,stockColorBuffers[3]);
        auto*p=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,STOCK_COLOR_HISTORY_BYTES,GL_MAP_READ_BIT);
        require(p,"video history CPU bridge map");std::memcpy(reinterpret_cast<void*>(base+STOCK_COLOR_HISTORY),p,STOCK_COLOR_HISTORY_BYTES);
        require(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER),"video history CPU bridge unmap");
        telemetryBytes(T_READBACK,STOCK_COLOR_HISTORY_BYTES);colorHistoryDirty=false;
        VLOG("VIDEO_HISTORY_CPU_BRIDGE bytes=%zu",STOCK_COLOR_HISTORY_BYTES);
    }
    if(invalidate)colorHistoryValid=false;
}
static GLuint prepareStockVideo(void*,GLuint gray,GLuint tone,GLuint rgba){
    if(!stockColorRows){
        GLint shared=0;glGetIntegerv(GL_MAX_COMPUTE_SHARED_MEMORY_SIZE,&shared);require(shared>=1440*20+45*4+4,"video shared memory budget");
        const char*map=R"(#version 310 es
precision highp int;layout(local_size_x=128)in;
layout(std430,binding=7)readonly buffer Gray{uint gray[];};
layout(std430,binding=4)readonly buffer Tone{uint tone[];};
layout(std430,binding=0)writeonly buffer Mapped{uint mapped[];};
void main(){uint g=gl_GlobalInvocationID.x;if(g>=259200u)return;uint src=gray[g],word=0u;
for(uint k=0u;k<4u;k++){uint v=(src>>(k*8u))&255u;word|=((tone[v>>2u]>>((v&3u)*8u))&255u)<<(k*8u);}mapped[g]=word;})";
        stockColorTone=EinkImageOps::link(map);stockColorInit=EinkImageOps::link(EinkColorOps::initShader());
        const auto rows=EinkColorOps::convergedCacheShader();stockColorRows=EinkImageOps::link(rows.c_str());
        glGenBuffers(stockColorBuffers.size(),stockColorBuffers.data());
        const size_t sizes[]={PIXELS,PIXELS*4,PIXELS,STOCK_COLOR_HISTORY_BYTES,4,1440*16,STOCK_COLOR_HISTORY_BYTES};
        for(size_t i=0;i<stockColorBuffers.size();i++){glBindBuffer(GL_SHADER_STORAGE_BUFFER,stockColorBuffers[i]);glBufferData(GL_SHADER_STORAGE_BUFFER,sizes[i],nullptr,GL_DYNAMIC_COPY);}
    }
    auto&b=stockColorBuffers;
    if(!colorHistoryValid){upload(b[3],reinterpret_cast<const void*>(base+STOCK_COLOR_HISTORY),STOCK_COLOR_HISTORY_BYTES,4);
        telemetryBytes(T_UPLOAD,STOCK_COLOR_HISTORY_BYTES);finishPixels();colorHistoryValid=true;}
    // Keep a GPU-only pre-wave checkpoint for failure recovery. Speculative
    // history from an incomplete dispatch must never become the CPU mirror.
    glBindBuffer(GL_COPY_READ_BUFFER,b[3]);glBindBuffer(GL_COPY_WRITE_BUFFER,b[6]);
    glCopyBufferSubData(GL_COPY_READ_BUFFER,GL_COPY_WRITE_BUFFER,0,0,STOCK_COLOR_HISTORY_BYTES);
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);finishPixels();
    bool oldDirty=colorHistoryDirty;
    try{
        glUseProgram(stockColorTone);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,gray);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,4,tone);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,b[0]);
        glDispatchCompute((PIXELS+511)/512,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        uint32_t zero=0;glBindBuffer(GL_SHADER_STORAGE_BUFFER,b[4]);glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,4,&zero);
        GLuint binding[]={b[0],rgba,b[1],b[2],b[3],b[4],b[5]};
        for(unsigned i=0;i<7;i++)glBindBufferBase(GL_SHADER_STORAGE_BUFFER,i,binding[i]);
        glUseProgram(stockColorInit);EinkImageOps::uniform(stockColorInit,"count",PIXELS);
        glDispatchCompute((PIXELS+511)/512,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        glUseProgram(stockColorRows);EinkImageOps::uniform(stockColorRows,"width",1440);EinkImageOps::uniform(stockColorRows,"height",720);
        EinkImageOps::uniform(stockColorRows,"algorithm",1);EinkImageOps::uniform(stockColorRows,"rowsPerDispatch",8);
        for(unsigned row=0;row<720;row+=8){EinkImageOps::uniform(stockColorRows,"rowIndex",row);glDispatchCompute(1,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);}
        finishPixels();colorHistoryDirty=true;
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,b[4]);auto*p=static_cast<const uint32_t*>(glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,4,GL_MAP_READ_BIT));
        require(p,"video row proof map");uint32_t slow=*p;require(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER),"video row proof unmap");
        VLOG("STOCK_VIDEO_GPU wave=%llu pixels=%zu serial_rows=%u",(unsigned long long)waveId,PIXELS,slow);return b[2];
    }catch(...){
        glBindBuffer(GL_COPY_READ_BUFFER,b[6]);glBindBuffer(GL_COPY_WRITE_BUFFER,b[3]);
        glCopyBufferSubData(GL_COPY_READ_BUFFER,GL_COPY_WRITE_BUFFER,0,0,STOCK_COLOR_HISTORY_BYTES);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);finishPixels();colorHistoryDirty=oldDirty;throw;
    }
}
