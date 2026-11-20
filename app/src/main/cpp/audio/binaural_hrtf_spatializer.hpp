#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>

namespace girassol::audio {

struct BinauralFrame {
    float left = 0.0f;
    float right = 0.0f;
};

/**
 * Compact per-source binaural spatializer.
 *
 * Woodworth spherical-head ITD, far-ear ILD, distance attenuation and a
 * one-pole occlusion filter. Fixed-size history keeps the audio callback
 * allocation-free and lock-free.
 */
class BinauralHrtfSpatializer {
public:
    BinauralFrame process(
        float mono,
        float azimuthRadians,
        float distanceMeters,
        float occlusion,
        int sampleRate
    ) noexcept {
        if (!std::isfinite(mono) || !std::isfinite(azimuthRadians) ||
            !std::isfinite(distanceMeters) || !std::isfinite(occlusion) ||
            sampleRate < 8000 || sampleRate > 192000) return {};

        constexpr double pi = 3.14159265358979323846;
        constexpr double headRadiusMeters = 0.0875;
        constexpr double speedOfSound = 343.0;
        const double azimuth = std::clamp(
            static_cast<double>(azimuthRadians), -pi * 0.5, pi * 0.5
        );
        const double angle = std::abs(azimuth);
        const double clampedOcclusion = std::clamp(static_cast<double>(occlusion), 0.0, 1.0);
        const double distance = std::clamp(static_cast<double>(distanceMeters), 0.0, 1000.0);

        // Woodworth: ITD = a/c * (theta + sin(theta)).
        const double delaySamples = std::clamp(
            (headRadiusMeters / speedOfSound) * (angle + std::sin(angle)) * sampleRate,
            0.0,
            static_cast<double>(history_.size() - 2)
        );
        history_[write_] = mono;
        double read = static_cast<double>(write_) - delaySamples;
        while (read < 0.0) read += static_cast<double>(history_.size());
        const auto index = static_cast<std::size_t>(read);
        const auto next = (index + 1) % history_.size();
        const float fraction = static_cast<float>(read - static_cast<double>(index));
        const float farEar = history_[index] * (1.0f - fraction) + history_[next] * fraction;
        write_ = (write_ + 1) % history_.size();

        const double lateral = std::sin(angle);
        const float farGain = static_cast<float>(
            1.0 - 0.68 * std::pow(lateral, 1.35)
        );
        const float distanceGain = static_cast<float>(1.0 / (1.0 + 0.08 * distance * distance));
        const float nearRaw = mono * distanceGain;
        const float farRaw = farEar * farGain * distanceGain;

        const double desiredCutoff = 18000.0 * (1.0 - clampedOcclusion) + 1200.0 * clampedOcclusion;
        const double cutoff = std::min(desiredCutoff, sampleRate * 0.45);
        const float alpha = static_cast<float>(
            1.0 - std::exp(-2.0 * pi * cutoff / static_cast<double>(sampleRate))
        );

        const float leftRaw = azimuth >= 0.0 ? farRaw : nearRaw;
        const float rightRaw = azimuth >= 0.0 ? nearRaw : farRaw;
        leftFilter_ += alpha * (leftRaw - leftFilter_);
        rightFilter_ += alpha * (rightRaw - rightFilter_);
        return {leftFilter_, rightFilter_};
    }

    void reset() noexcept {
        history_.fill(0.0f);
        write_ = 0;
        leftFilter_ = 0.0f;
        rightFilter_ = 0.0f;
    }

private:
    std::array<float, 192> history_{};
    std::size_t write_ = 0;
    float leftFilter_ = 0.0f;
    float rightFilter_ = 0.0f;
};

}  // namespace girassol::audio
