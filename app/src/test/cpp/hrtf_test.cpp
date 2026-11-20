#include "audio/binaural_hrtf_spatializer.hpp"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <iostream>
#include <vector>

using girassol::audio::BinauralFrame;
using girassol::audio::BinauralHrtfSpatializer;

int main() {
    constexpr int sampleRate = 48000;
    constexpr double pi = 3.14159265358979323846;

    BinauralHrtfSpatializer rightSource;
    float firstNear = 0.0f;
    float firstFar = 0.0f;
    float farPeak = 0.0f;
    int farPeakIndex = -1;
    for (int sample = 0; sample < 160; ++sample) {
        const auto frame = rightSource.process(
            sample == 0 ? 1.0f : 0.0f,
            static_cast<float>(pi * 0.5), 0.0f, 0.0f, sampleRate
        );
        if (sample == 0) {
            firstNear = frame.right;
            firstFar = frame.left;
        }
        if (std::abs(frame.left) > farPeak) {
            farPeak = std::abs(frame.left);
            farPeakIndex = sample;
        }
    }
    assert(firstNear > firstFar);
    assert(farPeak > 0.05f);
    assert(farPeakIndex >= 25 && farPeakIndex <= 38);

    BinauralHrtfSpatializer leftSource;
    float mirroredNear = 0.0f;
    float mirroredFar = 0.0f;
    for (int sample = 0; sample < 160; ++sample) {
        const auto frame = leftSource.process(
            sample == 0 ? 1.0f : 0.0f,
            static_cast<float>(-pi * 0.5), 0.0f, 0.0f, sampleRate
        );
        if (sample == 0) {
            mirroredNear = frame.left;
            mirroredFar = frame.right;
        }
    }
    assert(std::abs(mirroredNear - firstNear) < 1e-6f);
    assert(std::abs(mirroredFar - firstFar) < 1e-6f);

    BinauralHrtfSpatializer openAir;
    BinauralHrtfSpatializer occluded;
    double openEnergy = 0.0;
    double occludedEnergy = 0.0;
    for (int sample = 0; sample < sampleRate; ++sample) {
        const float tone = std::sin(
            static_cast<float>(2.0 * pi * 7000.0 * sample / sampleRate)
        );
        const auto open = openAir.process(tone, 0.0f, 0.0f, 0.0f, sampleRate);
        const auto blocked = occluded.process(tone, 0.0f, 0.0f, 1.0f, sampleRate);
        if (sample > 1000) {
            openEnergy += static_cast<double>(open.left) * open.left;
            occludedEnergy += static_cast<double>(blocked.left) * blocked.left;
        }
    }
    assert(occludedEnergy < openEnergy * 0.15);

    BinauralHrtfSpatializer nearSource;
    BinauralHrtfSpatializer distantSource;
    const auto nearFrame = nearSource.process(1.0f, 0.0f, 0.0f, 0.0f, sampleRate);
    const auto distantFrame = distantSource.process(1.0f, 0.0f, 4.0f, 0.0f, sampleRate);
    assert(distantFrame.left < nearFrame.left);

    const auto invalid = distantSource.process(1.0f, 0.0f, 1.0f, 0.0f, 7000);
    assert(invalid.left == 0.0f && invalid.right == 0.0f);

    std::cout << "Woodworth binaural ITD/ILD, distance and occlusion tests passed\n";
}
