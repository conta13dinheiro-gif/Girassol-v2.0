#include "mind/semantic_index.hpp"
#include <array>
#include <cassert>
#include <cmath>
#include <limits>
#include <random>
#include <vector>
int main() {
    constexpr int dimensions=64,count=400;
    std::vector<float> rows(count*dimensions);
    std::mt19937 random(42);std::uniform_real_distribution<float> uniform(-1,1);
    for(int i=0;i<count;++i) { double norm=0;for(int d=0;d<dimensions;++d) { const float value=uniform(random);rows[i*dimensions+d]=value;norm+=value*value; }for(int d=0;d<dimensions;++d) rows[i*dimensions+d]/=std::sqrt(norm); }
    const std::span<const float> query(rows.data()+17*dimensions,dimensions);
    std::array<girassol::semantic::Hit,8> hits{};
    assert(girassol::semantic::search(query,rows,dimensions,hits)==8);
    assert(hits[0].index==17 && hits[0].similarity>.999);
    auto broken=rows;broken[3]=std::numeric_limits<float>::quiet_NaN();
    assert(girassol::semantic::search(query,broken,dimensions,hits)==-1);
    assert(girassol::semantic::search(query,rows,0,hits)==-1);
    assert(girassol::semantic::search(query,rows,63,hits)==-1);
}
