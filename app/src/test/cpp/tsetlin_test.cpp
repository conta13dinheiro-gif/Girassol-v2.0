#include "cognitive/tsetlin_machine.hpp"
#include <cassert>
#include <iostream>
int main() {
    girassol::TsetlinMachine m;
    assert(m.vote(0)==0);
    for(unsigned i=0;i<60000;++i) { unsigned x=i%4; m.train(x,((x&1)!=0)!=((x&2)!=0),i+1); }
    for(unsigned x=0;x<4;++x) {
        std::cout << x << ": " << m.vote(x) << '\n';
        assert((m.vote(x)>0)==(((x&1)!=0)!=((x&2)!=0)));
    }
    girassol::TsetlinMachine copy; assert(copy.load(m.state));
    for(unsigned x=0;x<65536;++x) {
        assert(copy.vote(x)==m.vote(x));
        auto classify=[](int v) { return v>4 ? 1 : v< -4 ? -1 : 0; };
        assert(classify(m.vote(x))==classify(m.vote(x,true)));
    }
    auto invalid=m.state; invalid[4]=0;assert(!copy.load(invalid));
    assert(copy.state==m.state);
    invalid[4]=129;assert(!copy.load(invalid));
    assert(!copy.load(std::span<const std::uint8_t>(m.state).first(4)));
    girassol::TsetlinMachine replay;
    for(unsigned i=0;i<60000;++i) { unsigned x=i%4; replay.train(x,((x&1)!=0)!=((x&2)!=0),i+1); }
    assert(replay.state==m.state);
    girassol::TsetlinMachine noisy;
    std::uint64_t noise=7331;
    for(unsigned i=0;i<100000;++i) {
        noise^=noise<<13;noise^=noise>>7;noise^=noise<<17;
        unsigned x=(noise>>8)%4;
        bool y=((x&1)!=0)!=((x&2)!=0);
        noisy.train(x,(noise%10==0) ? !y : y,noise);
    }
    for(unsigned x=0;x<4;++x) assert((noisy.vote(x)>0)==(((x&1)!=0)!=((x&2)!=0)));
}
