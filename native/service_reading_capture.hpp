
#pragma once
#include <array>
static bool readingBitmap=false,readingSkip=false,readingDone=false;
static uint64_t readingWave=0;
static int readingRequested=0;
static char readingToken[PROP_VALUE_MAX]={};
static Bytes readingRgba,readingBlob;
static std::array<Bytes,9> readingParts;
static std::array<uint64_t,32> readingHeader{};
static Bytes readingBuffer(GLuint id,size_t length){
    finishPixels();glBindBuffer(GL_SHADER_STORAGE_BUFFER,id);
    void*p=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,length,GL_MAP_READ_BIT);
    require(p,"reading diagnostic map");Bytes bytes(length);std::memcpy(bytes.data(),p,length);
    require(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER),"reading diagnostic unmap");
    telemetryBytes(T_READBACK,length);return bytes;
}
static void readingFinishInput(int mode){
    char token[PROP_VALUE_MAX]={};__system_property_get("debug.eink.reading.capture",token);
    if(std::strcmp(token,readingToken)){std::strcpy(readingToken,token);readingDone=false;readingRgba.clear();}
    readingRequested=mode;
    readingBitmap=load<uint8_t>(base+0x234209)!=0;
    readingSkip=!pixelCtl||field<int>(pixelCtl,0x278)||field<int>(pixelCtl,0x280)||field<int>(pixelCtl,0x270);
    if(readingDone||!readingBitmap||readingSkip||!preparedGray.ready||!pixelRgba||field<uint8_t>(pixelCtl,0x15c)!=5)return;
    char value[PROP_VALUE_MAX]={};__system_property_get("debug.eink.reading.capture",value);
    if(value[0]=='1')readingRgba.assign(static_cast<uint8_t*>(pixelRgba),static_cast<uint8_t*>(pixelRgba)+IMAGE_BYTES);
}
static void readingCaptureWindow(int index){
    if(readingDone||!readingBitmap||readingSkip||gpuFailed)return;
    auto&r=rollingWave.request;auto&p=pools[index];
    if(einktransition::segment(einktransition::snapshot(producerCtl)).phase!=einktransition::Phase::Normal||field<uint8_t>(producerCtl,0x15c)!=5||readingRgba.size()!=IMAGE_BYTES)return;
    char value[PROP_VALUE_MAX]={};__system_property_get("debug.eink.reading.capture",value);if(value[0]!='1')return;
    if(p.firstFrame==0){
        readingWave=r.wave;readingParts={};readingHeader={};
        readingHeader[0]=0x52454144494e4701ULL;readingHeader[1]=r.wave;
        readingHeader[2]=r.activeFrames;readingHeader[3]=r.totalFrames;readingHeader[4]=uint32_t(r.layout);
        readingHeader[5]=r.maskRegion;readingHeader[6]=r.overlay12;readingHeader[7]=r.twoRegions;
        readingHeader[8]=r.region.left;readingHeader[9]=r.region.top;readingHeader[10]=r.region.right;readingHeader[11]=r.region.bottom;
        readingHeader[12]=r.secondRegion.left;readingHeader[13]=r.secondRegion.top;readingHeader[14]=r.secondRegion.right;readingHeader[15]=r.secondRegion.bottom;
        readingHeader[16]=field<uint8_t>(producerCtl,0x15c);readingHeader[17]=readingBitmap;
        readingHeader[28]=readingRequested;readingHeader[29]=oldIndex;readingHeader[30]=sizeof(readingHeader);
        readingParts[0]=readingRgba;readingParts[1]=Bytes(static_cast<uint8_t*>(producerCtl)+0x168,static_cast<uint8_t*>(producerCtl)+0x268);
        readingParts[2]=readGpu(oldLevels());readingParts[3]=readGpu(newLevels());
        readingParts[4]=readingBuffer(r.gpuIndices?r.gpuIndices:rollingWave.indices,r.indexBytes);
        readingParts[5]=Bytes(r.liveLut,r.liveLut+size_t(r.activeFrames)*65536);
        readingParts[6]=readingBuffer(r.gpuLut?r.gpuLut:rollingWave.lut,size_t(r.activeFrames)*65536);
        auto*t=reinterpret_cast<const uint8_t*>(r.controlTemplate);readingParts[7]=Bytes(t,t+einkwave::FrameBytes);
    }
    if(readingWave!=r.wave)return;
    for(uint32_t i=0;i<p.count;i++){
        auto scan=p.frames[i]->snapshot();telemetryBytes(T_READBACK,scan.size());
        readingParts[8].insert(readingParts[8].end(),scan.begin(),scan.end());
    }
    if(p.firstFrame+p.count==r.totalFrames){
        for(size_t i=0;i<readingParts.size();i++)readingHeader[18+i]=readingParts[i].size();
        readingBlob.resize(sizeof(readingHeader));std::memcpy(readingBlob.data(),readingHeader.data(),sizeof(readingHeader));
        for(auto&part:readingParts)readingBlob.insert(readingBlob.end(),part.begin(),part.end());
        LOG("READING_SNAPSHOT wave=%llu ptr=%p bytes=%zu active=%u total=%u layout=%u region=%u request=%x",
            (unsigned long long)r.wave,readingBlob.data(),readingBlob.size(),r.activeFrames,r.totalFrames,uint32_t(r.layout),r.maskRegion,readingRequested);
        readingDone=true;readingParts={};readingRgba.clear();
    }
}
