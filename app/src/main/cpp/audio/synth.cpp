#include "synth.hpp"
#include <algorithm>
#include <array>
#include <cmath>

namespace girassol::audio {
namespace {
constexpr double tau=6.283185307179586;
constexpr double referenceFrequency=130.81;
float unit(float x) { return std::isfinite(x) ? std::clamp(x,0.0f,1.0f) : 0; }
void phase(double& p,double frequency,double dt) { p+=tau*frequency*dt; if (p>=tau) p-=tau; }
}
float Synth::random() noexcept {
    random_^=random_<<13; random_^=random_>>17; random_^=random_<<5;
    return static_cast<float>(random_&65535)/32767.5f-1;
}
bool Synth::render(float* out,std::int32_t frames,int sr,Parameters p) noexcept {
    if (!out || frames<0 || frames>1048576 || sr<8000 || sr>192000) return false;
    p.volume=unit(p.volume); p.left_steps=unit(p.left_steps); p.right_steps=unit(p.right_steps); p.mastery=unit(p.mastery); p.social=unit(p.social); p.weather=unit(p.weather); p.tension=unit(p.tension); p.milestone=unit(p.milestone); p.pitch=std::isfinite(p.pitch)?std::clamp(p.pitch,.5f,2.0f):1.0f; p.synchrony=std::isfinite(p.synchrony)?std::clamp(p.synchrony,0.0f,1.0f):0.0f;
    for(auto& ratio:p.ratios) ratio=std::isfinite(ratio)?std::clamp(ratio,.25f,4.0f):1.0f;
    if(p.milestone>last_milestone_) celebration_=1;
    last_milestone_=p.milestone;
    const double dt=1.0/sr;
    const float smooth=static_cast<float>(1-std::exp(-dt/.025));
    const float filter=static_cast<float>(1-std::exp(-tau*280*dt));
    for (std::int32_t i=0;i<frames;++i) {
        gain_+=(p.volume-gain_)*smooth; left_+=(p.left_steps-left_)*smooth;
        right_+=(p.right_steps-right_)*smooth; mastery_+=(p.mastery-mastery_)*smooth;
        social_+=(p.social-social_)*smooth;
        weather_+=(p.weather-weather_)*smooth; tension_+=(p.tension-tension_)*smooth;
        celebration_*=static_cast<float>(std::exp(-dt/2.5));
        timer_+=dt;
        const double interval=1.85-.45*mastery_;
        if (timer_>=interval) { timer_-=interval; note_index_=(note_index_+1)%p.ratios.size(); }
        constexpr std::array<float,8> collectiveRatios{1.0f,1.125f,1.25f,1.333333f,1.5f,1.666667f,1.875f,2.0f};
        const std::size_t scaleIndex=note_index_%p.ratios.size();
        const float activeRatio=p.ratios[scaleIndex]*(1.0f-p.synchrony)+collectiveRatios[scaleIndex]*p.synchrony;
        const double noteFrequency=referenceFrequency*static_cast<double>(activeRatio)*p.pitch;
        phase(hum_,60,dt); phase(note_,noteFrequency,dt); phase(sub_,noteFrequency*.5,dt);
        phase(step_,1.7,dt);
        noise_+=filter*(random()-noise_);
        const float air=noise_*(.010f+.016f*weather_);
        const float hum=static_cast<float>(std::sin(hum_)*.023+std::sin(2*hum_)*.012);
        const double attack=std::min(timer_/.025,1.0);
        const float music=static_cast<float>((std::sin(note_)+.2*mastery_*std::sin(2*note_))*std::exp(-timer_*2.4)*attack*.045+
                                             std::sin(sub_)*.012);
        // Filtered noise bursts evoke carpet steps. Activity comes from actual movement snapshots.
        const float step_env=static_cast<float>(std::pow(std::max(0.0,std::sin(step_)),18));
        const float steps=noise_*step_env*.18f;
        const float community=static_cast<float>(std::sin(note_*.5))*social_*.003f;
        // Trautonium-inspired mixture of independently phased subharmonic divisions.
        float undertone=0;
        for (std::size_t k=0;k<undertones_.size();++k) {
            phase(undertones_[k],noteFrequency/static_cast<double>(k+2),dt);
            // Finite Fourier series avoids the discontinuities of an unfiltered sawtooth.
            for (int partial=1;partial<=4;++partial)
                undertone+=static_cast<float>(std::sin(undertones_[k]*partial)/partial)*(.002f+.003f*tension_);
        }
        // Nonlinear bow friction drives a fractional-delay digital waveguide.
        const double delay=std::clamp(sr/(110.0+20*mastery_),2.0,2046.0);
        double read=static_cast<double>(write_)-delay;
        if(read<0) read+=string_.size();
        const auto index=static_cast<std::size_t>(read);
        const float fraction=static_cast<float>(read-static_cast<double>(index));
        const float wave=string_[index]*(1-fraction)+string_[(index+1)%string_.size()]*fraction;
        const float relative=.08f+.05f*social_+.03f*tension_-wave;
        const float friction=relative*std::exp(-20*std::abs(relative));
        bow_+=(wave-bow_)*.45f;
        string_[write_]=std::clamp(bow_*.995f+friction*.22f,-.3f,.3f);
        write_=(write_+1)%string_.size();
        const float milestone=static_cast<float>(std::sin(note_*2)+std::sin(sub_*3))*.015f*celebration_;
        const float common=hum+music+air+community+undertone+wave*.045f+milestone;
        out[i*2]=std::clamp((common+steps*left_)*gain_,-.35f,.35f);
        out[i*2+1]=std::clamp((common+steps*right_)*gain_,-.35f,.35f);
    }
    return true;
}
}
