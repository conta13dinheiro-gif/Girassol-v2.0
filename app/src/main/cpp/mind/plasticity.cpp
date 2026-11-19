#include "plasticity.hpp"
#define TINYMIND_ENABLE_FLOAT 1
#define TINYMIND_ENABLE_STD 1
#include "../third_party/tinymind/ltc.hpp"
#include <algorithm>
#include <cmath>

namespace girassol::mind {
bool advance(Frame& f) noexcept {
    for (auto v:f.weights) if (!std::isfinite(v) || std::abs(v)>8) return false;
    for (auto v:f.hidden) if (!std::isfinite(v) || std::abs(v)>1.00001) return false;
    for (auto v:f.input) if (!std::isfinite(v) || v<0 || v>1) return false;
    for (int i=168;i<176;++i) if (f.weights[i]<.2 || f.weights[i]>8) return false;
    if (!std::isfinite(f.reward) || std::abs(f.reward)>5 || !std::isfinite(f.novelty) || f.novelty<0 || f.novelty>1 || f.action<0 || f.action>=8) return false;
    auto next=f;
    const auto before=f.hidden;
    tinymind::ltc::LtcCell<inputs,neurons>::step<double>(next.weights.data(),next.input.data(),before.data(),next.hidden.data(),.25,4);
    if (f.learn) {
        const double eta=.015*(std::clamp(f.reward,-1.0,1.0)+.35*f.novelty);
        for (int i=0;i<neurons;++i) {
            const double post=next.hidden[i];
            for (int j=0;j<inputs;++j) {
                auto& w=next.weights[i*inputs+j];
                w=std::clamp(w+eta*post*(next.input[j]-post*w),-4.0,4.0);
            }
            for (int j=0;j<neurons;++j) {
                auto& w=next.weights[96+i*neurons+j];
                w=std::clamp(w+eta*post*(before[j]-post*w),-4.0,4.0);
            }
            auto& w=next.weights[184+f.action*neurons+i];
            w=std::clamp(w+.025*(f.reward+.35*f.novelty)*post,-4.0,4.0);
        }
    }
    for (int a=0;a<8;++a) {
        next.scores[a]=0;
        for (int i=0;i<neurons;++i) next.scores[a]+=next.weights[184+a*neurons+i]*next.hidden[i];
        if (!std::isfinite(next.scores[a])) return false;
    }
    for (auto v:next.hidden) if (!std::isfinite(v) || std::abs(v)>1.00001) return false;
    f=next; return true;
}
}
