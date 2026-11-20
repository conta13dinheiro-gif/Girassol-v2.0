#include "cognitive/deterministic_engines.hpp"

#include <cassert>
#include <cmath>

int main() {
    using girassol::cognition::SubjectiveOpinion;
    using girassol::cognition::averageFusion;
    using girassol::cognition::cumulativeFusion;
    using girassol::cognition::discountTrust;

    const SubjectiveOpinion trust{0.8f, 0.1f, 0.1f, 0.5f};
    const SubjectiveOpinion source{0.6f, 0.2f, 0.2f, 0.4f};
    const auto discounted = discountTrust(trust, source);
    const auto cumulative = cumulativeFusion(source, discounted);
    const auto averaged = averageFusion(source, discounted);
    assert(discounted.valid() && cumulative.valid() && averaged.valid());
    assert(discounted.belief <= source.belief);
    assert(cumulative.uncertainty <= source.uncertainty);
    const SubjectiveOpinion dogmaticA{1.0f, 0.0f, 0.0f, 0.5f};
    const SubjectiveOpinion dogmaticB{0.0f, 1.0f, 0.0f, 0.5f};
    assert(cumulativeFusion(dogmaticA, dogmaticB).valid());

    girassol::cognition::DeterministicEngineInput input{};
    input.threat = 0.08f;
    input.socialWarmth = 0.82f;
    input.surprise = 0.12f;
    input.novelty = 0.65f;
    input.predictionError = 0.10f;
    input.observedOutcome = 1.0f;
    input.dt = 1.0f / 60.0f;

    auto state = input.priorState;
    girassol::cognition::DeterministicEngineOutput output{};
    for (int tick = 0; tick < 180; ++tick) {
        input.priorState = state;
        output = girassol::cognition::stepDeterministicEngines(input);
        state = output.nextState;
        for (const auto value : state) assert(std::isfinite(value) && value >= 0.0f && value <= 1.0f);
        for (const auto value : output.signals) assert(std::isfinite(value) && value >= -1.0f && value <= 1.0f);
    }

    assert(output.signals[0] > 0.60f); // Tsetlin reflex confidence
    assert(output.signals[1] > 0.0f);  // autotelic TTT curiosity
    assert(output.signals[2] > 0.8f);  // episodic MemRL Q
    assert(output.signals[3] > 0.5f);  // discounted subjective opinion
    assert(output.signals[4] >= 0.0f && output.signals[4] <= 1.0f); // variational F
    assert(output.signals[5] >= 0.0f && output.signals[5] <= 1.0f); // expected G
    assert(output.signals[6] < 0.30f); // calibrated Brier score
    assert(output.signals[7] == 1.0f); // four deterministic promotion e-gates

    // A high-threat, poorly calibrated, surprising observation must close the gate.
    input.priorState = state;
    input.threat = 1.0f;
    input.surprise = 1.0f;
    input.predictionError = 1.0f;
    input.observedOutcome = -1.0f;
    const auto rejected = girassol::cognition::stepDeterministicEngines(input);
    assert(rejected.signals[7] == 0.0f);

    // Identical state and inputs are bitwise deterministic.
    const auto again = girassol::cognition::stepDeterministicEngines(input);
    assert(rejected.nextState == again.nextState);
    assert(rejected.signals == again.signals);
    return 0;
}
