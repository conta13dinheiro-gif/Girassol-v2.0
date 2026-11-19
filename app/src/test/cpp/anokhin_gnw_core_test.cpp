#include "cognitive/anokhin_gnw_core.hpp"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <vector>

using namespace girassol::psyche;

int main() {
    HormonalState hormones;
    for (int i = 0; i < 100000; ++i) {
        hormones.stepODE(1.0f / 60.0f, i % 2 == 0 ? 1.0f : 0.0f,
            i % 3 == 0 ? 1.0f : 0.0f, i % 5 == 0 ? 1.0f : 0.0f);
        assert(hormones.cortisol >= 0.0f && hormones.cortisol <= 1.0f);
        assert(hormones.oxytocin >= 0.0f && hormones.oxytocin <= 1.0f);
        assert(hormones.dopamine >= 0.0f && hormones.dopamine <= 1.0f);
    }

    AnokhinActionAcceptor acceptor;
    std::array<float,16> actual{};
    assert(acceptor.evaluateOutcome(actual) == 0.0f);
    actual[0] = 1.0f;
    assert(std::abs(acceptor.evaluateOutcome(actual) - 0.25f) < 1e-6f);
    actual.fill(1.0f);
    assert(std::abs(acceptor.evaluateOutcome(actual) - 1.0f) < 1e-6f);

    HumanSoulState soul;
    soul.ard.lastPredictionError = 1.0f;
    assert(soul.computeIgnition(1.0f, 1.0f));
    soul.ard.lastPredictionError = 0.0f;
    assert(!soul.computeIgnition(0.0f, 0.0f));
    assert(std::isfinite(soul.ignitionIndex));

    std::vector<HumanSoulState> synchronized(8);
    for (auto& citizen : synchronized) {
        citizen.kuramotoPhase = 0.0f;
        citizen.kuramotoNaturalFreq = 1.0f;
    }
    assert(std::abs(stepKuramotoEnsemble(synchronized, 1.0f, 1.0f/60.0f) - 1.0f) < 1e-6f);
    for (int i = 0; i < 40000; ++i) {
        synchronized[i % synchronized.size()].kuramotoNaturalFreq = 1.0f + (i % 7) * .001f;
        const float order = stepKuramotoEnsemble(synchronized, 2.0f, 1.0f/60.0f);
        assert(std::isfinite(order) && order >= 0.0f && order <= 1.0f);
        for (const auto& citizen : synchronized) {
            assert(std::isfinite(citizen.kuramotoPhase));
            assert(citizen.kuramotoPhase >= -3.141593f && citizen.kuramotoPhase <= 3.141593f);
        }
    }
    std::vector<HumanSoulState> empty;
    assert(stepKuramotoEnsemble(empty, 1.0f, 1.0f/60.0f) == 0.0f);
    assert(stepKuramotoEnsemble(synchronized, 1.0f, -1.0f) == 0.0f);
}
