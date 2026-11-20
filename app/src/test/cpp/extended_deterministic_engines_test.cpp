
#include "cognitive/deterministic_engines.hpp"
#include <cassert>
#include <cmath>
#include <cstdint>
#include <vector>

int main() {
    using namespace extended;
    TsetlinMachine tm;
    assert(tm.add({1,20,0x3u,true}));
    assert(tm.add({2,22,0x1u,false}));
    const auto bytes=serializeGrst(tm);
    TsetlinMachine restored;
    assert(deserializeGrst(bytes.data(),bytes.size(),restored));
    assert(restored.count==2 && restored.predict(0x3u)==0.0f);
    const auto positiveState = restored.clauses[0].state;
    restored.observe(0x3u, 1.0f);
    assert(restored.clauses[0].state >= positiveState);
    const auto negativeState = restored.clauses[1].state;
    restored.observe(0x3u, -1.0f);
    assert(restored.clauses[1].state >= negativeState);
    auto damaged=bytes; damaged[0]^=1;
    assert(!deserializeGrst(damaged.data(),damaged.size(),restored));
    assert(!deserializeGrst(bytes.data(),bytes.size()-1,restored));

    PrioritizedReplayTree replay;
    replay.update(4,1.0f); replay.update(9,3.0f);
    assert(std::fabs(replay.total()-4.0f)<1e-6f);
    assert(replay.sample(0.5f)==4 && replay.sample(2.5f)==9);

    OnlineEwc ewc;
    std::array<float,32> p{}, f{}; p[0]=1.0f; f[0]=2.0f;
    ewc.consolidate(p,f,0.0f);
    auto moved=p; moved[0]=2.0f;
    assert(std::fabs(ewc.penalty(moved,1.0f)-2.0f)<1e-6f);

    Signature256 a{},b{}; b.words[0]=0xf0f0u;
    assert(hammingDistance(a,b)==8u);
    assert(reciprocalRankFusion(0,0)>reciprocalRankFusion(4,4));
    assert(hawkesDecay(1.0f,1.0f,1.0f,0.0f)<1.0f);

    const CoreSelf self({1,0,0,0,0,0,0,0});
    std::array<PianoCandidate,8> candidates{};
    candidates[5]={.9f,.8f,.9f};
    candidates[2]={.8f,.9f,.8f};
    candidates[7]={.7f,.7f,.7f};
    const auto top=pianoTop3(candidates,self);
    assert(top[0]==5 && top[1]==2 && top[2]==7);
    assert(expectedFreeEnergy(candidates[5],self)<expectedFreeEnergy(candidates[0],self));
    return 0;
}
