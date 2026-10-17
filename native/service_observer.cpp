// Root-only test observer/control. Toggle only the version-locked RW mode word.
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>
#include <ctime>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <algorithm>
#include <array>
#include <fstream>
#include <iostream>
#include <iomanip>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

static uint64_t now(){timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return uint64_t(t.tv_sec)*1000000000+t.tv_nsec;}
static std::string read(const std::string&p){std::ifstream f(p);std::ostringstream o;o<<f.rdbuf();return o.str();}
static long number(const std::string&p){return std::strtol(read(p).c_str(),nullptr,10);}
struct Proc {uint64_t start=0,cpu=0;std::string name;};
using Processes=std::map<int,Proc>;
static Processes processes(){
    Processes all;DIR*d=opendir("/proc");if(!d)throw std::runtime_error("opendir proc");
    while(auto*e=readdir(d)){
        int pid=atoi(e->d_name);if(pid<=0)continue;auto line=read("/proc/"+std::to_string(pid)+"/stat");
        auto a=line.find('('),b=line.rfind(')');if(a==std::string::npos||b==std::string::npos)continue;
        std::istringstream in(line.substr(b+2));std::vector<std::string>v;std::string word;while(in>>word)v.push_back(word);
        if(v.size()<22)continue;
        auto name=read("/proc/"+std::to_string(pid)+"/cmdline");name=name.substr(0,name.find('\0'));if(name.empty())name=line.substr(a+1,b-a-1);
        all[pid]={std::stoull(v[19]),std::stoull(v[11])+std::stoull(v[12]),name};
    }closedir(d);return all;
}
static std::array<uint64_t,8>cpu(){
    std::ifstream f("/proc/stat");std::string name;f>>name;std::array<uint64_t,8>a{};for(auto&v:a)f>>v;return a;
}
static std::string jsonString(const std::string&s){std::string o="\"";for(char c:s){if(c=='"'||c=='\\')o+='\\';if(static_cast<unsigned char>(c)>=32)o+=c;}return o+'"';}
static void changeMode(int pid,int mode){
    if(mode<0||mode>2)throw std::runtime_error("mode must be 0, 1 or 2");
    std::ifstream maps("/proc/"+std::to_string(pid)+"/maps");std::string line;uintptr_t base=0,helperA=0,helperB=0;
    while(std::getline(maps,line)){
        unsigned long a,b,off;char perms[8];if(sscanf(line.c_str(),"%lx-%lx %7s %lx",&a,&b,perms,&off)!=4)continue;
        if(line.find("/vendor/lib64/hw/hwcomposer.ums512.so")!=std::string::npos&&off==0)base=a;
        if(line.find("/vendor/lib64/hw/libeinkgpu.so")!=std::string::npos){if(!helperA||a<helperA)helperA=a;helperB=std::max(helperB,uintptr_t(b));}
    }
    if(!base||!helperA)throw std::runtime_error("expected service/helper mappings absent");
    int fd=open(("/proc/"+std::to_string(pid)+"/mem").c_str(),O_RDWR|O_CLOEXEC);if(fd<0)throw std::runtime_error("open service mem");
    uint64_t pointers[9],old;
    if(pread(fd,pointers,sizeof(pointers),base+0x6300000)!=sizeof(pointers)||pread(fd,&old,8,base+0x6300080)!=8||old>2)throw std::runtime_error("mode metadata invalid");
    for(auto p:pointers)if(p<helperA||p>=helperB)throw std::runtime_error("hook pointer outside helper mappings");
    uint64_t value=mode;
    if(pwrite(fd,&value,8,base+0x6300080)!=8)throw std::runtime_error("write mode word");
    if(pread(fd,&old,8,base+0x6300080)!=8||old!=value)throw std::runtime_error("verify mode word");close(fd);
    std::cout<<"mode="<<mode<<" pid="<<pid<<" base="<<std::hex<<base<<std::endl;
}
static int status(int pid){
    std::ifstream maps("/proc/"+std::to_string(pid)+"/maps");std::string line;uintptr_t base=0;
    bool helper=false;while(std::getline(maps,line)){
        unsigned long a,b,off;char perms[8];if(sscanf(line.c_str(),"%lx-%lx %7s %lx",&a,&b,perms,&off)!=4)continue;
        if(line.find("/vendor/lib64/hw/hwcomposer.ums512.so")!=std::string::npos&&off==0)base=a;
        if(line.find("libeinkgpu.so")!=std::string::npos)helper=true;
    }
    std::array<uint64_t,12> words{};
    int fd=open(("/proc/"+std::to_string(pid)+"/mem").c_str(),O_RDONLY|O_CLOEXEC);
    bool readable=fd>=0&&base&&pread(fd,words.data(),sizeof(words),base+0x6300100)==sizeof(words);
    if(fd>=0)close(fd);
    int version=readable&&words[0]==0x45494e4b47505503ULL?3:
                readable&&words[0]==0x45494e4b47505502ULL?2:0;
    bool valid=version!=0;
    std::cout<<"{\"pid\":"<<pid<<",\"helper_loaded\":"<<(helper?"true":"false")
       <<",\"telemetry_valid\":"<<(valid?"true":"false")<<",\"version\":"<<version;
    const char*names[]={"gpu_pixel_waves","gpu_scan_frames","cpu_waves","gpu_errors","last_wave","path_bits","last_scan_ns","pixel_readback_bytes","pixel_upload_bytes","input_gpu_frames","input_cpu_events"};
    for(size_t i=1;i<words.size();i++)std::cout<<",\""<<names[i-1]<<"\":"<<((valid&&(version>=3||i<10))?words[i]:0);
    std::cout<<"}"<<std::endl;return valid?0:2;
}
static int observe(int seconds,const std::string&dir,int hwc,int sf,int app){
    int hz=sysconf(_SC_CLK_TCK);double uptime=0;std::istringstream(read("/proc/uptime"))>>uptime;
    auto previous=processes();auto initialCpu=cpu();uint64_t begin=now(),last=begin;
    std::map<int,uint64_t>accum;std::map<int,std::string>names;std::ofstream csv(dir+"/observer.csv");
    csv<<"time_ns,interval_ms,system_busy_cpu_ms,hwc_cpu_ms,sf_cpu_ms,app_cpu_ms,other_process_cpu_ms,cpu0_khz,cpu6_khz,cpu0_min,cpu6_min,gpu_hz,soc_mC,cluster1_mC,gpu_mC,battery_mC\n";
    auto lastCpu=initialCpu;std::cout<<"READY "<<begin<<std::endl;
    for(int sample=1;sample<=seconds;sample++){
        uint64_t target=begin+sample*1000000000ULL;timespec t{time_t(target/1000000000),long(target%1000000000)};
        clock_nanosleep(CLOCK_MONOTONIC,TIMER_ABSTIME,&t,nullptr);
        auto current=processes();auto currentCpu=cpu();uint64_t stamp=now();std::map<int,uint64_t>deltas;
        for(auto&item:current){auto p=previous.find(item.first);uint64_t delta=0;
            if(p!=previous.end()&&p->second.start==item.second.start)delta=item.second.cpu-p->second.cpu;
            else if(item.second.start>=uint64_t(uptime*hz))delta=item.second.cpu;
            deltas[item.first]=delta;accum[item.first]+=delta;names[item.first]=item.second.name;
        }
        uint64_t busy=0;for(int i:{0,1,2,5,6,7})busy+=currentCpu[i]-lastCpu[i];
        uint64_t others=0;for(auto&x:deltas)if(x.first!=hwc&&x.first!=sf&&x.first!=app&&x.first!=getpid())others+=x.second;
        auto ms=[&](uint64_t ticks){return ticks*1000./hz;};
        csv<<stamp<<','<<(stamp-last)/1e6<<','<<ms(busy)<<','<<ms(deltas[hwc])<<','<<ms(deltas[sf])<<','<<ms(deltas[app])<<','<<ms(others);
        for(auto path:{"/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq","/sys/devices/system/cpu/cpu6/cpufreq/scaling_cur_freq",
                      "/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq","/sys/devices/system/cpu/cpu6/cpufreq/scaling_min_freq",
                      "/sys/class/devfreq/60000000.gpu/cur_freq","/sys/class/thermal/thermal_zone0/temp","/sys/class/thermal/thermal_zone14/temp",
                      "/sys/class/thermal/thermal_zone6/temp","/sys/class/power_supply/battery/temp"})csv<<','<<number(path);
        csv<<'\n';csv.flush();previous=std::move(current);lastCpu=currentCpu;last=stamp;
    }
    auto lastCpuTotal=cpu();uint64_t busy=0;for(int i:{0,1,2,5,6,7})busy+=lastCpuTotal[i]-initialCpu[i];
    std::vector<std::pair<int,uint64_t>>rank(accum.begin(),accum.end());std::sort(rank.begin(),rank.end(),[](auto&a,auto&b){return a.second>b.second;});
    std::ofstream result(dir+"/observer.json");result<<std::setprecision(12)<<"{\"begin_ns\":"<<begin<<",\"end_ns\":"<<last<<",\"hz\":"<<hz<<",\"system_busy_cpu_ms\":"<<busy*1000./hz<<",\"hwc_pid\":"<<hwc<<",\"sf_pid\":"<<sf<<",\"app_pid\":"<<app<<",\"processes\":[";
    bool first=true;for(auto&x:rank){if(x.second==0)continue;if(!first)result<<',';first=false;
        result<<"{\"pid\":"<<x.first<<",\"name\":"<<jsonString(names[x.first])<<",\"cpu_ms\":"<<x.second*1000./hz<<'}';}
    result<<"]}\n";std::cout<<"DONE "<<last<<std::endl;return 0;
}
int main(int argc,char**argv){try{
    if(argc==3&&std::string(argv[1])=="status")return status(atoi(argv[2]));
    if(argc==4&&std::string(argv[1])=="mode"){changeMode(atoi(argv[2]),atoi(argv[3]));return 0;}
    if(argc==7&&std::string(argv[1])=="observe")return observe(atoi(argv[2]),argv[3],atoi(argv[4]),atoi(argv[5]),atoi(argv[6]));
    throw std::runtime_error("usage: service_observer mode PID 0|1|2 / observe SECONDS DIRECTORY HWC SF APP");
}catch(const std::exception&e){std::cerr<<e.what()<<std::endl;return 1;}}
