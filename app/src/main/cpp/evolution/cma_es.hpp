#pragma once
#include <array>
#include <cstdint>
namespace girassol::evolution {
constexpr int dimensions=8,population=10,state_size=170;
using Vector=std::array<double,dimensions>;
using Population=std::array<Vector,population>;
struct CmaState {
    Vector mean{},sigma_path{},covariance_path{};
    std::array<double,64> covariance{};
    double sigma=.3;
    int generation=0;
    Population candidates{};
};
bool initialize(CmaState& state,const Vector& mean,std::uint64_t seed);
// Maximize scores; rank-mu covariance adaptation and cumulative step size control.
bool advance(CmaState& state,const std::array<double,population>& scores,std::uint64_t seed);
bool valid(const CmaState& state);
}
