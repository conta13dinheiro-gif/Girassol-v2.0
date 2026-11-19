#include "evolution/cma_es.hpp"
#include <cassert>
#include <cmath>
#include <limits>
using namespace girassol::evolution;
int main() {
    CmaState a,b; Vector start; start.fill(1.5);
    assert(initialize(a,start,777) && initialize(b,start,777)); assert(a.candidates==b.candidates);
    double best=100;
    for(int generation=0;generation<120;++generation) {
        std::array<double,population> scores{};
        for(int k=0;k<population;++k) { double loss=0; for(int i=0;i<8;++i) loss+=std::pow(a.candidates[k][i]-.1,2)*(i+1); scores[k]=-loss; best=std::min(best,loss); }
        assert(advance(a,scores,777) && advance(b,scores,777)); assert(a.candidates==b.candidates); assert(valid(a));
    }
    assert(best<1e-6);
    auto prior=a; std::array<double,population> invalid{}; invalid[0]=std::numeric_limits<double>::quiet_NaN();
    assert(!advance(a,invalid,777)); assert(a.candidates==prior.candidates && a.mean==prior.mean);
    a.covariance[0]=-1; assert(!advance(a,{},777));
    start[0]=3; assert(!initialize(a,start,3));
}
