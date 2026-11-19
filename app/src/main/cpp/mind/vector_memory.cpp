#include "vector_memory.hpp"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>
#include <hnswlib/hnswlib.h>
namespace girassol::memory {
namespace {
bool normalized(const std::array<float,dimensions>& v) {
    float n=0; for(float x:v) { if(!std::isfinite(x)) return false; n+=x*x; }
    return std::abs(n-1.f)<.002f;
}
}
int recall(const std::array<float,dimensions>& query,std::span<const Entry> entries,std::span<Hit> output) {
    if(!normalized(query) || entries.size()>std::numeric_limits<hnswlib::tableint>::max()) return -1;
    for(const auto& e:entries) if(!normalized(e.embedding) || !std::isfinite(e.age) || e.age<0 || !std::isfinite(e.importance) || e.importance<0 || e.importance>1) return -1;
    if(entries.empty() || output.empty()) return 0;
    hnswlib::InnerProductSpace space(dimensions);
    hnswlib::HierarchicalNSW<float> index(&space,entries.size(),16,100,7331);
    for(size_t i=0;i<entries.size();++i) if(!index.addPointNoExceptions(entries[i].embedding.data(),i).ok()) return -1;
    const size_t candidates=std::min(entries.size(),std::max<size_t>(16,std::min(output.size(),entries.size())*4));
    index.setEf(std::max<size_t>(64,candidates));
    auto found=index.searchKnnCloserFirstNoExceptions(query.data(),candidates);
    if(!found.ok()) return -1;
    std::vector<Hit> ranked; ranked.reserve(candidates);
    for(const auto& [distance,label]:found.value()) {
        const auto& e=entries[label];
        const float similarity=std::clamp(1-distance,0.f,1.f);
        ranked.push_back({static_cast<int>(label),.45f*similarity+.3f*e.importance+.25f*std::exp(-e.age/720.f)});
    }
    std::sort(ranked.begin(),ranked.end(),[](Hit a,Hit b) { return a.score!=b.score ? a.score>b.score : a.index<b.index; });
    const auto count=std::min(output.size(),ranked.size());
    std::copy_n(ranked.begin(),count,output.begin());
    return static_cast<int>(count);
}
}
