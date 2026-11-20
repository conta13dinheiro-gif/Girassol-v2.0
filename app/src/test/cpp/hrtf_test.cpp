#include "audio/binaural_hrtf_spatializer.hpp"
#include "audio/synth.hpp"
#include <cassert>
#include <cstring>
#include <iostream>
#include <limits>

int main() {
    using namespace girassol::audio;
    std::array<float,2048> impulse{};impulse[0]=1;
    std::array<float,4096> front{},right{},left{},blocked{},chunked{};
    HrtfState a{},b{},c{},d{},e{};a.rate=b.rate=c.rate=d.rate=e.rate=48000;
    assert(spatialize(a,impulse.data(),front.data(),2048,48000,0,0,0));
    assert(spatialize(b,impulse.data(),right.data(),2048,48000,pi/2,0,0));
    assert(spatialize(c,impulse.data(),left.data(),2048,48000,-pi/2,0,0));
    assert(spatialize(d,impulse.data(),blocked.data(),2048,48000,pi/2,0,1));
    assert(std::abs(woodworth(pi/2,48000)-.0875/343*(pi/2+1)*48000)<1e-12);
    int firstL=-1,firstR=-1;double energy=0,attenuated=0;
    for(int i=0;i<2048;++i) {
        assert(front[i*2]==front[i*2+1]);assert(right[i*2]==left[i*2+1] && right[i*2+1]==left[i*2]);
        if(firstL<0 && std::abs(right[i*2])>1e-9) firstL=i;
        if(firstR<0 && std::abs(right[i*2+1])>1e-9) firstR=i;
        energy+=right[i*2]*right[i*2]+right[i*2+1]*right[i*2+1];attenuated+=blocked[i*2]*blocked[i*2]+blocked[i*2+1]*blocked[i*2+1];
    }
    assert(firstR==0 && firstL==31 && attenuated<energy*.05);
    assert(spatialize(e,impulse.data(),chunked.data(),257,48000,pi/2,0,0));
    assert(spatialize(e,impulse.data()+257,chunked.data()+514,1791,48000,pi/2,0,0));
    assert(chunked==right && std::memcmp(&b,&e,sizeof(b))==0);
    const auto saved=b;const auto original=right;impulse[100]=std::numeric_limits<float>::quiet_NaN();
    assert(!spatialize(b,impulse.data(),right.data(),2048,48000,0,0,0));assert(std::memcmp(&saved,&b,sizeof(b))==0 && right==original);
    girassol::Mesh wall;wall.vertices={{0,0,-1,0,0,0,1,0},{0,2,-1,0,0,0,1,0},{0,2,1,0,0,0,1,0},{0,0,1,0,0,0,1,0}};wall.indices={0,1,2,0,2,3};
    assert(acoustic_occlusion(wall,{-1,1,0},{1,1,0})>.29);assert(acoustic_occlusion(wall,{-1,1,2},{1,1,2})==0);
    Synth dry(42),duck(42);Parameters p;p.volume=1;p.speech=false;Parameters q=p;q.speech=true;
    std::array<float,96000> x{},y{};
    assert(dry.render(x.data(),48000,48000,p));assert(duck.render(y.data(),48000,48000,q));
    double base=0,quiet=0;for(std::size_t i=48000;i<x.size();++i) { base+=x[i]*x[i];quiet+=y[i]*y[i]; }
    assert(quiet<base*.08 && quiet>base*.04);
    std::cout<<"HRTF: independent Woodworth delay31, mirror/ILD/filter, chunk invariance, invalid atomicity, real triangle occlusion and Trautonium ducking passed\n";
}
