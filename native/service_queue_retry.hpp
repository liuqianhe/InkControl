#pragma once
#include <stdexcept>

namespace einkimport {
// The consumer may switch raw/pending while a producer waits for the selected
// queue mutex. Re-select only BEFORE touching a target or issuing GPU work.
// Bound retries so persistent queue corruption still reaches the normal guard.
template<class ReadKind,class LockQueue,class Publish>
unsigned publishStableQueue(ReadKind readKind,LockQueue lockQueue,Publish publish) {
    for(unsigned retry=0;retry<4;++retry){
        auto kind=readKind();
        auto held=lockQueue(kind);
        if(readKind()!=kind)continue;
        publish(kind);
        return retry;
    }
    throw std::runtime_error("stock queue unstable after 4 selections");
}
}
