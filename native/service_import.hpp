#pragma once
// UTF-8. No framebuffer/controller access. Host integrates roles and fallback.
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <poll.h>
#include <pthread.h>
#include <stdexcept>
#include <string>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <cerrno>
#include "service_queue_retry.hpp"

namespace einkimport {
inline void check(bool value,const char*message){if(!value)throw std::runtime_error(message);}
inline void glCheck(const char*message){auto error=glGetError();if(error!=GL_NO_ERROR)
    throw std::runtime_error(std::string(message)+" GL="+std::to_string(error));}
template<class T> inline T read(const void*p){T v;std::memcpy(&v,p,sizeof(v));return v;}
template<class T> inline T field(const void*p,size_t offset){return read<T>(static_cast<const uint8_t*>(p)+offset);}

// Private VNDK ABI: confirmed in the captured Android10 libnativewindow.so.
// method=3 maps to GraphicBuffer::CLONE_HANDLE; never takes the original handle.
using CreateFromHandle=int(*)(const AHardwareBuffer_Desc*,const void*,int,AHardwareBuffer**);
using GetNativeHandle=const void*(*)(const AHardwareBuffer*);
inline void* nativeSymbol(const char*name){
    void*p=dlsym(RTLD_DEFAULT,name);
    if(!p){static void*lib=dlopen("libnativewindow.so",RTLD_NOW|RTLD_LOCAL);if(lib)p=dlsym(lib,name);}
    return p;
}
inline AHardwareBuffer*cloneHandle(const AHardwareBuffer_Desc&desc,const void*handle){
    auto fn=reinterpret_cast<CreateFromHandle>(nativeSymbol("AHardwareBuffer_createFromHandle"));
    check(fn&&handle,"native handle import unavailable");AHardwareBuffer*b=nullptr;
    check(fn(&desc,handle,3,&b)==0&&b,"clone gralloc handle failed");return b;
}
inline bool storageAliases(AHardwareBuffer*a,AHardwareBuffer*b){
    if(a==b)return true;
    auto get=reinterpret_cast<GetNativeHandle>(nativeSymbol("AHardwareBuffer_getNativeHandle"));
    check(get,"native handle alias check unavailable");const void*ha=get(a);const void*hb=get(b);
    check(ha&&hb,"native handle alias check");int na=field<int>(ha,4),nb=field<int>(hb,4);
    check(na>0&&na<64&&nb>0&&nb<64,"alias FD count");
    auto maliId=[](const void*h)->uint64_t{
        // This Android10 vendor ABI is verified against gralloc.ums512.so
        // a523a446...: allocator b6cc stores (getpid()<<32)|atomic_counter at
        // 0xa4, including identical IDs for shared backing allocations.
        if(field<int>(h,0)!=12||field<int>(h,4)!=2||field<int>(h,8)!=55||
           field<uint32_t>(h,0x14)!=0x3141592u)return 0;
        uint64_t id=field<uint64_t>(h,0xa4);
        check(id&&uint32_t(id>>32)==field<uint32_t>(h,0xb8),"Mali backing ID/allocator mismatch");
        return id;
    };
    uint64_t ia=maliId(ha),ib=maliId(hb);
    if(ia&&ib)return ia==ib;
    // dma-buf / metadata descriptors can share anonymous inode numbers. Compare
    // the open file descriptions instead: cloned handles retain duplicated FDs.
    for(int i=0;i<na;++i){int fa=field<int>(ha,12+i*4);
        for(int j=0;j<nb;++j){int fb=field<int>(hb,12+j*4);
            if(fa==fb)return true;
            errno=0;long same=syscall(SYS_kcmp,getpid(),getpid(),0,fa,fb);
            if(same==0)return true;
            if(same<0)throw std::runtime_error("alias open-file comparison unavailable errno="+std::to_string(errno));}}
    return false;
}
struct BufferRef {
    AHardwareBuffer*p=nullptr;
    explicit BufferRef(AHardwareBuffer*b=nullptr):p(b){}
    ~BufferRef(){if(p)AHardwareBuffer_release(p);}
    BufferRef(const BufferRef&)=delete;BufferRef&operator=(const BufferRef&)=delete;
};

// Own only the duplicate FD. poll waits sync_file; no EGL native-fence extension required.
inline void waitAcquire(int borrowedFd,int timeoutMs=3000){
    if(borrowedFd==-1)return;
    check(borrowedFd>=0&&timeoutMs>0,"invalid acquire fence");
    int fd=fcntl(borrowedFd,F_DUPFD_CLOEXEC,0);check(fd>=0,"dup acquire fence");
    struct Close{int fd;~Close(){close(fd);}}guard{fd};
    auto end=std::chrono::steady_clock::now()+std::chrono::milliseconds(timeoutMs);
    for(;;){
        auto remain=std::chrono::duration_cast<std::chrono::milliseconds>(end-std::chrono::steady_clock::now()).count();
        check(remain>0,"acquire fence timeout");pollfd p{fd,POLLIN,0};
        int n=poll(&p,1,static_cast<int>(remain));if(n<0&&errno==EINTR)continue;
        check(n>0&&(p.revents&POLLIN)&&!(p.revents&(POLLERR|POLLNVAL)),"acquire fence failed/timeout");return;
    }
}

struct Config {
    EGLDisplay display=EGL_NO_DISPLAY;EGLContext share=EGL_NO_CONTEXT;
    uint32_t sourceWidth=720,sourceHeight=1440,targetWidth=1440,targetHeight=720;
    // Optional existing module-owned targets; held with independent AHB references.
    // Must include GPU_COLOR_OUTPUT|GPU_SAMPLED_IMAGE. Never SF external buffers.
    std::array<AHardwareBuffer*,3> targets{};
};

class Importer {
    struct Slot {AHardwareBuffer*b=nullptr;EGLImageKHR image=EGL_NO_IMAGE_KHR;GLuint texture=0,fbo=0;unsigned pins=0;};
    Config config_;std::array<Slot,3>slots_{};std::mutex mutex_;
    EGLContext context_=EGL_NO_CONTEXT;EGLSurface surface_=EGL_NO_SURFACE;
    GLuint program_=0,vao_=0;uint64_t sequence_=0;unsigned next_=0;
    PFNEGLCREATEIMAGEKHRPROC create_=nullptr;PFNEGLDESTROYIMAGEKHRPROC destroy_=nullptr;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC native_=nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC bind_=nullptr;
    struct Current {
        EGLDisplay oldDisplay=eglGetCurrentDisplay();EGLContext oldContext=eglGetCurrentContext();
        EGLSurface oldDraw=eglGetCurrentSurface(EGL_DRAW),oldRead=eglGetCurrentSurface(EGL_READ);
        EGLenum oldApi=eglQueryAPI();EGLDisplay ours;bool bound=false;
        explicit Current(Importer&i):ours(i.config_.display){
            check(eglBindAPI(EGL_OPENGL_ES_API),"producer EGL API");
            if(!eglMakeCurrent(ours,i.surface_,i.surface_,i.context_)){
                auto error=eglGetError();eglBindAPI(oldApi);
                throw std::runtime_error("producer EGL current EGL="+std::to_string(error));
            }bound=true;
        }
        ~Current(){
            if(!bound)return;
            eglMakeCurrent(ours,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
            eglBindAPI(oldApi);
            if(oldContext!=EGL_NO_CONTEXT)eglMakeCurrent(oldDisplay,oldDraw,oldRead,oldContext);
        }
    };
    struct Image {
        Importer&owner;EGLImageKHR image=EGL_NO_IMAGE_KHR;GLuint texture=0;
        Image(Importer&i,AHardwareBuffer*b):owner(i){
            EGLint attrs[]={EGL_IMAGE_PRESERVED_KHR,EGL_TRUE,EGL_NONE};
            image=i.create_(i.config_.display,EGL_NO_CONTEXT,EGL_NATIVE_BUFFER_ANDROID,i.native_(b),attrs);
            check(image!=EGL_NO_IMAGE_KHR,"source native EGLImage");
            glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);
            i.bind_(GL_TEXTURE_2D,reinterpret_cast<GLeglImageOES>(image));
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            if(glGetError()!=GL_NO_ERROR){glDeleteTextures(1,&texture);texture=0;
                i.destroy_(i.config_.display,image);image=EGL_NO_IMAGE_KHR;throw std::runtime_error("source native texture");}
        }
        ~Image(){if(texture)glDeleteTextures(1,&texture);if(image!=EGL_NO_IMAGE_KHR)owner.destroy_(owner.config_.display,image);}
    };
    static GLuint shader(GLenum type,const char*text){
        GLuint s=glCreateShader(type);glShaderSource(s,1,&text,nullptr);glCompileShader(s);GLint ok=0;
        glGetShaderiv(s,GL_COMPILE_STATUS,&ok);if(!ok){char log[2048]={};glGetShaderInfoLog(s,sizeof(log),nullptr,log);
            glDeleteShader(s);throw std::runtime_error(std::string("import shader: ")+log);}return s;
    }
    void initialize(){
        check(config_.display!=EGL_NO_DISPLAY&&config_.share!=EGL_NO_CONTEXT,"worker EGL not ready");
        check(config_.sourceWidth&&config_.sourceHeight&&config_.targetWidth&&config_.targetHeight&&
            uint64_t(config_.sourceWidth)*config_.sourceHeight==uint64_t(config_.targetWidth)*config_.targetHeight,"import dimensions");
        EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
        EGLConfig cfg=nullptr;EGLint count=0;
        // Choose the same config ID as the worker, to avoid sharing config mismatch.
        EGLint id=0;check(eglQueryContext(config_.display,config_.share,EGL_CONFIG_ID,&id),"worker config ID");
        EGLint byId[]={EGL_CONFIG_ID,id,EGL_NONE};
        check(eglChooseConfig(config_.display,id?byId:attrs,&cfg,1,&count)&&count,"producer EGL config");
        EGLint ca[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE},sa[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
        EGLenum oldApi=eglQueryAPI();check(eglBindAPI(EGL_OPENGL_ES_API),"create producer API");
        context_=eglCreateContext(config_.display,cfg,config_.share,ca);
        surface_=eglCreatePbufferSurface(config_.display,cfg,sa);eglBindAPI(oldApi);
        check(context_!=EGL_NO_CONTEXT&&surface_!=EGL_NO_SURFACE,"producer shared context");
        Current current(*this);
        create_=reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
        destroy_=reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
        native_=reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        bind_=reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        check(create_&&destroy_&&native_&&bind_,"native EGL image API");
        const char*vs="#version 300 es\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}";
        const char*fs=R"(#version 300 es
precision highp float;precision highp int;
uniform highp sampler2D src;uniform int sw,tw;layout(location=0)out vec4 color;
void main(){ivec2 d=ivec2(gl_FragCoord.xy);int i=d.y*tw+d.x;
color=texelFetch(src,ivec2(i%sw,i/sw),0);})";
        GLuint v=shader(GL_VERTEX_SHADER,vs),f=0;
        try{f=shader(GL_FRAGMENT_SHADER,fs);}catch(...){glDeleteShader(v);throw;}
        program_=glCreateProgram();glAttachShader(program_,v);glAttachShader(program_,f);glLinkProgram(program_);
        glDeleteShader(v);glDeleteShader(f);GLint ok=0;glGetProgramiv(program_,GL_LINK_STATUS,&ok);check(ok,"import link");
        glGenVertexArrays(1,&vao_);glBindVertexArray(vao_);
        for(size_t k=0;k<slots_.size();++k){auto&s=slots_[k];AHardwareBuffer_Desc d{};
            for(size_t j=0;j<k;++j)check(!config_.targets[k]||config_.targets[k]!=slots_[j].b,"snapshot targets alias");
            if(config_.targets[k]){s.b=config_.targets[k];AHardwareBuffer_acquire(s.b);}
            else{d.width=config_.targetWidth;d.height=config_.targetHeight;d.layers=1;
                d.format=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
                d.usage=AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT|AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
                check(!AHardwareBuffer_allocate(&d,&s.b),"snapshot AHB allocation");}
            AHardwareBuffer_describe(s.b,&d);
            check(d.width==config_.targetWidth&&d.height==config_.targetHeight&&d.layers==1&&
                d.format==AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM&&
                (d.usage&AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT)&&
                (d.usage&AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE),"snapshot AHB descriptor/usage");
            EGLint ia[]={EGL_IMAGE_PRESERVED_KHR,EGL_TRUE,EGL_NONE};
            s.image=create_(config_.display,EGL_NO_CONTEXT,EGL_NATIVE_BUFFER_ANDROID,native_(s.b),ia);
            check(s.image!=EGL_NO_IMAGE_KHR,"snapshot EGLImage");glGenTextures(1,&s.texture);
            glBindTexture(GL_TEXTURE_2D,s.texture);bind_(GL_TEXTURE_2D,reinterpret_cast<GLeglImageOES>(s.image));
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glGenFramebuffers(1,&s.fbo);glBindFramebuffer(GL_FRAMEBUFFER,s.fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,s.texture,0);
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"snapshot framebuffer");
        }glCheck("import initialize");
    }
    void cleanup()noexcept{
        if(context_!=EGL_NO_CONTEXT&&surface_!=EGL_NO_SURFACE){try{Current current(*this);glFinish();
            for(auto&s:slots_){if(s.fbo)glDeleteFramebuffers(1,&s.fbo);if(s.texture)glDeleteTextures(1,&s.texture);
                if(s.image!=EGL_NO_IMAGE_KHR&&destroy_)destroy_(config_.display,s.image);}
            if(vao_)glDeleteVertexArrays(1,&vao_);if(program_)glDeleteProgram(program_);
        }catch(...){}}
        for(auto&s:slots_)if(s.b)AHardwareBuffer_release(s.b);
        if(surface_!=EGL_NO_SURFACE)eglDestroySurface(config_.display,surface_);
        if(context_!=EGL_NO_CONTEXT)eglDestroyContext(config_.display,context_);
    }
    void unpin(unsigned k){std::lock_guard<std::mutex>lock(mutex_);check(slots_.at(k).pins>0,"snapshot lease underflow");--slots_[k].pins;}
public:
    class Lease {
        Importer*owner_=nullptr;unsigned slot_=0;uint64_t sequence_=0;
        friend class Importer;
        Lease(Importer&o,unsigned k,uint64_t n):owner_(&o),slot_(k),sequence_(n){}
    public:
        Lease()=default;Lease(const Lease&)=delete;Lease&operator=(const Lease&)=delete;
        Lease(Lease&&o)noexcept:owner_(o.owner_),slot_(o.slot_),sequence_(o.sequence_){o.owner_=nullptr;}
        Lease&operator=(Lease&&o)noexcept{if(this!=&o){reset();owner_=o.owner_;slot_=o.slot_;sequence_=o.sequence_;o.owner_=nullptr;}return *this;}
        ~Lease(){reset();}
        void reset()noexcept{if(owner_){owner_->unpin(slot_);owner_=nullptr;}}
        unsigned slot()const{return slot_;}uint64_t sequence()const{return sequence_;}
        AHardwareBuffer*buffer()const{check(owner_,"empty snapshot lease");return owner_->slots_[slot_].b;}
    };
    explicit Importer(Config config):config_(config){try{initialize();}catch(...){cleanup();throw;}}
    ~Importer(){cleanup();}Importer(const Importer&)=delete;Importer&operator=(const Importer&)=delete;
    AHardwareBuffer*buffer(unsigned k)const{return slots_.at(k).b;}
    Lease copy(AHardwareBuffer*source,int borrowedFence=-1,int requestedSlot=-1){
        // Serializes the shared producer context across binder/present threads.
        std::lock_guard<std::mutex>lock(mutex_);waitAcquire(borrowedFence);
        AHardwareBuffer_Desc desc{};check(source,"null source AHB");AHardwareBuffer_describe(source,&desc);
        check(desc.width==config_.sourceWidth&&desc.height==config_.sourceHeight&&desc.layers==1&&
            desc.format==AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM&&
            !(desc.usage&AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT),"unsupported source descriptor");
        unsigned chosen=3;
        if(requestedSlot>=0){check(requestedSlot<3,"invalid snapshot slot");if(!slots_[requestedSlot].pins)chosen=unsigned(requestedSlot);}
        else for(unsigned n=0;n<3;++n){unsigned k=(next_+n)%3;if(!slots_[k].pins){chosen=k;break;}}
        check(chosen<3,"all snapshot slots leased");Current current(*this);Image image(*this,source);
        auto&s=slots_[chosen];check(!storageAliases(source,s.b),"source aliases snapshot storage");bool issued=false;
        try{
            glBindFramebuffer(GL_FRAMEBUFFER,s.fbo);glViewport(0,0,config_.targetWidth,config_.targetHeight);
            glDisable(GL_BLEND);glDisable(GL_DITHER);glDisable(GL_DEPTH_TEST);glDisable(GL_SCISSOR_TEST);
            glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);glBindVertexArray(vao_);glUseProgram(program_);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,image.texture);
            glUniform1i(glGetUniformLocation(program_,"src"),0);glUniform1i(glGetUniformLocation(program_,"sw"),config_.sourceWidth);
            glUniform1i(glGetUniformLocation(program_,"tw"),config_.targetWidth);glDrawArrays(GL_TRIANGLES,0,3);issued=true;
            glCheck("snapshot draw");GLsync fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            check(fence,"snapshot copy fence");GLenum status=glClientWaitSync(fence,GL_SYNC_FLUSH_COMMANDS_BIT,1000000000ULL);
            glDeleteSync(fence);check(status==GL_ALREADY_SIGNALED||status==GL_CONDITION_SATISFIED,"snapshot fence wait");
            glCheck("snapshot completion");
        }catch(...){if(issued)glFinish();throw;} // external source must not be released while GPU still reads it
        ++s.pins;next_=(chosen+1)%3;return Lease(*this,chosen,++sequence_);
    }
    Lease import(const void*borrowedHandle,const AHardwareBuffer_Desc&desc,int borrowedFence=-1,int requestedSlot=-1){
        BufferRef source(cloneHandle(desc,borrowedHandle));return copy(source.p,borrowedFence,requestedSlot);
    }
};

enum class PublishKind {Raw,Pending};
struct Host {
    void*user=nullptr;
    // Resolver must publish worker display/context and all 3 slot roles coherently.
    bool(*resolve)(void*,Config&,std::array<void*,3>&tokens)noexcept=nullptr;
    // Called with stock queue mutex held. Must not reenter queue locks.
    // Reserve checks no consumer/previous frame uses this target; return its exact slot.
    int(*reserve)(void*,PublishKind,void*target)noexcept=nullptr;
    // commit=true transfers role protection to the queued consumer; false cancels it.
    void(*release)(void*,PublishKind,int,bool commit)noexcept=nullptr;
    // On fallback, materialise/restore all CPU roles BEFORE stock function reads them.
    bool(*cpuFallback)(void*,const char*reason)noexcept=nullptr;
};
class StockBridge {
    uintptr_t base_;Host host_;std::mutex producer_;std::unique_ptr<Importer>importer_;
    std::array<void*,3>tokens_{};
    static uint8_t flag(uintptr_t a){return __atomic_load_n(reinterpret_cast<uint8_t*>(a),__ATOMIC_ACQUIRE);}
    static void setFlag(uintptr_t a,uint8_t value){__atomic_store_n(reinterpret_cast<uint8_t*>(a),value,__ATOMIC_RELEASE);}
    struct QueueLock {pthread_mutex_t*p;explicit QueueLock(uintptr_t a):p(reinterpret_cast<pthread_mutex_t*>(a)){
        check(!pthread_mutex_lock(p),"stock queue lock");}~QueueLock(){pthread_mutex_unlock(p);}};
    bool attempt(void*layer){
        check(layer,"null input layer");const void*handle=field<const void*>(layer,8);check(handle,"null gralloc handle");
        // Guard the private handle offsets, including native_handle header length.
        int fds=field<int>(handle,4),ints=field<int>(handle,8);
        check(field<int>(handle,0)==12&&fds>=1&&ints>=0&&fds+ints>=14&&fds+ints<1024,"private handle header");
        AHardwareBuffer_Desc source{};source.width=field<uint32_t>(handle,0x1c);source.height=field<uint32_t>(handle,0x20);
        source.format=field<uint32_t>(handle,0x24);source.stride=field<uint32_t>(handle,0x40);
        source.layers=1;source.usage=AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        check(source.width==720&&source.height==1440&&source.stride==720&&source.format==1,"stock input format/stride");
        if(flag(base_+0x234209))return true; // stock bitmap path ignores this SF input
        if(!importer_){Config cfg;check(host_.resolve&&host_.resolve(host_.user,cfg,tokens_),"input/worker roles unavailable");
            check(host_.reserve&&host_.release&&host_.cpuFallback,"host lease/fallback callbacks required");
            for(unsigned i=0;i<3;++i)check(cfg.targets[i]&&tokens_[i],"host must supply three target AHB/token pairs");
            importer_=std::make_unique<Importer>(cfg);}
        publishStableQueue([&](){return flag(base_+0x9e351)?PublishKind::Raw:PublishKind::Pending;},
          [&](PublishKind kind){return QueueLock(base_+(kind==PublishKind::Raw?0x233f90:0x233fb8));},
          [&](PublishKind kind){
        if(flag(base_+0x234209))return;
        if(kind==PublishKind::Raw&&flag(base_+0x9e350))return;
        uintptr_t targetAddress=base_+(kind==PublishKind::Raw?0x2341d8:0x2341e0);
        void*target=read<void*>(reinterpret_cast<void*>(targetAddress));
        int slot=host_.reserve(host_.user,kind,target);check(slot>=0&&slot<3,"input target not reservable");
        bool committed=false;
        try{
            check(tokens_[unsigned(slot)]==target,"target role/token mismatch");
            auto lease=importer_->import(handle,source,field<int>(layer,0x80),slot);
            // GPU fence already completed; raw pointer and role assignment stay intact.
            if(kind==PublishKind::Raw){setFlag(base_+0x9e350,1);setFlag(base_+0x234200,0);
                pthread_cond_broadcast(reinterpret_cast<pthread_cond_t*>(base_+0x2340b8));}
            else setFlag(base_+0x234200,1);
            committed=true;host_.release(host_.user,kind,slot,true);
        }catch(...){if(!committed)host_.release(host_.user,kind,slot,false);throw;}
        });
        return true;
    }
public:
    StockBridge(uintptr_t base,Host host):base_(base),host_(host){check(base_,"HWC base required");}
    // No exception crosses the HWC C ABI. All producers and fallback paths serialized.
    void dump(void*layer)noexcept{
        std::lock_guard<std::mutex>lock(producer_);
        try{if(attempt(layer)){
            __atomic_fetch_add(reinterpret_cast<uint32_t*>(base_+0x23331c),uint32_t(1),__ATOMIC_RELAXED);return;
        }}catch(const std::exception&e){
            if(host_.cpuFallback&&!host_.cpuFallback(host_.user,e.what()))return;
        }catch(...){if(host_.cpuFallback&&!host_.cpuFallback(host_.user,"unknown input import failure"))return;}
        reinterpret_cast<void(*)(void*)>(base_+0x7c188)(layer);
    }
};
// Register once before hooking 0x72460. Bridge must outlive HWC producer threads.
inline std::atomic<StockBridge*>registeredBridge{nullptr};
inline std::atomic<uintptr_t>registeredBase{0};
inline void configure(uintptr_t base,StockBridge*bridge){registeredBase.store(base);registeredBridge.store(bridge,std::memory_order_release);}
inline void hookDumpFrameBuffer(void*layer)noexcept{
    if(auto*b=registeredBridge.load(std::memory_order_acquire)){b->dump(layer);return;}
    if(auto base=registeredBase.load())reinterpret_cast<void(*)(void*)>(base+0x7c188)(layer);
}
} // namespace einkimport
