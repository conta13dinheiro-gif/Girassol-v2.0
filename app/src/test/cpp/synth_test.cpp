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
    // The active Trautonium ratios change the generated spectrum deterministically.
    Synth equalTuning(71), customTuning(71);
    Parameters equalParams=p, customParams=p;
    customParams.ratios={1.0f,1.2f,1.4f,1.6f,1.8f,2.0f,2.2f,2.4f};
    assert(equalTuning.render(b.data(),frames,sr,equalParams));
    assert(customTuning.render(c.data(),frames,sr,customParams));
    assert(b!=c);
    Synth independentPhase(85), collectivePhase(85);
    Parameters individualParams=p, collectiveParams=p;
    individualParams.synchrony=0.0f;
    collectiveParams.synchrony=1.0f;
    assert(independentPhase.render(b.data(),frames,sr,individualParams));
    assert(collectivePhase.render(c.data(),frames,sr,collectiveParams));
    assert(b!=c);
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

    // The binaural approximation responds to spatial direction and preserves deterministic output.
    Synth fromLeft(123), fromRight(123);
    Parameters leftParams=p, rightParams=p;
    leftParams.left_steps=1.0f; leftParams.right_steps=0.0f;
    rightParams.left_steps=0.0f; rightParams.right_steps=1.0f;
    assert(fromLeft.render(b.data(),frames,sr,leftParams));
    assert(fromRight.render(c.data(),frames,sr,rightParams));
    assert(b!=c);

    // Conversation activity ducks the subharmonic bed without muting the whole ambience.
    Synth noConversation(321), conversation(321);
    Parameters quiet=p, speaking=p;
    quiet.social=0.0f; speaking.social=1.0f;
    assert(noConversation.render(b.data(),frames,sr,quiet));
    assert(conversation.render(c.data(),frames,sr,speaking));
    assert(b!=c);

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
