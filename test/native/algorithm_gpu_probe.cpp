// 独立 EGL/GLES 3.1 测试。只创建离屏 context/SSBO，不访问 HWC、面板或系统属性。
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
static GLuint compile(GLenum type,const char*source){
    GLuint s=glCreateShader(type);glShaderSource(s,1,&source,nullptr);glCompileShader(s);
    GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
    if(!ok){char log[8192]={};glGetShaderInfoLog(s,sizeof(log),nullptr,log);throw std::runtime_error(log);}
    return s;
}
#include "../../native/service_algorithm_policy.hpp"
#include "../../native/service_binary_shader.hpp"
#include "../../native/service_image_ops.hpp"
#include "../../native/service_bluenoise.hpp"
#include "legacy_binary_shader.hpp"
using Bytes=std::vector<uint8_t>;
static void require(bool ok,const std::string&why){if(!ok)throw std::runtime_error(why);}
static void glCheck(){auto e=glGetError();require(e==GL_NO_ERROR,"GL error "+std::to_string(e));}
struct GPU {
    EGLDisplay display=EGL_NO_DISPLAY;EGLContext context=EGL_NO_CONTEXT;EGLSurface surface=EGL_NO_SURFACE;
    std::array<GLuint,11> buffers{};
    GPU(){
        display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
        require(eglInitialize(display,nullptr,nullptr)&&eglBindAPI(EGL_OPENGL_ES_API),"EGL init");
        EGLint a[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
        EGLConfig cfg;EGLint count=0;require(eglChooseConfig(display,a,&cfg,1,&count)&&count,"EGL config");
        EGLint c[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE},s[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
        context=eglCreateContext(display,cfg,EGL_NO_CONTEXT,c);surface=eglCreatePbufferSurface(display,cfg,s);
        require(context!=EGL_NO_CONTEXT&&surface!=EGL_NO_SURFACE&&eglMakeCurrent(display,surface,surface,context),"EGL current");
        GLint major=0,minor=0;glGetIntegerv(GL_MAJOR_VERSION,&major);glGetIntegerv(GL_MINOR_VERSION,&minor);
        require(major>3||(major==3&&minor>=1),"GLES 3.1 required");
        glGenBuffers(buffers.size(),buffers.data());glCheck();
    }
    ~GPU(){
        if(context!=EGL_NO_CONTEXT)glDeleteBuffers(buffers.size(),buffers.data());
        if(display!=EGL_NO_DISPLAY){
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
            if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
            if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
            eglTerminate(display);
        }
    }
    void upload(unsigned binding,const void*data,size_t size){
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers.at(binding));
        glBufferData(GL_SHADER_STORAGE_BUFFER,size,data,GL_DYNAMIC_COPY);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,binding,buffers[binding]);glCheck();
    }
    void upload(unsigned binding,const Bytes&data){upload(binding,data.data(),data.size());}
    void fence(){
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
        GLsync s=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);require(s!=nullptr,"GL fence");
        auto r=glClientWaitSync(s,GL_SYNC_FLUSH_COMMANDS_BIT,5000000000ULL);glDeleteSync(s);
        require(r==GL_ALREADY_SIGNALED||r==GL_CONDITION_SATISFIED,"GPU timeout");glCheck();
    }
    Bytes read(unsigned binding,size_t size){
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers.at(binding));
        auto*p=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,size,GL_MAP_READ_BIT);require(p!=nullptr,"GPU map");
        Bytes data(size);std::memcpy(data.data(),p,size);require(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER),"GPU unmap");
        glCheck();return data;
    }
};
static unsigned scenarios=0;
static bool collectMismatches=false;
static uint64_t totalMismatches=0;
static unsigned failedComparisons=0;
static void same(const Bytes&actual,const Bytes&expected,const std::string&name){
    require(actual.size()==expected.size(),name+": size");
    size_t first=actual.size(),mismatches=0;
    for(size_t i=0;i<actual.size();i++)if(actual[i]!=expected[i]){if(first==actual.size())first=i;++mismatches;}
    if(!mismatches)return;
    const std::string message=name+" at "+std::to_string(first)+": actual="+std::to_string(actual[first])+" expected="+std::to_string(expected[first])+" mismatches="+std::to_string(mismatches);
    if(!collectMismatches)throw std::runtime_error(message);
    totalMismatches+=mismatches;++failedComparisons;
    std::cerr<<"MISMATCH "<<message<<"\n";
}
static Bytes mixedReference(const Bytes&old,const Bytes&next){
    Bytes b(old.size());
    for(size_t i=0;i<b.size();i+=2){b[i]=uint8_t(16*(old[i]/2)+old[i+1]/2);b[i+1]=uint8_t(16*(next[i]/2)+next[i+1]/2);}
    return b;
}
static unsigned bayer(unsigned x,unsigned y){
    // 独立递归生成 8x8 Bayer 矩阵（不是复制 shader 的位运算）。
    constexpr unsigned seed[2][2]={{0,3},{2,1}};unsigned b=0;
    for(unsigned bit=0;bit<3;bit++)b=b*4+seed[(y>>bit)&1][(x>>bit)&1];
    return b;
}
static void setBinary(GLuint p,unsigned algorithm,bool video,float gamma,float black,float hyst,unsigned n,unsigned width){
    glUseProgram(p);EinkImageOps::uniform(p,"uAlgorithm",algorithm);EinkImageOps::uniform(p,"uVideoTone",video?1:0);
    EinkImageOps::uniform(p,"count",n);EinkImageOps::uniform(p,"width",width);
    glUniform1f(glGetUniformLocation(p,"uGamma0"),gamma);glUniform1f(glGetUniformLocation(p,"uBlack"),black);
    glUniform1f(glGetUniformLocation(p,"uHyst"),hyst);
}
static void inputs(GPU&g,const Bytes&gray,const Bytes&tone,const Bytes&old){
    const size_t n=gray.size();g.upload(7,gray);g.upload(4,tone);g.upload(6,old);
    g.upload(5,nullptr,n);g.upload(0,nullptr,n);
    uint32_t noise[4096];for(unsigned i=0;i<4096;i++)noise[i]=kBlueNoise64[i];g.upload(8,noise,sizeof(noise));g.fence();
}
static void binaryCases(GPU&g,GLuint p){
    constexpr unsigned width=64,n=64*64*256;
    Bytes gray(n),tone(256),old(n);
    // 每个输入灰度完整覆盖 64x64 蓝噪声/Bayer 相位，测试 0/255 和所有阈值附近。
    for(unsigned i=0;i<n;i++)gray[i]=uint8_t(i/4096);
    for(unsigned i=0;i<256;i++)tone[i]=uint8_t(i);
    for(const char*value:{"bayer","blue","threshold"})for(auto h:{einkalg::Hysteresis::Off,einkalg::Hysteresis::On})
    for(unsigned previous:{0u,14u,16u,30u}){
        std::fill(old.begin(),old.end(),uint8_t(previous));
        auto selected=einkalg::binary(false,einkalg::parseAlgorithm(einkalg::Group::Fast,value),h,einkalg::Algorithm::Blue);
        inputs(g,gray,tone,old);
        // 刻意给非默认 gamma/black，验证 normal blue/threshold/Bayer 根本不使用视频调色。
        const float shift=selected.hysteresis?12.f/255.f:0.f;
        setBinary(p,selected.shaderAlgorithm(),selected.videoTone,0.23f,173.f,shift,n,width);
        glDispatchCompute((n+511)/512,1,1);g.fence();auto next=g.read(5,n);
        Bytes expected(n);
        for(unsigned i=0;i<n;i++){
            const unsigned x=i%width,y=i/width,b=bayer(x,y);
            // 在像素值域比较，防止 CPU 参考自身的除法舍入误判整数相等边界。
            const double threshold=selected.algorithm==einkalg::Algorithm::Blue?
                (double(kBlueNoise64[(y%64)*64+x%64])+0.5)*255.0/4096.0:
                selected.algorithm==einkalg::Algorithm::Threshold?127.5:double(b*4+2);
            const double delta=selected.hysteresis?12.0:0.0;
            expected[i]=double(gray[i])>(previous>=16?threshold-delta:threshold+delta)?30:0;
        }
        const std::string name=std::string("normal_")+value+(selected.hysteresis?"_on":"_off")+"_old"+std::to_string(previous);
        same(next,expected,name);same(g.read(0,n),mixedReference(old,expected),name+"_PairY4");
        ++scenarios;std::cout<<(collectMismatches?"CHECK ":"PASS ")<<name<<"\n";
    }
    // 非恒等 tone，确保 blue/threshold 读取 tone 后的值，仍不读取 gamma/black。
    for(unsigned i=0;i<256;i++)tone[i]=uint8_t(255-i);
    for(const char*value:{"blue","threshold"}){
        inputs(g,gray,tone,old);auto a=einkalg::parseAlgorithm(einkalg::Group::Extreme,value);
        auto s=einkalg::binary(false,a,einkalg::Hysteresis::Off,einkalg::Algorithm::Blue);
        setBinary(p,s.shaderAlgorithm(),false,0.19f,210.f,0,n,width);glDispatchCompute((n+511)/512,1,1);g.fence();
        Bytes expected(n);
        for(unsigned i=0;i<n;i++){
            const unsigned x=i%width,y=i/width;
            const double t=a==einkalg::Algorithm::Blue?(double(kBlueNoise64[(y%64)*64+x])+0.5)/4096.0:0.5;
            expected[i]=double(tone[gray[i]])/255.0>t?30:0;
        }
        same(g.read(5,n),expected,std::string("normal_tone_")+value);++scenarios;
    }
}
static void legacyCases(GPU&g,GLuint p,GLuint legacy){
    constexpr unsigned n=1440*720;Bytes gray(n),tone(256),old(n);
    uint32_t rng=0x7ab123ef;
    for(unsigned i=0;i<n;i++){rng=rng*1664525+1013904223;gray[i]=uint8_t(rng>>24);old[i]=uint8_t((i%16)*2);}
    for(unsigned i=0;i<256;i++)tone[i]=uint8_t((i*73+17)&255);
    struct Tune {float gamma,black,hyst;};
    for(unsigned mode:{0u,1u,2u})for(const auto&t:std::array<Tune,4>{{{0.6f,20.f,12.f},{1.f,0.f,0.f},{0.05f,254.f,64.f},{2.f,0.f,12.f}}}){
        if(collectMismatches)std::cout<<"CASE legacy mode="<<mode<<" gamma="<<t.gamma<<" black="<<t.black<<" hyst="<<t.hyst<<"\n";
        inputs(g,gray,tone,old);glUseProgram(legacy);EinkImageOps::uniform(legacy,"uVideo",mode);
        glUniform1f(glGetUniformLocation(legacy,"uGamma0"),t.gamma);glUniform1f(glGetUniformLocation(legacy,"uBlack"),t.black);
        glUniform1f(glGetUniformLocation(legacy,"uHyst"),t.hyst/255.f);glDispatchCompute((n+511)/512,1,1);g.fence();
        const auto next=g.read(5,n),mix=g.read(0,n);
        auto s=einkalg::binary(mode!=0,einkalg::Algorithm::Default,einkalg::Hysteresis::Default,
            mode==2?einkalg::Algorithm::Bayer:einkalg::Algorithm::Blue);
        setBinary(p,s.shaderAlgorithm(),s.videoTone,t.gamma,t.black,mode?t.hyst/255.f:0.f,n,1440);
        glDispatchCompute((n+511)/512,1,1);g.fence();
        same(g.read(5,n),next,"frozen_legacy_levels_"+std::to_string(mode));
        same(g.read(0,n),mix,"frozen_legacy_PairY4_"+std::to_string(mode));++scenarios;
    }
    std::cout<<(collectMismatches?"CHECK ":"PASS ")<<"frozen legacy default (12 full-screen comparisons)\n";
}
static void grayCases(GPU&g){
    const GLuint levels=EinkImageOps::link(EinkImageOps::levelsShader());
    const GLuint init=EinkImageOps::link(EinkImageOps::stock16RingInitShader());
    // 使用生产容量和相同 prefix 核，独立 probe 只缩小测试图尺寸。
    auto source=EinkImageOps::stock16PrefixCacheShader(1440);const GLuint prefix=EinkImageOps::link(source.c_str());
    constexpr unsigned width=256,height=64,n=width*height;Bytes gray(n),tone(256),old(n);
    for(unsigned i=0;i<n;i++){gray[i]=uint8_t((i+(i/width)*19)&255);old[i]=uint8_t((i%16)*2);}
    struct Case {const char*option;bool stock,lock;};
    for(unsigned contrast:{0u,30u,100u})for(const auto&c:std::array<Case,7>{{
        {"default",false,false},{"default",true,false},{"default",true,true},{"default",false,true},
        {"stock",false,true},{"nearest",true,false},{"bayer16",true,true}}}){
        if(collectMismatches)std::cout<<"CASE gray option="<<c.option<<" contrast="<<contrast<<" stock="<<c.stock<<" lock="<<c.lock<<"\n";
        EinkImageOps::gldTone(contrast,tone.data());inputs(g,gray,tone,old);
        const auto chosen=einkalg::gray(einkalg::parseAlgorithm(einkalg::Group::Reading,c.option),c.stock,c.lock);
        auto kind=EinkImageOps::Levels::Tone;GLuint quantized=g.buffers[7];
        Bytes mapped(n);for(unsigned i=0;i<n;i++)mapped[i]=tone[gray[i]];
        Bytes expected(n);
        if(chosen==einkalg::Gray::Stock16){
            g.upload(9,nullptr,1440*4*4);g.upload(10,nullptr,n);g.upload(8,nullptr,4);g.fence();
            EinkImageOps::dispatchStock16Prefix(init,prefix,g.buffers[7],g.buffers[4],g.buffers[9],g.buffers[10],g.buffers[8],width,height,n,8,1440);
            g.fence();auto proof=g.read(8,4);require(proof==Bytes(4,0),"stock16 domain violation");
            std::vector<int32_t> error(n);Bytes stock(n);
            require(EinkImageOps::stock16Reference(mapped.data(),stock.data(),error.data(),width,height),"stock16 CPU reference");
            same(g.read(10,n),stock,"stock16 before level packing");
            for(unsigned i=0;i<n;i++)expected[i]=uint8_t((stock[i]/16)*2);
            quantized=g.buffers[10];kind=EinkImageOps::Levels::Raw;
        }else{
            if(chosen==einkalg::Gray::Nearest16)kind=EinkImageOps::Levels::Nearest16;
            if(chosen==einkalg::Gray::Bayer16)kind=EinkImageOps::Levels::Ordered16;
            for(unsigned i=0;i<n;i++){
                const unsigned v=mapped[i];unsigned q=v/16;
                if(chosen==einkalg::Gray::Nearest16)q=std::min((v+8)/16,15u);
                if(chosen==einkalg::Gray::Bayer16&&q<15&&(v%16)*64>bayer(i%width,i/width)*16+8)q++;
                expected[i]=uint8_t(q*2);
            }
        }
        EinkImageOps::dispatchLevels(levels,quantized,g.buffers[4],g.buffers[6],g.buffers[5],g.buffers[0],n,width,kind);g.fence();
        same(g.read(5,n),expected,std::string("gray_levels_")+c.option);
        same(g.read(0,n),mixedReference(old,expected),std::string("gray_PairY4_")+c.option);++scenarios;
    }
    glDeleteProgram(prefix);glDeleteProgram(init);glDeleteProgram(levels);
    std::cout<<(collectMismatches?"CHECK ":"PASS ")<<"gray stock/nearest/bayer16/default, tone and 16-level packing (21 cases)\n";
}
int main(int argc,char**argv){
    try{
        require(argc==1||(argc==2&&std::strcmp(argv[1],"--continue")==0),"usage: algorithm_gpu_probe [--continue]");
        collectMismatches=argc==2;
        GPU g;std::cout<<"renderer="<<reinterpret_cast<const char*>(glGetString(GL_RENDERER))<<"\n";
        const GLuint p=EinkImageOps::link(einkalg::binaryShader()),legacy=EinkImageOps::link(legacyBinaryShader());
        binaryCases(g,p);legacyCases(g,p,legacy);grayCases(g);
        glDeleteProgram(legacy);glDeleteProgram(p);glCheck();
        std::cout<<"{\"scenarios\":"<<scenarios<<",\"mismatches\":"<<totalMismatches<<",\"panel_access\":false";
        if(collectMismatches)std::cout<<",\"failed_comparisons\":"<<failedComparisons;
        std::cout<<"}\n";return totalMismatches?2:0;
    }catch(const std::exception&e){std::cerr<<"algorithm_gpu_probe: "<<e.what()<<"\n";return 1;}
}
