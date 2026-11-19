#include "mind/plasticity.hpp"
#include <cassert>
#include <cmath>
#include <cstring>
#include <limits>
using namespace girassol::mind;
int main() {
    Frame initial;
    initial.weights.fill(.1); initial.input.fill(.5); initial.hidden.fill(.1);
    for(int i=168;i<176;++i) initial.weights[i]=1;
    auto a=initial,b=initial;
    assert(advance(a) && advance(b)); assert(a.weights==b.weights && a.hidden==b.hidden && a.scores==b.scores);
    assert(a.weights==initial.weights && a.hidden!=initial.hidden);
    a=initial; a.learn=true; a.reward=.7; a.novelty=1;
    const auto weights=a.weights; assert(advance(a)); assert(a.weights!=weights);
    b=initial; b.learn=true; b.reward=-.7; b.novelty=0; assert(advance(b));
    assert(a.weights[184]>weights[184] && b.weights[184]<weights[184]);
    // The semi-implicit solver and Oja updates remain finite through sustained plasticity.
    for(int i=0;i<20000;++i) { a.action=i%8; a.reward=std::sin(i*.03); assert(advance(a)); }
    for(auto w:a.weights) assert(std::isfinite(w) && std::abs(w)<=8);
    for(auto h:a.hidden) assert(std::isfinite(h) && std::abs(h)<=1.00001);
    a=initial; a.weights[168]=0; auto before=a; assert(!advance(a)); assert(a.weights==before.weights && a.hidden==before.hidden);
    a=initial; a.input[3]=std::numeric_limits<double>::quiet_NaN(); assert(!advance(a));
    a=initial; a.hidden[2]=2; assert(!advance(a));
    a=initial; a.action=8; assert(!advance(a));
    a=initial; a.reward=std::numeric_limits<double>::infinity(); assert(!advance(a));
}
