#pragma once
#include <array>
#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <span>

namespace girassol {
// Binary classifier: 16 clauses, 16 input bits and their complements, N=64, s=3, T=4.
class TsetlinMachine {
public:
    static constexpr int clauses=16, features=16, literals=32, threshold=4;
    static constexpr std::size_t bytes=4+clauses*literals;
    std::array<std::uint8_t,bytes> state{};
    TsetlinMachine() { state.fill(64); state[0]='G';state[1]='T';state[2]=1;state[3]=features; }
    bool load(std::span<const std::uint8_t> data) {
        if(data.size()!=bytes || data[0]!='G' || data[1]!='T' || data[2]!=1 || data[3]!=features) return false;
        for(std::size_t i=4;i<bytes;++i) if(data[i]<1 || data[i]>128) return false;
        std::copy(data.begin(),data.end(),state.begin());return true;
    }
    bool clause(int c,std::uint32_t x,bool training=false) const {
        std::uint32_t include=0;
        for(int l=0;l<literals;++l) if(state[4+c*literals+l]>64) include |= std::uint32_t{1}<<l;
        const auto truth=(x&65535u)|((~x&65535u)<<16);
        return (include!=0 || training) && (truth&include)==include;
    }
    int vote(std::uint32_t x,bool early=false) const {
        int result=0;
        for(int c=0;c<clauses;++c) {
            if(clause(c,x)) result += c%2 ? -1 : 1;
            if(early && std::abs(result)>threshold+clauses-c-1) break;
        }
        return result;
    }
    void train(std::uint32_t x,bool positive,std::uint64_t seed) {
        auto rng=seed ? seed : UINT64_C(0x9e3779b97f4a7c15);
        auto sample=[&](unsigned numerator,unsigned denominator) {
            rng^=rng<<13;rng^=rng>>7;rng^=rng<<17;
            return rng%denominator<numerator;
        };
        const int v=std::clamp(vote(x),-threshold,threshold);
        const int sign=positive ? 1 : -1;
        const auto truth=(x&65535u)|((~x&65535u)<<16);
        for(int c=0;c<clauses;++c) {
            if(!sample(threshold-sign*v,2*threshold)) continue;
            const bool fires=clause(c,x,true);
            const bool typeI=(c%2==0)==positive;
            for(int l=0;l<literals;++l) {
                auto& a=state[4+c*literals+l]; const bool literal=(truth&(std::uint32_t{1}<<l))!=0;
                if(typeI) {
                    if(fires && literal) { if(sample(2,3) && a<128) ++a; }
                    else if(sample(1,3) && a>1) --a;
                } else if(fires && !literal && a<=64) ++a;
            }
        }
    }
};
}
