#pragma once
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <span>

namespace girassol::forge {
/** Fixed 8x8 LTC readout, rank four. No heap allocation, including wire decode/backward.
 * Layout A[rank,input], B[output,rank]. Optimizer never modifies caller-owned W0.
 */
struct RealTimeLoraAdapter {
    static constexpr std::size_t parameters=64, bytes=2592;
    static constexpr std::uint64_t max_steps=1'000'000'000'000ULL;
    static constexpr double scaling=4, lr_a=.001, lr_b=.004, decay=.01;
    std::uint64_t step=0, consolidations=0;
    std::array<double,parameters> weights{},m{},v{},fisher{},anchor{};
    using Input=std::array<double,8>;
    using Base=std::array<double,64>;
    void initialize(std::uint64_t seed) noexcept {
        *this={};
        // SplitMix64 uniform variance 1/input. Fully specified across standard libraries.
        for(std::size_t i=0;i<32;++i) {
            seed+=0x9e3779b97f4a7c15ULL;auto z=seed;
            z=(z^(z>>30))*0xbf58476d1ce4e5b9ULL;z=(z^(z>>27))*0x94d049bb133111ebULL;z^=z>>31;
            weights[i]=(static_cast<double>(z>>11)*0x1.0p-53*2-1)*std::sqrt(3.0/8);
        }
        anchor=weights;
    }
    bool valid() const noexcept {
        if(step>max_steps || consolidations!=step/32) return false;
        for(std::size_t i=0;i<parameters;++i) {
            if(!std::isfinite(weights[i]) || std::abs(weights[i])>8 || !std::isfinite(anchor[i]) || std::abs(anchor[i])>8 ||
               !std::isfinite(m[i]) || std::abs(m[i])>1e12 || !std::isfinite(v[i]) || v[i]<0 || v[i]>1e24 ||
               !std::isfinite(fisher[i]) || fisher[i]<0 || fisher[i]>1e24) return false;
            if(step==0 && (m[i]!=0 || v[i]!=0 || fisher[i]!=0 || weights[i]!=anchor[i] || (i>=32 && weights[i]!=0))) return false;
        }
        return true;
    }
    static bool inputs(const Base& base,const Input& x) noexcept {
        for(double a:base) if(!std::isfinite(a) || std::abs(a)>8) return false;
        for(double a:x) if(!std::isfinite(a) || std::abs(a)>1.00001) return false;
        return true;
    }
    Input forward(const Base& base,const Input& x) const noexcept {
        std::array<double,4> h{};Input y{};
        for(int r=0;r<4;++r) for(int k=0;k<8;++k) h[r]+=weights[r*8+k]*x[k];
        for(int d=0;d<8;++d) {
            for(int k=0;k<8;++k) y[d]+=base[d*8+k]*x[k];
            for(int r=0;r<4;++r) y[d]+=scaling*weights[32+d*4+r]*h[r];
        }
        return y;
    }
    // Data gradients are computed together using the PRE-update B. Fisher excludes EWC.
    std::array<double,64> gradients(const Base& base,const Input& x,const Input& target,double& loss) const noexcept {
        auto y=forward(base,x);std::array<double,4> h{},dh{};std::array<double,64> g{};loss=0;
        for(int r=0;r<4;++r) for(int k=0;k<8;++k) h[r]+=weights[r*8+k]*x[k];
        for(int d=0;d<8;++d) {
            const double diff=y[d]-target[d],dy=diff/4;loss+=diff*diff/8;
            for(int r=0;r<4;++r) { g[32+d*4+r]=scaling*dy*h[r];dh[r]+=scaling*weights[32+d*4+r]*dy; }
        }
        for(int r=0;r<4;++r) for(int k=0;k<8;++k) g[r*8+k]=dh[r]*x[k];
        return g;
    }
    bool train(const Base& base,const Input& x,const Input& target,double& loss,double ewc_lambda=15) noexcept {
        if(!valid() || !inputs(base,x) || step==max_steps || !std::isfinite(ewc_lambda) || ewc_lambda<0 || ewc_lambda>100) return false;
        for(double a:target) if(!std::isfinite(a) || std::abs(a)>10000) return false;
        const auto g=gradients(base,x,target,loss);auto next=*this;++next.step;
        const double c1=1-std::pow(.9,static_cast<double>(next.step)),c2=1-std::pow(.999,static_cast<double>(next.step));
        for(std::size_t i=0;i<parameters;++i) {
            const double reg=g[i]+ewc_lambda*fisher[i]*(weights[i]-anchor[i]),lr=i<32?lr_a:lr_b;
            next.m[i]=.9*m[i]+.1*reg;next.v[i]=.999*v[i]+.001*reg*reg;
            next.fisher[i]=.95*fisher[i]+.05*g[i]*g[i];
            next.weights[i]=weights[i]*(1-lr*decay)-lr*(next.m[i]/c1)/(std::sqrt(next.v[i]/c2)+1e-8);
        }
        if(next.step%32==0) { next.anchor=next.weights;++next.consolidations; }
        if(!next.valid() || !std::isfinite(loss)) return false;
        *this=next;return true;
    }
    bool save(std::span<std::uint8_t> out) const noexcept {
        if(out.size()!=bytes || !valid()) return false;
        auto put=[&](std::size_t offset,std::uint64_t value,int size) { for(int j=0;j<size;++j) out[offset+j]=static_cast<std::uint8_t>(value>>(8*j)); };
        put(0,0x3141524c,4);put(4,1,2);put(6,8,2);put(8,8,2);put(10,4,2);put(12,0,4);put(16,step,8);put(24,consolidations,8);
        std::size_t pos=32;for(const auto* array:{&weights,&m,&v,&fisher,&anchor}) for(double value:*array) { put(pos,std::bit_cast<std::uint64_t>(value),8);pos+=8; }
        return true;
    }
    bool load(std::span<const std::uint8_t> in) noexcept {
        if(in.size()!=bytes) return false;
        auto get=[&](std::size_t offset,int size) { std::uint64_t value=0;for(int j=0;j<size;++j) value|=static_cast<std::uint64_t>(in[offset+j])<<(8*j);return value; };
        if(get(0,4)!=0x3141524c || get(4,2)!=1 || get(6,2)!=8 || get(8,2)!=8 || get(10,2)!=4 || get(12,4)!=0) return false;
        RealTimeLoraAdapter next{};next.step=get(16,8);next.consolidations=get(24,8);std::size_t pos=32;
        for(auto* array:{&next.weights,&next.m,&next.v,&next.fisher,&next.anchor}) for(double& value:*array) { value=std::bit_cast<double>(get(pos,8));pos+=8; }
        if(!next.valid()) return false;
        *this=next;return true;
    }
};
static_assert(sizeof(RealTimeLoraAdapter)<=2592);
}
