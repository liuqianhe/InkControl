#include "../../native/service_queue_retry.hpp"
#include <cstdlib>
#define CHECK(condition) do { if(!(condition)) { std::cerr << "CHECK failed: " #condition << "\n"; std::abort(); } } while(0)
#include <iostream>
#include <mutex>
#include <vector>
#include <atomic>
#include <condition_variable>
#include <thread>

int main(){
    unsigned passed=0;
    for(int initial:{0,1}){
        int state=initial,locks=0,published=0;std::mutex queues[2];
        // Deterministically switch the consumer's state during the first lock
        // acquisition. Publication must happen once under the NEW queue lock.
        einkimport::publishStableQueue([&](){return state;},[&](int kind){
            std::unique_lock<std::mutex> lock(queues[kind]);
            if(++locks==1)state^=1;
            return lock;
        },[&](int kind){CHECK(kind==state);CHECK(kind!=(initial));
            CHECK(queues[initial].try_lock());queues[initial].unlock();++published;});
        CHECK(locks==2&&published==1);++passed;
        for(auto&queue:queues){CHECK(queue.try_lock());queue.unlock();}
    }
    {
        std::mutex queues[2],gate;std::condition_variable observed;
        std::atomic<int> state{0};bool selected=false;unsigned published=0,retries=0;
        std::unique_lock<std::mutex> blocked(queues[0]);
        std::thread producer([&](){
            retries=einkimport::publishStableQueue([&](){int kind=state.load();
                {std::lock_guard<std::mutex> held(gate);selected=true;}observed.notify_one();return kind;},
              [&](int kind){return std::unique_lock<std::mutex>(queues[kind]);},
              [&](int kind){CHECK(kind==1);++published;});
        });
        {std::unique_lock<std::mutex> held(gate);observed.wait(held,[&](){return selected;});}
        state.store(1);blocked.unlock();producer.join();
        CHECK(retries==1&&published==1);++passed;
    }
    {
        int reads=0,published=0;std::mutex queue;
        try{
            einkimport::publishStableQueue([&](){return (++reads)&1;},
              [&](int){return std::unique_lock<std::mutex>(queue);},[&](int){++published;});
            CHECK(false);
        }catch(const std::runtime_error&error){CHECK(std::string(error.what()).find("unstable")!=std::string::npos);}
        CHECK(reads==8&&published==0);CHECK(queue.try_lock());queue.unlock();++passed;
    }
    {
        unsigned calls=0;std::mutex queue;
        try{
            einkimport::publishStableQueue([](){return 0;},
              [&](int){return std::unique_lock<std::mutex>(queue);},[&](int){++calls;throw std::runtime_error("GPU fault");});
            CHECK(false);
        }catch(const std::runtime_error&error){CHECK(std::string(error.what())=="GPU fault");}
        CHECK(calls==1);CHECK(queue.try_lock());queue.unlock();++passed;
    }
    {
        unsigned calls=0;std::mutex queue;
        einkimport::publishStableQueue([](){return 1;},
          [&](int){return std::unique_lock<std::mutex>(queue);},[&](int kind){CHECK(kind==1);++calls;});
        CHECK(calls==1);++passed;
    }
    std::cout<<"queue_retry: "<<passed<<" scenarios passed\n";
}
