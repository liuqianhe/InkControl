#pragma once
#include "service_lut.hpp"
#include <vector>
#include <map>

// Stock GetWF owns mode/temperature/RLE decode and table allocations. Replace
// only its three verified expansion calls. A separate, serialized GL context
// handles initialization callers; restore their previous context on every exit.
namespace einklutruntime {
struct Record {
    std::vector<uint8_t> raw;uint32_t rawFrames=0,frames=0;bool regal=false;uint64_t revision=0;
};
static std::mutex mutex;
static std::map<const uint8_t*,Record> records;
static uint64_t revision;
static EGLDisplay display=EGL_NO_DISPLAY;
static EGLContext context=EGL_NO_CONTEXT;
static EGLSurface surface=EGL_NO_SURFACE;
static einklut::Builder compatibilityBuilder,producerBuilder;
static const uint8_t*producerKey;
static uint64_t producerRevision;
static GLuint producerTable;
struct ContextRestore {
    EGLDisplay oldDisplay=eglGetCurrentDisplay();EGLContext oldContext=eglGetCurrentContext();
    EGLSurface draw=eglGetCurrentSurface(EGL_DRAW),read=eglGetCurrentSurface(EGL_READ);
    bool switched=false;
    ~ContextRestore(){if(switched){
        bool ok=oldContext==EGL_NO_CONTEXT?eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT):
            eglMakeCurrent(oldDisplay,draw,read,oldContext);
        if(!ok){telemetryAdd(T_ERRORS);gpuFailed=true;LOG("GPU_ERROR LUT caller context restoration");}
    }}
};
static void makeCurrent(ContextRestore&restore){
    if(context==EGL_NO_CONTEXT){
        display=eglGetDisplay(EGL_DEFAULT_DISPLAY);require(eglInitialize(display,nullptr,nullptr),"LUT EGL initialize");
        EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_RED_SIZE,8,
            EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
        EGLConfig cfg;EGLint count=0;require(eglChooseConfig(display,attrs,&cfg,1,&count)&&count,"LUT EGL config");
        EGLint ctx[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE},surf[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
        context=eglCreateContext(display,cfg,EGL_NO_CONTEXT,ctx);require(context!=EGL_NO_CONTEXT,"LUT EGL context");
        surface=eglCreatePbufferSurface(display,cfg,surf);require(surface!=EGL_NO_SURFACE,"LUT EGL surface");
    }
    require(eglMakeCurrent(display,surface,surface,context),"LUT EGL current");restore.switched=true;
}
static bool expand(uint8_t*destination,unsigned stride,const uint8_t*raw,unsigned rawFrames,unsigned padding,bool regal){
    std::lock_guard<std::mutex>lock(mutex);
    records.erase(destination); // Reused address must never select stale GPU data, including CPU fallback.
    if(!gpuMode||stride!=65536||!destination||!raw||!rawFrames||padding>1||rawFrames+padding>511)return false;
    try{
        require(records.size()<128,"LUT registry count budget");
        size_t rawBytes=size_t(rawFrames)*1024,totalRaw=rawBytes;
        for(const auto&item:records)totalRaw+=item.second.raw.size();
        require(totalRaw<=16u*1024u*1024u,"LUT raw registry budget");
        Record r;r.raw.assign(raw,raw+rawBytes);r.rawFrames=rawFrames;r.frames=rawFrames+padding;r.regal=regal;r.revision=++revision;
        ContextRestore restore;makeCurrent(restore);
        GLuint table=compatibilityBuilder.build(r.raw.data(),r.rawFrames,r.frames,r.regal);glFinish();einkwave::check("LUT compatibility finish");
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,table);
        auto*p=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,compatibilityBuilder.bytes(),GL_MAP_READ_BIT);require(p,"LUT compatibility map");
        // Startup/temperature-change compatibility copy: stock's unchanged CPU
        // consumers and recovery retain a complete table. This is not steady scan readback.
        std::memcpy(destination,p,compatibilityBuilder.bytes());require(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER),"LUT compatibility unmap");
        telemetryBytes(T_READBACK,compatibilityBuilder.bytes());records.emplace(destination,std::move(r));
        VLOG("LUT_GPU_EXPAND destination=%p raw=%u frames=%u regal=%d compatibility_bytes=%zu",destination,rawFrames,
            rawFrames+padding,regal,compatibilityBuilder.bytes());return true;
    }catch(const std::exception&e){telemetryAdd(T_ERRORS);LOG("GPU_ERROR LUT expansion fallback reason=%s",e.what());return false;}
}
}
static void hookLutPaired(uint8_t*dst,unsigned stride,const uint8_t*raw,unsigned frames){
    if(einklutruntime::expand(dst,stride,raw,frames,0,false))return;
    reinterpret_cast<void(*)(uint8_t*,unsigned,const uint8_t*,unsigned)>(base+0x960ac)(dst,stride,raw,frames);
}
static void hookLutPadding(uint8_t*dst,unsigned stride,const uint8_t*raw,unsigned frames,unsigned padding){
    if(einklutruntime::expand(dst,stride,raw,frames,padding,false))return;
    reinterpret_cast<void(*)(uint8_t*,unsigned,const uint8_t*,unsigned,unsigned)>(base+0x966b0)(dst,stride,raw,frames,padding);
}
static void hookLutRegal(uint8_t*dst,unsigned stride,const uint8_t*raw,unsigned frames,unsigned padding){
    if(einklutruntime::expand(dst,stride,raw,frames,padding,true))return;
    reinterpret_cast<void(*)(uint8_t*,unsigned,const uint8_t*,unsigned,unsigned)>(base+0x96278)(dst,stride,raw,frames,padding);
}
static unsigned gpuLutFor(const uint8_t*key,uint32_t activeFrames){
    using namespace einklutruntime;
    std::lock_guard<std::mutex>lock(mutex);auto it=records.find(key);
    if(it==records.end())return 0;
    const auto&r=it->second;require(activeFrames<=r.frames,"selected live LUT frame capacity");
    if(key==producerKey&&r.revision==producerRevision)return producerTable;
    // Rebuild in the producer context from small stock-decoded raw constants.
    // The table stays in this context across every rolling window of the wave.
    producerTable=producerBuilder.build(r.raw.data(),r.rawFrames,r.frames,r.regal);
    producerKey=key;producerRevision=r.revision;
    VLOG("LUT_GPU_RESIDENT destination=%p revision=%llu frames=%u raw_bytes=%zu",key,
        (unsigned long long)r.revision,r.frames,r.raw.size());return producerTable;
}
