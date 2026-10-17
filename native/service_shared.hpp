#pragma once
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <GLES2/gl2ext.h>
#include <drm/drm.h>
#include <drm/drm_fourcc.h>
#include <linux/dma-buf.h>
#include <sys/socket.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <memory>
#include <vector>
#include <stdexcept>
#include <string>
#include <condition_variable>
// 1 = verify every GPU wave against a CPU shadow (debug build only).
#ifndef EINK_VERIFY_ALL
#define EINK_VERIFY_ALL 0
#endif
using Bytes=std::vector<uint8_t>;
inline void require(bool ok,const std::string&what){if(!ok)throw std::runtime_error(what+" errno="+std::to_string(errno));}
struct Gpu {static void check(const char*where){auto e=glGetError();if(e)throw std::runtime_error(std::string(where)+" GL="+std::to_string(e));}};
// Public socket API transfers only the native handle/FDs, never image pixels.
int dmaFd(AHardwareBuffer*b,size_t minimumSize){
    int sv[2];require(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,sv),"socketpair");
    int r=AHardwareBuffer_sendHandleToUnixSocket(b,sv[0]);
    if(r){close(sv[0]);close(sv[1]);throw std::runtime_error("AHB send "+std::to_string(r));}
    close(sv[0]);std::vector<int>fds;
    for(;;){
        char bytes[16384];alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int)*64)];
        iovec io{bytes,sizeof(bytes)};msghdr msg{};msg.msg_iov=&io;msg.msg_iovlen=1;
        msg.msg_control=control;msg.msg_controllen=sizeof(control);
        ssize_t n=recvmsg(sv[1],&msg,MSG_CMSG_CLOEXEC);if(n<=0)break;
        require(!(msg.msg_flags&MSG_CTRUNC),"truncated handle FDs");
        for(auto*c=CMSG_FIRSTHDR(&msg);c;c=CMSG_NXTHDR(&msg,c)){
            if(c->cmsg_level==SOL_SOCKET&&c->cmsg_type==SCM_RIGHTS){
                auto count=(c->cmsg_len-CMSG_LEN(0))/sizeof(int);auto*p=reinterpret_cast<int*>(CMSG_DATA(c));
                for(size_t i=0;i<count;i++)fds.push_back(p[i]);
            }
        }
    }
    close(sv[1]);int chosen=-1;
    for(int fd:fds){
        off_t size=lseek(fd,0,SEEK_END);lseek(fd,0,SEEK_SET);
        if(chosen<0&&size>=static_cast<off_t>(minimumSize))chosen=fd;else close(fd);
    }
    require(chosen>=0,"no large dma-buf FD in AHB handle");return chosen;
}

struct Shared{
    AHardwareBuffer*ahb=nullptr;AHardwareBuffer_Desc desc{};
    EGLDisplay display;EGLImageKHR image=EGL_NO_IMAGE_KHR;GLuint texture=0,fbo=0;
    int fd=-1,drm=-1;uint32_t gem=0,fb=0;void*raw=MAP_FAILED;size_t length=0;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage=nullptr;
    Shared(EGLDisplay d,uint32_t w,uint32_t h,uint64_t usage,int drmFd=-1):display(d),drm(drmFd){
        desc.width=w;desc.height=h;desc.layers=1;desc.format=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;desc.usage=usage;
        int r=AHardwareBuffer_allocate(&desc,&ahb);
        if(r)throw std::runtime_error("AHB allocate "+std::to_string(r));
        AHardwareBuffer_describe(ahb,&desc);
        auto native=reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        auto create=reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
        destroyImage=reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
        auto bind=reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        require(native&&create&&destroyImage&&bind,"native EGL functions");
        EGLint attrs[]={EGL_IMAGE_PRESERVED_KHR,EGL_TRUE,EGL_NONE};
        image=create(d,EGL_NO_CONTEXT,EGL_NATIVE_BUFFER_ANDROID,native(ahb),attrs);
        if(image==EGL_NO_IMAGE_KHR)throw std::runtime_error("EGLImage error "+std::to_string(eglGetError()));
        glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        bind(GL_TEXTURE_2D,reinterpret_cast<GLeglImageOES>(image));Gpu::check("native texture");
        glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
        require(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"native framebuffer incomplete");
        if(drm>=0){
            length=size_t(desc.stride)*4*h;fd=dmaFd(ahb,length);
            drm_prime_handle prime{};prime.fd=fd;
            require(!ioctl(drm,DRM_IOCTL_PRIME_FD_TO_HANDLE,&prime),"DRM PRIME import");gem=prime.handle;
            drm_mode_fb_cmd2 cmd{};cmd.width=w;cmd.height=h;cmd.pixel_format=DRM_FORMAT_XBGR8888;
            cmd.handles[0]=gem;cmd.pitches[0]=desc.stride*4;
            require(!ioctl(drm,DRM_IOCTL_MODE_ADDFB2,&cmd),"DRM AddFB2 XB24");fb=cmd.fb_id;
            raw=mmap(nullptr,length,PROT_READ,MAP_SHARED,fd,0);require(raw!=MAP_FAILED,"raw dma-buf mmap");
        }
    }
    ~Shared(){
        if(raw!=MAP_FAILED)munmap(raw,length);
        if(fb)ioctl(drm,DRM_IOCTL_MODE_RMFB,&fb);
        if(gem){drm_gem_close c{};c.handle=gem;ioctl(drm,DRM_IOCTL_GEM_CLOSE,&c);}
        if(fd>=0)close(fd);
        if(fbo)glDeleteFramebuffers(1,&fbo);
        if(texture)glDeleteTextures(1,&texture);
        if(image!=EGL_NO_IMAGE_KHR&&destroyImage)destroyImage(display,image);
        if(ahb)AHardwareBuffer_release(ahb);
    }
    void seed(const Bytes&bytes){
        void*p=nullptr;require(!AHardwareBuffer_lock(ahb,AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,-1,nullptr,&p),"AHB seed lock");
        for(size_t y=0;y<desc.height;y++)std::memcpy(static_cast<uint8_t*>(p)+y*desc.stride*4,bytes.data()+y*desc.width*4,desc.width*4);
        require(!AHardwareBuffer_unlock(ahb,nullptr),"AHB seed unlock");
    }
    Bytes snapshot(){
        dma_buf_sync sync{};sync.flags=DMA_BUF_SYNC_START|DMA_BUF_SYNC_READ;
        require(!ioctl(fd,DMA_BUF_IOCTL_SYNC,&sync),"dma-buf read sync start");
        Bytes result(size_t(desc.width)*desc.height*4);
        for(size_t y=0;y<desc.height;y++)std::memcpy(result.data()+y*desc.width*4,static_cast<uint8_t*>(raw)+y*desc.stride*4,desc.width*4);
        sync.flags=DMA_BUF_SYNC_END|DMA_BUF_SYNC_READ;
        require(!ioctl(fd,DMA_BUF_IOCTL_SYNC,&sync),"dma-buf read sync end");return result;
    }
};
