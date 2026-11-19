#include "cma_es.hpp"
#include <algorithm>
#include <cmath>
#include <numeric>
// Positive-weight CMA-ES, Hansen's tutorial https://arxiv.org/abs/1604.00772.
// Original fixed-storage implementation; no external optimizer or synthetic world model.
namespace girassol::evolution {
namespace {
using Matrix=std::array<double,64>;
constexpr int n=dimensions;
struct Rng {
    std::uint64_t state;
    double unit() {
        auto z=(state+=0x9e3779b97f4a7c15ULL);
        z=(z^(z>>30))*0xbf58476d1ce4e5b9ULL; z=(z^(z>>27))*0x94d049bb133111ebULL;
        return (((z^(z>>31))>>11)+.5)*0x1p-53;
    }
    double normal() { return std::sqrt(-2*std::log(unit()))*std::cos(6.283185307179586*unit()); }
};
bool eigen(const Matrix& input,Matrix& basis,Vector& values) {
    Matrix a=input; basis={}; for(int i=0;i<n;++i) basis[i*n+i]=1;
    for(int sweep=0;sweep<256;++sweep) {
        int p=0,q=1; double peak=0;
        for(int i=0;i<n;++i) for(int j=i+1;j<n;++j) if(std::abs(a[i*n+j])>peak) { p=i;q=j;peak=std::abs(a[i*n+j]); }
        if(peak<1e-13) break;
        const auto angle=.5*std::atan2(2*a[p*n+q],a[q*n+q]-a[p*n+p]);
        const auto c=std::cos(angle),s=std::sin(angle);
        const auto pp=a[p*n+p],qq=a[q*n+q],pq=a[p*n+q];
        for(int k=0;k<n;++k) if(k!=p && k!=q) {
            const auto kp=a[k*n+p],kq=a[k*n+q];
            a[k*n+p]=a[p*n+k]=c*kp-s*kq; a[k*n+q]=a[q*n+k]=s*kp+c*kq;
        }
        a[p*n+p]=c*c*pp-2*s*c*pq+s*s*qq; a[q*n+q]=s*s*pp+2*s*c*pq+c*c*qq; a[p*n+q]=a[q*n+p]=0;
        for(int k=0;k<n;++k) { const auto bp=basis[k*n+p],bq=basis[k*n+q]; basis[k*n+p]=c*bp-s*bq; basis[k*n+q]=s*bp+c*bq; }
    }
    for(int i=0;i<n;++i) { values[i]=a[i*n+i]; if(!std::isfinite(values[i]) || values[i]<=1e-14) return false; }
    return true;
}
bool sample(CmaState& state,std::uint64_t seed) {
    Matrix basis; Vector values; if(!eigen(state.covariance,basis,values)) return false;
    Rng rng{seed^static_cast<std::uint64_t>(state.generation)*0xd1b54a32d192ed03ULL};
    for(auto& candidate:state.candidates) {
        Vector z; for(int i=0;i<n;++i) z[i]=std::sqrt(values[i])*rng.normal();
        for(int i=0;i<n;++i) {
            double y=0; for(int j=0;j<n;++j) y+=basis[i*n+j]*z[j];
            const double proposed=state.mean[i]+state.sigma*y;
            // Reflect into the admissible policy box, rather than discard covariance information.
            double folded=std::fmod(proposed+2,8); if(folded<0) folded+=8;
            candidate[i]=folded<=4 ? folded-2 : 6-folded;
        }
    }
    return true;
}
}
bool valid(const CmaState& s) {
    if(!std::isfinite(s.sigma) || s.sigma<1e-8 || s.sigma>2 || s.generation<0 || s.generation>1000000) return false;
    for(double x:s.mean) if(!std::isfinite(x) || std::abs(x)>2) return false;
    for(double x:s.covariance) if(!std::isfinite(x) || std::abs(x)>1e8) return false;
    for(int i=0;i<n;++i) {
        if(!std::isfinite(s.sigma_path[i]) || !std::isfinite(s.covariance_path[i])) return false;
        for(int j=0;j<n;++j) if(std::abs(s.covariance[i*n+j]-s.covariance[j*n+i])>1e-9) return false;
    }
    return true;
}
bool initialize(CmaState& state,const Vector& mean,std::uint64_t seed) {
    CmaState next; next.mean=mean;
    for(int i=0;i<n;++i) next.covariance[i*n+i]=1;
    if(!valid(next) || !sample(next,seed)) return false;
    state=next; return true;
}
bool advance(CmaState& state,const std::array<double,population>& scores,std::uint64_t seed) {
    if(!valid(state)) return false;
    for(double x:scores) if(!std::isfinite(x)) return false;
    for(const auto& candidate:state.candidates) for(double x:candidate) if(!std::isfinite(x) || std::abs(x)>2) return false;
    constexpr int mu=population/2; std::array<double,mu> weights{};
    double sum=0,ss=0; for(int i=0;i<mu;++i) sum+=(weights[i]=std::log(mu+.5)-std::log(i+1.));
    for(auto& w:weights) { w/=sum;ss+=w*w; }
    const auto effective=1/ss,cc=(4+effective/n)/(n+4+2*effective/n),cs=(effective+2)/(n+effective+5);
    const auto c1=2/(std::pow(n+1.3,2)+effective),cmu=std::min(1-c1,2*(effective-2+1/effective)/(std::pow(n+2,2)+effective));
    const auto damping=1+2*std::max(0.,std::sqrt((effective-1)/(n+1))-1)+cs;
    const auto chi=std::sqrt(n)*(1-1./(4*n)+1./(21*n*n));
    std::array<int,population> order{}; std::iota(order.begin(),order.end(),0);
    std::sort(order.begin(),order.end(),[&](int a,int b) { return scores[a]!=scores[b] ? scores[a]>scores[b] : a<b; });
    CmaState next=state; next.mean={};
    for(int k=0;k<mu;++k) for(int i=0;i<n;++i) next.mean[i]+=weights[k]*state.candidates[order[k]][i];
    Vector direction{},whitened{},diagonal{}; Matrix basis{};
    if(!eigen(state.covariance,basis,diagonal)) return false;
    for(int i=0;i<n;++i) direction[i]=(next.mean[i]-state.mean[i])/state.sigma;
    for(int k=0;k<n;++k) {
        double projected=0; for(int j=0;j<n;++j) projected+=basis[j*n+k]*direction[j];
        for(int i=0;i<n;++i) whitened[i]+=basis[i*n+k]*projected/std::sqrt(diagonal[k]);
    }
    double norm=0;
    for(int i=0;i<n;++i) { next.sigma_path[i]=(1-cs)*state.sigma_path[i]+std::sqrt(cs*(2-cs)*effective)*whitened[i]; norm+=next.sigma_path[i]*next.sigma_path[i]; }
    norm=std::sqrt(norm);
    const bool h=norm/std::sqrt(1-std::pow(1-cs,2*(state.generation+1)))/chi < 1.4+2./(n+1);
    for(int i=0;i<n;++i) next.covariance_path[i]=(1-cc)*state.covariance_path[i]+(h ? std::sqrt(cc*(2-cc)*effective)*direction[i] : 0);
    for(int i=0;i<n;++i) for(int j=0;j<n;++j) {
        double rank_mu=0;
        for(int k=0;k<mu;++k) rank_mu+=weights[k]*(state.candidates[order[k]][i]-state.mean[i])*(state.candidates[order[k]][j]-state.mean[j])/(state.sigma*state.sigma);
        next.covariance[i*n+j]=(1-c1-cmu)*state.covariance[i*n+j]+c1*(next.covariance_path[i]*next.covariance_path[j]+(!h ? cc*(2-cc)*state.covariance[i*n+j] : 0))+cmu*rank_mu;
    }
    next.sigma=std::clamp(state.sigma*std::exp(cs/damping*(norm/chi-1)),1e-8,2.);
    ++next.generation;
    if(!valid(next) || !sample(next,seed)) return false;
    state=next; return true;
}
}
