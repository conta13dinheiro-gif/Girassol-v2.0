#include "mind/vector_memory.hpp"
#include <cassert>
#include <cmath>
#include <limits>
#include <vector>
using namespace girassol::memory;
int main() {
    std::array<float,16> q{}; q[0]=1;
    std::vector<Entry> rows(3); rows[0]={q,0,.2f}; rows[1]={q,0,1}; rows[2]={q,7200,1};
    std::array<Hit,3> hits{};
    assert(recall(q,rows,hits)==3); assert(hits[0].index==1); assert(hits[1].index==0); assert(hits[2].index==2);
    auto prior=hits; assert(recall(q,rows,hits)==3);
    for(int i=0;i<3;++i) assert(hits[i].index==prior[i].index && hits[i].score==prior[i].score);
    rows[0].embedding[1]=std::numeric_limits<float>::quiet_NaN();
    assert(recall(q,rows,hits)==-1); assert(hits[0].score==prior[0].score);
    rows.clear(); assert(recall(q,rows,hits)==0);
    for(int i=0;i<2000;++i) {
        std::array<float,16> v{}; v[0]=std::cos(i*.001f); v[1]=std::sin(i*.001f);
        rows.push_back({v,0,.5});
    }
    assert(recall(q,rows,hits)==3 && hits[0].index==0);
    for(const auto& hit:hits) assert(std::isfinite(hit.score) && hit.score>=0 && hit.score<=1);
}
