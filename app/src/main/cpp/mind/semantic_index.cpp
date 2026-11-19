#include "semantic_index.hpp"
#include <hnswlib/hnswlib.h>
#include <algorithm>
#include <cmath>
#include <vector>
namespace girassol::semantic {
int search(std::span<const float> query,std::span<const float> rows,int dimensions,std::span<Hit> output) {
    if(dimensions<1 || dimensions>32768 || query.size()!=static_cast<size_t>(dimensions) || rows.size()%dimensions!=0) return -1;
    const size_t count=rows.size()/dimensions;
    if(count>2048 || rows.size()>2*1024*1024 || output.size()>count) return -1;
    const auto normalized=[](std::span<const float> values) {
        double norm=0;for(auto value:values) { if(!std::isfinite(value)) return false;norm+=double(value)*value; }
        return std::abs(norm-1)<.01;
    };
    if(!normalized(query)) return -1;
    for(size_t row=0;row<count;++row) if(!normalized(rows.subspan(row*dimensions,dimensions))) return -1;
    if(output.empty()) return 0;
    hnswlib::InnerProductSpace space(dimensions);
    hnswlib::HierarchicalNSW<float> index(&space,count,16,100,42);
    for(size_t row=0;row<count;++row) index.addPoint(rows.data()+row*dimensions,row);
    index.setEf(std::max<size_t>(64,output.size()));
    auto hits=index.searchKnn(query.data(),output.size());
    const auto found=hits.size();
    while(!hits.empty()) {
        const auto position=hits.size()-1;const auto value=hits.top();hits.pop();
        output[position]={static_cast<int>(value.second),std::clamp(1-value.first,-1.f,1.f)};
    }
    return static_cast<int>(found);
}
}
