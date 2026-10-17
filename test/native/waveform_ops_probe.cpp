// Standalone candidate shader probe. No HWC/DRM/sysfs/properties/device control.
// EXPECTED bytes are generated separately by the ORIGINAL AArch64 library.
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include "service_waveform.hpp"
#include <array>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <vector>

namespace fs=std::filesystem;
using Bytes=std::vector<uint8_t>;
static Bytes read(const fs::path&p){
    std::ifstream s(p,std::ios::binary);if(!s)throw std::runtime_error("read "+p.string());
    return Bytes(std::istreambuf_iterator<char>(s),{});
}
static void write(const fs::path&p,const Bytes&b){
    if(fs::exists(p))throw std::runtime_error("refuse overwrite "+p.string());
    std::ofstream s(p,std::ios::binary);s.write(reinterpret_cast<const char*>(b.data()),b.size());
    if(!s)throw std::runtime_error("write "+p.string());
}
static std::string quote(const std::string&s){
    std::string r="\"";for(unsigned char c:s){if(c=='"'||c=='\\')r+='\\';r+=c<32?' ':char(c);}return r+'"';
}
static std::vector<std::vector<std::string>> rows(const fs::path&p){
    std::ifstream s(p);if(!s)throw std::runtime_error("read TSV "+p.string());
    std::vector<std::vector<std::string>> r;std::string line;
    while(std::getline(s,line)){if(!line.empty()&&line.back()=='\r')line.pop_back();if(line.empty())continue;
        std::vector<std::string> row;std::istringstream t(line);std::string v;
        while(std::getline(t,v,'\t'))row.push_back(v);r.push_back(row);}
    return r;
}
static uint32_t num(const std::string&s){size_t n=0;auto v=std::stoul(s,&n);if(n!=s.size()||v>UINT32_MAX)throw std::runtime_error("bad uint "+s);return uint32_t(v);}
static std::string diff(const Bytes&a,const Bytes&b,size_t&total){
    if(a.size()!=b.size())throw std::runtime_error("comparison length mismatch");
    size_t count=0,first=a.size();for(size_t i=0;i<a.size();i++)if(a[i]!=b[i]){count++;if(first==a.size())first=i;}
    total+=count;return "\"bytes\":"+std::to_string(a.size())+",\"mismatches\":"+std::to_string(count)+
        ",\"first_mismatch\":"+(count?std::to_string(first):"null");
}
struct GPU{
    EGLDisplay display=EGL_NO_DISPLAY;EGLContext context=EGL_NO_CONTEXT;EGLSurface surface=EGL_NO_SURFACE;
    einkwave::Mixer mixer;std::array<GLuint,4> buffers{};GLuint frame=0,texture=0,fbo=0,vao=0;
    GPU(){
        display=eglGetDisplay(EGL_DEFAULT_DISPLAY);if(!eglInitialize(display,nullptr,nullptr)||!eglBindAPI(EGL_OPENGL_ES_API))throw std::runtime_error("EGL init");
        EGLint a[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
        EGLConfig cfg;EGLint count=0;if(!eglChooseConfig(display,a,&cfg,1,&count)||!count)throw std::runtime_error("EGL config");
        EGLint c[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE},s[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
        context=eglCreateContext(display,cfg,EGL_NO_CONTEXT,c);surface=eglCreatePbufferSurface(display,cfg,s);
        if(context==EGL_NO_CONTEXT||surface==EGL_NO_SURFACE||!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("EGL current");
        GLint major=0,minor=0;glGetIntegerv(GL_MAJOR_VERSION,&major);glGetIntegerv(GL_MINOR_VERSION,&minor);
        if(major<3||(major==3&&minor<1))throw std::runtime_error("GLES 3.1 required");
        const char*v="#version 310 es\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0.,1.);}";
        GLuint vs=glCreateShader(GL_VERTEX_SHADER),frag=glCreateShader(GL_FRAGMENT_SHADER);
        auto compile=[](GLuint sh,const char*src){glShaderSource(sh,1,&src,nullptr);glCompileShader(sh);GLint ok=0;glGetShaderiv(sh,GL_COMPILE_STATUS,&ok);
            if(!ok){char log[8192]={};glGetShaderInfoLog(sh,sizeof(log),nullptr,log);throw std::runtime_error(log);}};
        compile(vs,v);compile(frag,einkwave::FrameShader);frame=glCreateProgram();glAttachShader(frame,vs);glAttachShader(frame,frag);glLinkProgram(frame);
        GLint ok=0;glGetProgramiv(frame,GL_LINK_STATUS,&ok);if(!ok){char log[8192]={};glGetProgramInfoLog(frame,sizeof(log),nullptr,log);throw std::runtime_error(log);}
        glDeleteShader(vs);glDeleteShader(frag);glGenBuffers(4,buffers.data());glGenVertexArrays(1,&vao);glBindVertexArray(vao);
        glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);glTexStorage2D(GL_TEXTURE_2D,1,GL_RGBA8,einkwave::OutputWidth,einkwave::OutputHeight);
        glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw std::runtime_error("FBO incomplete");
        glDisable(GL_DITHER);glDisable(GL_BLEND);einkwave::check("probe setup");
    }
    ~GPU(){if(context!=EGL_NO_CONTEXT){mixer.release();glDeleteBuffers(4,buffers.data());glDeleteProgram(frame);glDeleteFramebuffers(1,&fbo);glDeleteTextures(1,&texture);glDeleteVertexArrays(1,&vao);}
        if(display!=EGL_NO_DISPLAY){eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
            if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);eglTerminate(display);}}
    void upload(unsigned binding,const Bytes&b){
        if(b.empty()||b.size()%4)throw std::runtime_error("SSBO requires packed uint32");
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[binding]);glBufferData(GL_SHADER_STORAGE_BUFFER,b.size(),b.data(),GL_DYNAMIC_COPY);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,binding,buffers[binding]);einkwave::check("probe upload");
    }
    void fence(){GLsync s=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);if(!s)throw std::runtime_error("fence allocation");
        auto r=glClientWaitSync(s,GL_SYNC_FLUSH_COMMANDS_BIT,5000000000ULL);glDeleteSync(s);
        if(r!=GL_ALREADY_SIGNALED&&r!=GL_CONDITION_SATISFIED)throw std::runtime_error("fence timeout");einkwave::check("probe fence");}
    Bytes download(unsigned binding,size_t size){
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[binding]);void*p=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,size,GL_MAP_READ_BIT);
        if(!p)throw std::runtime_error("map read");Bytes b(size);std::memcpy(b.data(),p,size);if(!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))throw std::runtime_error("unmap");
        einkwave::check("probe download");return b;
    }
    void ui(const char*name,uint32_t v){glUniform1ui(glGetUniformLocation(frame,name),v);}
    void bi(const char*name,bool v){glUniform1i(glGetUniformLocation(frame,name),v);}
    Bytes pixels(){
        Bytes b(einkwave::FrameBytes);glPixelStorei(GL_PACK_ALIGNMENT,4);glReadPixels(0,0,einkwave::OutputWidth,einkwave::OutputHeight,GL_RGBA,GL_UNSIGNED_BYTE,b.data());
        // Test-only RGBA -> stock B,G,R,A conversion.
        for(size_t i=0;i<b.size();i+=4)std::swap(b[i],b[i+2]);einkwave::check("probe pixels");return b;
    }
    Bytes scan(const std::vector<std::string>&r,const fs::path&assets){
        upload(0,read(assets/r[13]));upload(1,read(assets/r[14]));upload(2,read(assets/r[15]));upload(3,Bytes(4,0));
        glUseProgram(frame);ui("indexLayout",num(r[1]));ui("frameNumber",num(r[2]));ui("activeFrames",num(r[3]));
        ui("bandDirection",num(r[4]));ui("bandFrames",num(r[5]));bi("neutral",num(r[6]));bi("overlay12",num(r[7]));bi("maskRegion",num(r[8]));bi("motion",false);
        glUniform4ui(glGetUniformLocation(frame,"activeRect"),num(r[9]),num(r[10]),num(r[11]),num(r[12]));
        bi("twoRegions",num(r[17])!=0);
        glUniform4ui(glGetUniformLocation(frame,"secondRect"),num(r[18]),num(r[19]),num(r[20]),num(r[21]));
        glBindFramebuffer(GL_FRAMEBUFFER,fbo);glViewport(0,0,einkwave::OutputWidth,einkwave::OutputHeight);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);glDrawArrays(GL_TRIANGLES,0,3);fence();
        Bytes b=pixels();
        // Shader emits R,G,B,A from stock uint32 B,G,R,A. This is a PROBE
        // readback format conversion only, not part of production pixel work.
        return b;
    }
};
int main(int argc,char**argv){
    try{
        if(argc!=3)throw std::runtime_error("usage: waveform_ops_probe ASSETS_DIR NEW_OUTPUT_DIR");
        fs::path assets=argv[1],out=argv[2];if(!fs::create_directory(out))throw std::runtime_error("new output directory required");
        auto mixes=rows(assets/"mix_cases.tsv"),scans=rows(assets/"scan_cases.tsv");if(mixes.empty()||scans.empty())throw std::runtime_error("empty assets");
        GPU g;size_t mismatches=0;std::vector<std::string> checks;
        for(const auto&r:mixes){
            if(r.size()!=13)throw std::runtime_error("mix TSV columns");
            auto old=read(assets/r[3]),next=read(assets/r[4]),seed=read(assets/r[5]);
            g.upload(0,old);g.upload(1,next);g.upload(2,seed);g.upload(3,Bytes(einkwave::LevelBytes,0x6c));
            auto begin=std::chrono::steady_clock::now();
            if(num(r[1]))g.mixer.dispatchRegal({g.buffers[0],g.buffers[1],g.buffers[2],einkwave::RegalOperation(num(r[2]))});
            else{einkwave::MixRequest q;q.oldLevels=g.buffers[0];q.newLevels=g.buffers[1];q.mixed=g.buffers[2];q.operation=einkwave::MixOperation(num(r[2]));
                q.region={num(r[9]),num(r[10]),num(r[11]),num(r[12])};if(r[7]!="-")q.nextState=g.buffers[3];g.mixer.dispatch(q);}
            g.fence();auto actual=g.download(2,seed.size());write(out/(r[0]+".bin"),actual);
            auto check="{\"name\":"+quote(r[0])+",\"kind\":\"mix\","+diff(actual,read(assets/r[6]),mismatches);
            if(r[7]!="-"){auto state=g.download(3,einkwave::LevelBytes);write(out/(r[0]+"_state.bin"),state);check+=",\"state\":{"+diff(state,read(assets/r[7]),mismatches)+"}";}
            check+=",\"elapsed_ms\":"+std::to_string(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count())+"}";
            checks.push_back(check);std::cout<<check<<std::endl;
        }
        for(const auto&r:scans){
            if(r.size()!=22)throw std::runtime_error("scan TSV columns");auto begin=std::chrono::steady_clock::now();auto actual=g.scan(r,assets);
            write(out/(r[0]+".bin"),actual);auto check="{\"name\":"+quote(r[0])+",\"kind\":\"scan\","+diff(actual,read(assets/r[16]),mismatches)+
                ",\"elapsed_ms\":"+std::to_string(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count())+"}";
            checks.push_back(check);std::cout<<check<<std::endl;
        }
        std::string json="{\"schema\":2,\"scope\":\"candidate_waveform_ops_stock_bytecode\",\"renderer\":"+quote(reinterpret_cast<const char*>(glGetString(GL_RENDERER)))+
            ",\"version\":"+quote(reinterpret_cast<const char*>(glGetString(GL_VERSION)))+",\"gl_errors\":0,\"mismatches\":"+std::to_string(mismatches)+",\"checks\":[";
        for(size_t i=0;i<checks.size();i++){if(i)json+=',';json+=checks[i];}json+="]}\n";write(out/"probe_result.json",Bytes(json.begin(),json.end()));return mismatches?2:0;
    }catch(const std::exception&e){std::cerr<<"waveform_ops_probe: "<<e.what()<<std::endl;return 1;}
}
