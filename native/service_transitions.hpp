#ifndef EINK_SERVICE_TRANSITIONS_HPP
#define EINK_SERVICE_TRANSITIONS_HPP
// 只提供核与只读控制适配；stock 仍执行所有计数、模式恢复与提交。
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace einktransition {
constexpr size_t StockLevelBytes=0xfd200;
constexpr uint32_t MixChangeSlot=19, MixResetSlot=20, OldFillSlot=21;
constexpr uintptr_t OldFillCaller=0x978ac;
enum class Phase:uint32_t {Unsupported=0, Blank=1, Reset=2, Transition=3, Normal=4};
enum class Boundary:uint32_t {None=0, TransitionToNormal=1, NormalCommit=2, SpecialEnd=3};
struct Control {
    uint32_t width=0,height=0,mode=0,blank=0,reset=0,transition=0,submode=0,region=0;
    uint32_t normalTotal=0,normalRemaining=0,transitionTotal=0,transitionRemaining=0;
    uintptr_t normalMix=0,transitionMix=0,regalMix=0,newLevels=0,oldLevels=0,a2Bound=0;
};
template<class T> inline T readField(const void*ctl,size_t off){
    T value{};std::memcpy(&value,static_cast<const uint8_t*>(ctl)+off,sizeof(value));return value;
}
// 调用方必须持有 stock ctl 的读权限；这里不解引用像素/LUT 指针。
inline Control snapshot(const void*ctl){
    Control c{};if(!ctl)return c;
    c.width=readField<uint32_t>(ctl,0xbc);c.height=readField<uint32_t>(ctl,0xc0);
    c.mode=readField<uint8_t>(ctl,0x15c);c.blank=readField<uint32_t>(ctl,0x280);
    c.reset=readField<uint32_t>(ctl,0x270);c.transition=readField<uint32_t>(ctl,0x278);
    c.submode=readField<uint32_t>(ctl,0x27c);c.region=readField<uint32_t>(ctl,0x2c8);
    c.normalTotal=readField<uint32_t>(ctl,0x160);c.normalRemaining=readField<uint32_t>(ctl,0xc8);
    c.transitionTotal=readField<uint32_t>(ctl,0x288);c.transitionRemaining=readField<uint32_t>(ctl,0x28c);
    c.normalMix=readField<uintptr_t>(ctl,0x40);c.transitionMix=readField<uintptr_t>(ctl,0x48);
    c.regalMix=readField<uintptr_t>(ctl,0x50);c.newLevels=readField<uintptr_t>(ctl,0x58);
    c.oldLevels=readField<uintptr_t>(ctl,0x60);c.a2Bound=readField<uintptr_t>(ctl,0x2d0);return c;
}
struct Segment {
    Phase phase=Phase::Unsupported;Boundary boundary=Boundary::None;
    uint32_t total=0,remaining=0,localFrame=0,lutOffset=0,mixOffset=0;
    bool valid=false,pixelY8Edge=false,regionOverlay=false;
    // remaining==0 仍有一次扫描；transition 这次 return 1，normal 才 commit/return 0。
    bool terminal()const{return valid&&remaining==0;}
    uint32_t window(uint32_t requested=8)const{
        if(!valid||!requested)return 0;
        return uint32_t(uint64_t(requested)<uint64_t(remaining)+1?requested:uint64_t(remaining)+1);
    }
};
inline Segment segment(const Control&c){
    Segment s{};s.total=c.normalTotal;s.remaining=c.normalRemaining;
    if(c.blank){s.phase=Phase::Blank;s.boundary=Boundary::SpecialEnd;}
    else if(c.reset==1){s.phase=Phase::Reset;s.lutOffset=0x90;s.mixOffset=0x40;s.boundary=Boundary::SpecialEnd;}
    else if(c.transition){
        s.phase=Phase::Transition;s.total=c.transitionTotal;s.remaining=c.transitionRemaining;
        s.lutOffset=(c.transition==1&&(c.submode==2||c.submode==3))?0x88:0x68;
        s.mixOffset=0x48;s.boundary=Boundary::TransitionToNormal;
    }else{
        s.phase=Phase::Normal;s.mixOffset=0x40;s.boundary=Boundary::NormalCommit;
        if(c.mode==1)s.lutOffset=0x80;
        else if(c.mode==2)s.lutOffset=0x68;
        else if(c.mode==6||c.mode==8)s.lutOffset=0x70;
        else if(c.mode==5){s.lutOffset=0x78;s.mixOffset=0x50;s.pixelY8Edge=c.region!=1;s.regionOverlay=c.region==1;}
        else return Segment{};
    }
    s.valid=s.remaining<=s.total;
    if(s.valid)s.localFrame=s.total-s.remaining;
    return s;
}
// 对接现有 beginWave/renderWindow 时要求从段首开始；中途接管需桥接或另加 frameBase。
// terminalNeutral 必须来自实际选中 live LUT/延迟语义的证据，不能凭 remaining==0 推断。
inline bool existingRollingEligible(const Control&c,const Segment&s,bool terminalNeutral){
    return s.valid&&s.phase!=Phase::Blank&&c.width==1440&&c.height==720&&
        s.localFrame==0&&s.total>0&&s.total<159&&terminalNeutral;
}
// 只验证本适配层负责的计数/阶段边界。mode/count/history 的其他 stock 写入仍保留。
inline bool verifyStep(const Control&before,const Control&after,int result){
    const auto s=segment(before);if(!s.valid)return false;
    if(s.phase==Phase::Transition){
        if(result!=1||after.normalRemaining!=before.normalRemaining||after.normalTotal!=before.normalTotal||
           after.transitionTotal!=before.transitionTotal)return false;
        return s.remaining?after.transitionRemaining==s.remaining-1&&after.transition==before.transition:
                           after.transitionRemaining==0&&after.transition==0;
    }
    return result==(s.remaining?1:0)&&after.normalRemaining==(s.remaining?s.remaining-1:0)&&
           after.normalTotal==before.normalTotal&&after.transition==before.transition&&
           after.transitionRemaining==before.transitionRemaining;
}
inline bool mixChangeCall(const Control&c,const void*src,const void*dst,uint32_t w,uint32_t h){
    return c.width==1440&&c.height==720&&w==c.width&&h==c.height&&
        uintptr_t(src)==c.newLevels&&uintptr_t(dst)==c.normalMix;
}
inline bool mixResetCall(const Control&c,const void*src,const void*dst,uint32_t w,uint32_t h){
    return c.width==1440&&c.height==720&&w==c.width&&h==c.height&&
        uintptr_t(src)==c.oldLevels&&uintptr_t(dst)==c.transitionMix;
}
inline bool oldFillCall(const Control&c,uintptr_t caller,const void*dst,int value,size_t bytes){
    return caller==OldFillCaller&&c.width==1440&&c.height==720&&c.transition!=0&&
        c.transitionRemaining==0&&uintptr_t(dst)==c.oldLevels&&
        (value&255)==30&&bytes==StockLevelBytes;
}
} // namespace einktransition

#ifndef EINK_TRANSITIONS_CONTROL_ONLY
#include "service_waveform.hpp"
namespace einktransition {
// 一个线程/一个 GL context 使用；不承担 CPU mirror 与状态提交。
inline constexpr const char*FillShader=R"(#version 310 es
precision highp int;
layout(local_size_x=128)in;
layout(std430,binding=0)buffer Destination{uint destination[];};
uniform uint byteCount;uniform uint byteValue;
void main(){uint g=gl_GlobalInvocationID.x;uint words=byteCount/4u;
uint tail=byteCount%4u;if(g>words||(g==words&&tail==0u))return;
uint v=(byteValue&255u)*16843009u;
if(g<words)destination[g]=v;
else{uint mask=(1u<<(tail*8u))-1u;destination[g]=(destination[g]&~mask)|(v&mask);}
})";
class Kernels {
    einkwave::Mixer mixer_;GLuint fillProgram_=0;
public:
    Kernels()=default;Kernels(const Kernels&)=delete;Kernels&operator=(const Kernels&)=delete;
    void release(){mixer_.release();if(fillProgram_)glDeleteProgram(fillProgram_);fillProgram_=0;}
    void mixChange(GLuint newLevels,GLuint normalMixed){
        einkwave::MixRequest r{};r.oldLevels=newLevels;r.mixed=normalMixed;
        r.operation=einkwave::MixOperation::ModeChange;mixer_.dispatch(r);
    }
    void mixReset(GLuint oldLevels,GLuint transitionMixed){
        einkwave::MixRequest r{};r.oldLevels=oldLevels;r.mixed=transitionMixed;
        r.operation=einkwave::MixOperation::Reset;mixer_.dispatch(r);
    }
    void fill(GLuint buffer,size_t bytes,uint8_t value){
        if(bytes>UINT32_MAX)throw std::runtime_error("transition fill size overflow");
        if(!einkwave::storage(buffer,(bytes+3u)&~size_t(3)))throw std::runtime_error("transition fill capacity/padding");
        if(!bytes)return;
        if(!fillProgram_)fillProgram_=einkwave::linkShader(GL_COMPUTE_SHADER,FillShader);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,buffer);glUseProgram(fillProgram_);
        glUniform1ui(glGetUniformLocation(fillProgram_,"byteCount"),uint32_t(bytes));
        glUniform1ui(glGetUniformLocation(fillProgram_,"byteValue"),value);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
        glDispatchCompute(GLuint((uint64_t(bytes)+511u)/512u),1,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
        einkwave::check("transition GPU fill");
    }
    // 原厂 0x978ac 清 1036800 bytes 为 30（Y8 white），不是 0xff。
    void oldFillWhite(GLuint oldLevels){fill(oldLevels,StockLevelBytes,30);}
    // 若 stock 后续有 CPU consumer，调用方必须 fence+回读后再放行，或保留 stock clear。
    void clearA2Bound(GLuint bound,uint32_t width,uint32_t height){fill(bound,size_t(width)*height,0);}
};
} // namespace einktransition
#endif // EINK_TRANSITIONS_CONTROL_ONLY
#endif // EINK_SERVICE_TRANSITIONS_HPP
