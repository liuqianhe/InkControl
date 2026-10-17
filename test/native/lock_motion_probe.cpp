// 独立 GPU 核对：真实服务不上机，逐字节验证阅读模式边缘复制与完整延迟波形。
#define main waveform_probe_unused_main
#include "waveform_ops_probe.cpp"
#undef main
#include <sys/system_properties.h>
#include <cstdio>
#include <cmath>
static uint64_t waveId=0;
static uint64_t now(){return 0;}
template<class T>static T field(void*p,int off){T v{};std::memcpy(&v,static_cast<uint8_t*>(p)+off,sizeof(v));return v;}
#define LOG(...) do{}while(0)
#include "service_motion.hpp"

int main(int argc,char**argv){
    try{
        if(argc!=2)throw std::runtime_error("usage: lock_motion_probe NEW_OUTPUT_DIR");
        fs::path out=argv[1];if(!fs::create_directory(out))throw std::runtime_error("new output required");
        GPU g;size_t mismatch=0,checks=0,frames=0;
        for(int layout:{0,1}){
            int active=layout==1?38:10;
            Bytes mix(einkwave::LevelBytes*(layout==1?2:1)),lut(active*65536),templ(einkwave::FrameBytes);
            for(size_t i=0;i<mix.size();i++)mix[i]=uint8_t((i*73+19)&255);
            for(size_t i=0;i<lut.size();i++)lut[i]=uint8_t((i*13+i/65536*7)&(layout==1?3:15));
            for(size_t i=0;i<templ.size();i++)templ[i]=uint8_t((i*17+3)&255);
            g.upload(0,mix);g.upload(1,lut);g.upload(2,templ);
            for(const char*style:{"ink","dots","scan","blind"})
            for(const char*dir:{"ttb","btt"}){
                MotionReq q;std::strcpy(q.style,style);std::strcpy(q.dir,dir);q.spread=layout==1?12:5;q.ox=360;q.oy=720;
                int delay=buildMotionMap(q);
                if(delay<1||delay>q.spread||smapHost.size()!=einkwave::LevelBytes)throw std::runtime_error("invalid map");
                g.upload(3,smapHost);glUseProgram(g.frame);
                einkwave::WaveRequest request{};
                request.gpuIndices=g.buffers[0];request.indexBytes=mix.size();request.liveLut=lut.data();request.lutBytes=lut.size();
                request.controlTemplate=reinterpret_cast<const uint32_t*>(templ.data());request.templateBytes=templ.size();
                request.cpuStartMap=smapHost.data();request.startMapBytes=smapHost.size();
                request.activeFrames=active;request.totalFrames=active+delay+1;request.maxDelay=delay;
                request.layout=layout==1?einkwave::IndexLayout::PixelY8Edge:einkwave::IndexLayout::PairY4;
                if(!einkwave::validWave(request))throw std::runtime_error("delay request rejected");
                request.layout=einkwave::IndexLayout::PixelY8;request.maskRegion=true;request.overlay12=true;
                if(einkwave::validWave(request))throw std::runtime_error("unsafe regional delay accepted");
                g.ui("indexLayout",layout);g.ui("activeFrames",active);g.ui("bandDirection",0);g.ui("bandFrames",0);
                g.bi("motion",true);g.bi("maskRegion",false);g.bi("overlay12",false);g.bi("twoRegions",false);
                size_t local=0;
                for(int f=0;f<=active+delay;f++){
                    g.ui("frameNumber",f);g.bi("neutral",f==active+delay);
                    glBindFramebuffer(GL_FRAMEBUFFER,g.fbo);glViewport(0,0,einkwave::OutputWidth,einkwave::OutputHeight);
                    glDrawArrays(GL_TRIANGLES,0,3);g.fence();Bytes actual=g.pixels();
                    for(int y=0;y<725;y++)for(int x=0;x<384;x++){
                        size_t p=size_t(y)*384+x;uint32_t expected=0;std::memcpy(&expected,templ.data()+p*4,4);
                        if(y>=5&&x<360){
                            uint8_t data=0;
                            for(int k=0;k<4;k++){
                                size_t px=size_t(y-5)*1440+x*4+k;int ff=f-smapHost[px];
                                if(ff>=0&&ff<active&&f!=active+delay){
                                    size_t src=px;
                                    if(layout==1){if(x==0&&k==0)src+=2;if(x==359&&k==3)src-=1;}
                                    size_t indexOffset=layout==1?src*2:(px&~size_t(1));
                                    uint16_t index=uint16_t(mix[indexOffset])|(uint16_t(mix[indexOffset+1])<<8);
                                    uint8_t code=lut[size_t(ff)*65536+index];
                                    if(layout==0)code=(code>>(k%2?0:2))&3;
                                    data|=code<<((3-k)*2);
                                }
                            }
                            expected=(expected&0xffffff00u)|data;
                        }
                        uint32_t got=0;std::memcpy(&got,actual.data()+p*4,4);local+=got!=expected;
                    }
                    frames++;
                }
                mismatch+=local;checks++;
                std::cout<<"{\"layout\":"<<layout<<",\"style\":"<<quote(style)<<",\"dir\":"<<quote(dir)<<",\"active\":"<<active<<",\"delay\":"<<delay<<",\"mismatches\":"<<local<<"}"<<std::endl;
            }
        }
        std::string result="{\"checks\":"+std::to_string(checks)+",\"frames\":"+std::to_string(frames)+",\"mismatches\":"+std::to_string(mismatch)+",\"panel_access\":false}\n";
        write(out/"assessment.json",Bytes(result.begin(),result.end()));return mismatch?2:0;
    }catch(const std::exception&e){std::cerr<<e.what()<<std::endl;return 1;}
}
