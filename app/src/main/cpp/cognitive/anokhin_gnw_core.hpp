#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace girassol::psyche {

enum class AttachmentStyle : std::uint8_t {
    SECURE = 0,
    ANXIOUS = 1,
    AVOIDANT = 2,
    DISORGANIZED = 3
};

struct HormonalState {
    float cortisol = 0.25f;
    float oxytocin = 0.45f;
    float dopamine = 0.50f;

    void stepODE(float dt, float threatStimulus, float socialWarmth, float predictionSurprise) noexcept {
        if (!std::isfinite(dt) || dt <= 0.0f) return;
        dt = std::min(dt, 0.1f);
        const auto unit = [](float value) {
            return std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f;
        };
        threatStimulus = unit(threatStimulus);
        socialWarmth = unit(socialWarmth);
        predictionSurprise = unit(predictionSurprise);
        cortisol += dt * (0.35f * threatStimulus - 0.04f * (cortisol - 0.15f));
        oxytocin += dt * (0.28f * socialWarmth - 0.03f * (oxytocin - 0.30f));
        dopamine += dt * (0.45f * predictionSurprise - 0.08f * (dopamine - 0.40f));
        cortisol = unit(cortisol);
        oxytocin = unit(oxytocin);
        dopamine = unit(dopamine);
    }
};

struct AffectivePAD {
    float pleasure = 0.1f;
    float arousal = 0.2f;
    float dominance = 0.0f;
};

struct AnokhinActionAcceptor {
    std::array<float, 16> expectedSensory{};
    float lastPredictionError = 0.0f;

    float evaluateOutcome(const std::array<float, 16>& actualSensory) noexcept {
        double error = 0.0;
        for (std::size_t i = 0; i < expectedSensory.size(); ++i) {
            if (!std::isfinite(actualSensory[i]) || !std::isfinite(expectedSensory[i])) {
                lastPredictionError = 1.0f;
                return lastPredictionError;
            }
            const double delta = static_cast<double>(actualSensory[i] - expectedSensory[i]);
            error += delta * delta;
        }
        lastPredictionError = static_cast<float>(std::clamp(std::sqrt(error / 16.0), 0.0, 1.0));
        return lastPredictionError;
    }
};

struct HumanSoulState {
    std::uint64_t citizenId = 0;
    float ageYears = 0.0f;
    float biologicalHealth = 1.0f;
    AttachmentStyle attachment = AttachmentStyle::SECURE;
    std::array<float, 5> bigFive{0.5f, 0.5f, 0.5f, 0.5f, 0.5f};
    HormonalState hormones{};
    AffectivePAD pad{};
    AnokhinActionAcceptor ard{};
    float kuramotoPhase = 0.0f;
    float kuramotoNaturalFreq = 1.0f;
    float ignitionIndex = 0.0f;

    bool computeIgnition(float externalSalience, float somaticBias) noexcept {
        const float error = std::isfinite(ard.lastPredictionError)
            ? std::clamp(ard.lastPredictionError, 0.0f, 1.0f) : 1.0f;
        externalSalience = std::isfinite(externalSalience)
            ? std::clamp(externalSalience, 0.0f, 1.0f) : 0.0f;
        somaticBias = std::isfinite(somaticBias) ? somaticBias : 0.0f;
        const float rawDrive = 0.45f * error + 0.35f * externalSalience
            + 0.20f * std::fabs(somaticBias);
        const float exponent = std::clamp(-12.0f * (rawDrive - 0.52f), -40.0f, 40.0f);
        ignitionIndex = 1.0f / (1.0f + std::exp(exponent));
        return ignitionIndex > 0.65f;
    }
};

inline float stepKuramotoEnsemble(
    std::vector<HumanSoulState>& citizens,
    float couplingK,
    float dt
) noexcept {
    if (citizens.empty() || !std::isfinite(couplingK) || !std::isfinite(dt) || dt <= 0.0f) {
        return 0.0f;
    }
    dt = std::min(dt, 0.1f);
    couplingK = std::clamp(couplingK, 0.0f, 4.0f);
    double sumCos = 0.0;
    double sumSin = 0.0;
    for (const auto& citizen : citizens) {
        if (!std::isfinite(citizen.kuramotoPhase)) return 0.0f;
        sumCos += std::cos(citizen.kuramotoPhase);
        sumSin += std::sin(citizen.kuramotoPhase);
    }
    const double count = static_cast<double>(citizens.size());
    const double meanCos = sumCos / count;
    const double meanSin = sumSin / count;
    const double meanAngle = std::atan2(meanSin, meanCos);
    const double orderParameter = std::clamp(std::hypot(meanCos, meanSin), 0.0, 1.0);
    for (auto& citizen : citizens) {
        if (!std::isfinite(citizen.kuramotoNaturalFreq)) return 0.0f;
        citizen.kuramotoPhase += static_cast<double>(dt) * (
            static_cast<double>(citizen.kuramotoNaturalFreq)
            + static_cast<double>(couplingK) * orderParameter
                * std::sin(meanAngle - citizen.kuramotoPhase)
        );
        citizen.kuramotoPhase = std::remainder(citizen.kuramotoPhase, 6.2831853071795864769);
    }
    return static_cast<float>(orderParameter);
}

} // namespace girassol::psyche
