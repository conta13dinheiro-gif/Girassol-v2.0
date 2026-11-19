#pragma once
#include <cstdint>
#include <array>

namespace girassol::audio {
struct Parameters {
    float volume=0, left_steps=0, right_steps=0, mastery=0, social=0, weather=0, tension=0, milestone=0, pitch=1;
    std::array<float,8> ratios{1.0f,1.067f,1.125f,1.2f,1.333f,1.5f,1.6f,1.8f};
};
// Owned solely by the audio callback. No allocations, locks, logging or Android calls in render.
class Synth {
public:
    explicit Synth(std::uint32_t seed=1) : random_(seed ? seed : 1) {}
    bool render(float* stereo, std::int32_t frames, int sample_rate, Parameters params) noexcept;
private:
    std::uint32_t random_;
    double hum_=0, note_=0, sub_=0, timer_=0, step_=0;
    std::uint32_t note_index_=0;
    float gain_=0, left_=0, right_=0, mastery_=0, social_=0, noise_=0;
    std::array<double,3> undertones_{};
    std::array<float,2048> string_{};
    std::uint32_t write_=0;
    float weather_=0, tension_=0, bow_=0, celebration_=0, last_milestone_=0;
    float random() noexcept;
};
}
