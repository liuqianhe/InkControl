// 独立 Mali/GLES 测试：不替换服务，不访问面板；逐字节核对延迟波形。
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
        if(argc!=2)throw std::runtime_error("usage: motion_ops_probe NEW_OUTPUT_DIR");
        fs::path out=argv[1];if(!fs::create_directory(out))throw std::runtime_error("new output required");
        GPU g;size_t mismatch=0,checks=0,frames=0;
        constexpr int active=10;
        Bytes mix(einkwave::LevelBytes),lut(active*65536),templ(einkwave::FrameBytes);
        for(size_t i=0;i<mix.size();i++)mix[i]=uint8_t((i*73+19)&255);
        for(size_t i=0;i<lut.size();i++)lut[i]=uint8_t((i*13+i/65536*7)&15);
        for(size_t i=0;i<templ.size();i++)templ[i]=uint8_t((i*17+3)&255);
        g.upload(0,mix);g.upload(1,lut);g.upload(2,templ);
        for(const char*style:{"ink","dots","scan","blind","gcsweep"})
        for(const char*dir:{"ltr","rtl","ttb","btt"}){
            MotionReq q;std::strcpy(q.style,style);std::strcpy(q.dir,dir);q.spread=5;q.ox=97;q.oy=1331;
            int delay=buildMotionMap(q);
            if(delay<1||delay>q.spread||smapHost.size()!=einkwave::LevelBytes)throw std::runtime_error("invalid map");
            g.upload(3,smapHost);glUseProgram(g.frame);
            g.ui("indexLayout",0);g.ui("activeFrames",active);g.ui("bandDirection",0);g.ui("bandFrames",0);
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
                                size_t pair=px&~size_t(1);uint16_t index=uint16_t(mix[pair])|(uint16_t(mix[pair+1])<<8);
                                uint8_t code=(lut[size_t(ff)*65536+index]>>(k%2?0:2))&3;
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
            std::cout<<"{\"style\":"<<quote(style)<<",\"dir\":"<<quote(dir)<<",\"frames\":"<<active+delay+1<<",\"mismatches\":"<<local<<"}"<<std::endl;
        }
        std::string result="{\"checks\":"+std::to_string(checks)+",\"frames\":"+std::to_string(frames)+",\"mismatches\":"+std::to_string(mismatch)+",\"panel_access\":false}\n";
        write(out/"assessment.json",Bytes(result.begin(),result.end()));return mismatch?2:0;
    }catch(const std::exception&e){std::cerr<<e.what()<<std::endl;return 1;}
}
