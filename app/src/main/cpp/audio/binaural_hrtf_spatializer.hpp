#pragma once
#include "../city_mesh.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

namespace girassol::audio {
inline constexpr double pi=3.14159265358979323846;
struct HrtfState {
    std::uint32_t magic=0x31465248,rate=0,cursor=0,reserved=0;
    float left=0,right=0;
    std::array<float,128> delay{};
};
static_assert(sizeof(HrtfState)==536);
inline bool valid(const HrtfState& s,int rate) {
    return s.magic==0x31465248 && s.rate==static_cast<std::uint32_t>(rate) && s.cursor<128 && s.reserved==0 &&
        std::isfinite(s.left) && std::isfinite(s.right) && std::abs(s.left)<=1.001f && std::abs(s.right)<=1.001f &&
        std::all_of(s.delay.begin(),s.delay.end(),[](float v){return std::isfinite(v) && std::abs(v)<=1;});
}
inline double woodworth(double azimuth,int rate) {
    const double absolute=std::abs(azimuth),theta=std::min(absolute,pi-absolute);
    return .0875/343.0*(theta+std::sin(theta))*rate;
}
/** Analytic spherical-head HRTF: fractional Woodworth ITD, ILD, contralateral and occlusion filters. */
inline bool spatialize(HrtfState& s,const float* mono,float* stereo,int frames,int rate,double azimuth,double distance,double occlusion) noexcept {
    if(!mono || !stereo || frames<1 || frames>2048 || rate<8000 || rate>96000 || !valid(s,rate) ||
       !std::isfinite(azimuth) || std::abs(azimuth)>pi || !std::isfinite(distance) || distance<0 || distance>2000 ||
       !std::isfinite(occlusion) || occlusion<0 || occlusion>1) return false;
    for(int i=0;i<frames;++i) if(!std::isfinite(mono[i]) || std::abs(mono[i])>1) return false;
    const double lateral=std::sin(azimuth),delay=woodworth(azimuth,rate);
    const double far=std::pow(10.0,-9.0*std::abs(lateral)/20),norm=1/std::sqrt(1+far*far);
    const double leftGain=(lateral>0?far:1)*norm,rightGain=(lateral<0?far:1)*norm;
    const double gain=(1-.85*occlusion)/(1+.025*distance);
    auto cutoff=[&](bool farEar) { const double frequency=(farEar?1500+4500*(1-std::abs(lateral)):14000)*(1-occlusion)+900*occlusion;return 1-std::exp(-2*pi*std::min(frequency,rate*.45)/rate); };
    const double alphaL=cutoff(lateral>0),alphaR=cutoff(lateral<0);
    for(int i=0;i<frames;++i) {
        s.delay[s.cursor]=mono[i];const int whole=static_cast<int>(std::floor(delay));const double part=delay-whole;
        const double delayed=(1-part)*s.delay[(s.cursor+128-static_cast<std::uint32_t>(whole))%128]+part*s.delay[(s.cursor+127-static_cast<std::uint32_t>(whole))%128];
        const double l=lateral>0?delayed:mono[i],r=lateral<0?delayed:mono[i];
        s.left=static_cast<float>(s.left+alphaL*(l-s.left));s.right=static_cast<float>(s.right+alphaR*(r-s.right));
        stereo[i*2]=static_cast<float>(s.left*leftGain*gain);stereo[i*2+1]=static_cast<float>(s.right*rightGain*gain);
        s.cursor=(s.cursor+1)%128;
    }
    return true;
}
/** Segment/triangle propagation through the same city mesh used by Filament and navigation. */
inline double acoustic_occlusion(const Mesh& mesh,const std::array<double,3>& from,const std::array<double,3>& to) {
    using P=std::array<double,3>;
    auto sub=[](P a,P b)->P{return {a[0]-b[0],a[1]-b[1],a[2]-b[2]};};
    auto cross=[](P a,P b)->P{return {a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};};
    auto dot=[](P a,P b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];};
    const P direction=sub(to,from);const double length=std::sqrt(dot(direction,direction));if(length<.04) return 0;
    std::array<double,256> hits{};std::size_t count=0;
    for(std::size_t i=0;i+2<mesh.indices.size();i+=3) {
        auto point=[&](std::size_t j)->P{const auto& v=mesh.vertices[mesh.indices[j]];return {v.x,v.y,v.z};};
        const P a=point(i),e1=sub(point(i+1),a),e2=sub(point(i+2),a),h=cross(direction,e2);const double determinant=dot(e1,h);
        if(std::abs(determinant)<1e-10) continue;
        const double inv=1/determinant;const P offset=sub(from,a);const double u=inv*dot(offset,h);if(u< -1e-9 || u>1+1e-9) continue;
        const P q=cross(offset,e1);const double v=inv*dot(direction,q);if(v< -1e-9 || u+v>1+1e-9) continue;
        const double t=inv*dot(e2,q);if(t*length<=.02 || (1-t)*length<=.02) continue;
        if(count==hits.size()) return 1;
        hits[count++]=t*length;
    }
    std::sort(hits.begin(),hits.begin()+static_cast<std::ptrdiff_t>(count));int surfaces=0;double last=-1;
    for(std::size_t i=0;i<count;++i) if(hits[i]-last>.04) { ++surfaces;last=hits[i]; }
    return 1-std::pow(.5,surfaces/2.0);
}
}
