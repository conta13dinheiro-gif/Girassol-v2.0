#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>

namespace girassol::cognition {

struct DeterministicEngineInput {
    std::array<float, 8> priorState{};
    float threat = 0.0f;
    float socialWarmth = 0.0f;
    float surprise = 0.0f;
    float novelty = 0.0f;
    float predictionError = 0.0f;
    float observedOutcome = 0.0f; // -1 failure, 0.2 reserved, 0.5 move, 1 success
    float dt = 1.0f / 60.0f;
};

struct DeterministicEngineOutput {
    std::array<float, 8> nextState{};
    // Tsetlin confidence, TTT curiosity, episodic Q, subjective probability,
    // variational F, expected G, Brier score, bounded-RSI e-gate.
    std::array<float, 8> signals{};
};

inline float unit(float value) noexcept {
    return std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f;
}

inline DeterministicEngineOutput stepDeterministicEngines(
    const DeterministicEngineInput& input
) noexcept {
    DeterministicEngineOutput output{};
    const float dt = std::isfinite(input.dt) ? std::clamp(input.dt, 0.0001f, 0.1f) : 1.0f / 60.0f;
    const float threat = unit(input.threat);
    const float warmth = unit(input.socialWarmth);
    const float surprise = unit(input.surprise);
    const float novelty = unit(input.novelty);
    const float predictionError = unit(input.predictionError);
    const float outcome = std::isfinite(input.observedOutcome)
        ? std::clamp(input.observedOutcome, -1.0f, 1.0f) : -1.0f;

    // Tsetlin automata: bounded continuous counters represent deterministic clause votes.
    float clauseSum = 0.0f;
    const std::array<float, 4> features{1.0f - threat, warmth, 1.0f - surprise, novelty};
    for (std::size_t i = 0; i < 4; ++i) {
        const float prior = unit(input.priorState[i]);
        const float target = features[i];
        const float direction = outcome >= 0.5f ? target : -target;
        output.nextState[i] = unit(prior + dt * 2.0f * direction);
        clauseSum += output.nextState[i];
    }
    const float reflexConfidence = clauseSum * 0.25f;

    // Autotelic TTT: Ebbinghaus decay plus replay priority for surprising, novel outcomes.
    const float priorRetention = unit(input.priorState[4]);
    const float retention = unit(priorRetention * std::exp(-dt / 900.0f)
        + dt * (0.08f * novelty + 0.05f * std::fabs(outcome)));
    output.nextState[4] = retention;
    const float curiosityBonus = unit((1.0f - retention) * 0.55f + novelty * 0.35f + surprise * 0.10f);

    // Episodic MemRL Q: one bounded TD update, keyed by the canonical action outcome.
    const float oldQ = unit(input.priorState[5]) * 2.0f - 1.0f;
    const float q = std::clamp(oldQ + 0.20f * (outcome - oldQ), -1.0f, 1.0f);
    output.nextState[5] = unit((q + 1.0f) * 0.5f);

    // Jøsang subjective opinion: evidence is discounted by relational warmth.
    const float evidence = unit(0.15f + 0.70f * warmth + 0.15f * retention);
    const float priorUncertainty = unit(input.priorState[6]);
    const float uncertainty = unit(0.75f * priorUncertainty + 0.25f * (1.0f - evidence));
    const float evidenceMass = 1.0f - uncertainty;
    const float belief = evidenceMass * unit((outcome + 1.0f) * 0.5f);
    const float disbelief = evidenceMass - belief;
    const float opinionMass = belief + disbelief + uncertainty;
    const float projected = unit((belief + 0.5f * uncertainty) / std::max(opinionMass, 1e-6f));
    output.nextState[6] = uncertainty;

    // Variational free energy and expected free energy remain finite and bounded.
    const float freeEnergy = unit(0.55f * predictionError + 0.30f * threat + 0.15f * surprise);
    const float expectedFreeEnergy = unit(0.45f * threat + 0.30f * (1.0f - reflexConfidence)
        + 0.25f * (1.0f - novelty));

    // Brier calibration is updated from the prior success probability and observed outcome.
    const float predictedSuccess = unit((oldQ + 1.0f) * 0.5f);
    const float targetSuccess = unit((outcome + 1.0f) * 0.5f);
    const float previousBrier = unit(input.priorState[7]);
    const float calibrationError = predictedSuccess - targetSuccess;
    const float brier = unit(0.8f * previousBrier + 0.2f * calibrationError * calibrationError);
    output.nextState[7] = brier;

    // Four promotion e-gates: calibrated evidence, bounded surprise, low free energy,
    // and no severe threat. This is a deterministic proposal gate, not self-modifying code.
    const bool evidenceGate = reflexConfidence >= 0.62f && projected >= 0.45f;
    const bool calibrationGate = brier <= 0.30f;
    const bool stabilityGate = freeEnergy <= 0.72f && surprise <= 0.85f;
    const bool safetyGate = threat <= 0.80f;
    const float promotionGate = evidenceGate && calibrationGate && stabilityGate && safetyGate ? 1.0f : 0.0f;

    output.signals = {
        reflexConfidence,
        curiosityBonus,
        q,
        projected,
        freeEnergy,
        expectedFreeEnergy,
        brier,
        promotionGate
    };
    return output;
}

} // namespace girassol::cognition
