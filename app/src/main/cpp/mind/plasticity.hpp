#pragma once
#include <array>
namespace girassol::mind {
constexpr int parameters=248, neurons=8, inputs=12;
struct Frame {
    std::array<double,parameters> weights{};
    std::array<double,neurons> hidden{};
    std::array<double,inputs> input{};
    std::array<double,8> scores{};
    double reward=0, novelty=0;
    int action=0;
    bool learn=false;
};
// Semi-implicit LTC dynamics plus reward-modulated Hebbian/Oja updates.
bool advance(Frame& frame) noexcept;
}
