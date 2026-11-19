#include "audio/synth.hpp"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <limits>
#include <vector>
using namespace girassol::audio;
int main() {
    constexpr int sr=48000, frames=sr*4;
    std::vector<float> a(frames*2),b(frames*2),c(frames*2);
    Synth silent; assert(silent.render(a.data(),frames,sr,{}));
    for (float v : a) assert(v==0);
    Synth one(42),two(42),three(43);
    Parameters p{1,1,0,.6f,.4f};
    assert(one.render(a.data(),frames,sr,p));
    // Callback size cannot change the signal for the same controls/sample rate.
    int done=0;
    while (done<frames) { const int n=std::min(193,frames-done); assert(two.render(b.data()+done*2,n,sr,p)); done+=n; }
    assert(a==b);
    assert(three.render(c.data(),frames,sr,p)); assert(a!=c);
    double difference=0,energy=0; float jump=0;
    for (int i=0;i<frames*2;++i) {
        assert(std::isfinite(a[i]) && std::abs(a[i])<=.35f);
        energy+=a[i]*a[i];
        if (i%2==0) { difference+=std::abs(a[i]-a[i+1]); if (i>=2) jump=std::max(jump,std::abs(a[i]-a[i-2])); }
    }
    assert(energy>1 && difference>1); assert(jump<.05f);
    // Muting smoothly settles to silence rather than keeping the noise bed active.
    assert(one.render(a.data(),frames,sr,{}));
    for (int i=frames*2-sr;i<frames*2;++i) assert(std::abs(a[i])<1e-10f);
    for (int rate : {8000,44100,48000,96000,192000}) {
        Synth s(9); Parameters bad{std::numeric_limits<float>::quiet_NaN(),-1,100,
            std::numeric_limits<float>::infinity(),-1};
        assert(s.render(a.data(),1024,rate,bad));
        for (int i=0;i<2048;++i) assert(a[i]==0 && std::isfinite(a[i]));
        assert(s.render(a.data(),1024,rate,{100,100,100,100,100}));
        for (int i=0;i<2048;++i) assert(std::isfinite(a[i]) && std::abs(a[i])<=.35f);
    }
    Synth reactive(42); auto reactiveParams=p;
    reactiveParams.weather=1; reactiveParams.tension=1; reactiveParams.milestone=1;
    assert(reactive.render(a.data(),frames,sr,reactiveParams));
    Synth baseline(42); assert(baseline.render(b.data(),frames,sr,p)); assert(a!=b);
    for(float v:a) assert(std::isfinite(v) && std::abs(v)<=.35f);
    std::fill(a.begin(),a.end(),.5f);
    assert(!one.render(nullptr,1,sr,p)); assert(!one.render(a.data(),-1,sr,p));
    assert(!one.render(a.data(),1,0,p)); assert(!one.render(a.data(),1048577,sr,p));
    for (float v : a) assert(v==.5f);
}
