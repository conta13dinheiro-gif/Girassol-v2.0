#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace girassol::qd {
inline constexpr std::size_t dimension=16,bins=8,capacity=bins*bins*bins,policy_size=8;
using Trajectory=std::array<double,dimension>;
using Policy=std::array<double,policy_size>;
using Descriptor=std::array<double,3>;
inline double dot(const Trajectory& a,const Trajectory& b) noexcept {
    double result=0;for(std::size_t i=0;i<dimension;++i) result+=a[i]*b[i];return result;
}
inline bool normalize(Trajectory& values) noexcept {
    const double norm=std::sqrt(dot(values,values));
    if(!std::isfinite(norm) || norm<1e-12) return false;
    for(auto& value:values) value/=norm;
    return true;
}
struct Candidate {
    std::uint64_t owner=0;
    std::uint32_t generation=0;
    double fitness=0,explicit_axis=0;
    Trajectory trajectory{};
    Policy policy{};
    bool valid() const noexcept {
        if(generation==0 || generation>2147483647 || !std::isfinite(fitness) || fitness < -1 || fitness>1 ||
           !std::isfinite(explicit_axis) || explicit_axis<0 || explicit_axis>1) return false;
        for(double x:trajectory) if(!std::isfinite(x) || x<0 || x>1) return false;
        for(double x:policy) if(!std::isfinite(x) || x < -8 || x>8) return false;
        return true;
    }
};
struct Encoder {
    Trajectory mean{},first{},second{};
    Encoder() noexcept { first[0]=1;second[1]=1; }
    bool valid() const noexcept {
        for(std::size_t i=0;i<dimension;++i) if(!std::isfinite(mean[i]) || mean[i]<0 || mean[i]>1 ||
            !std::isfinite(first[i]) || !std::isfinite(second[i])) return false;
        return std::abs(dot(first,first)-1)<1e-8 && std::abs(dot(second,second)-1)<1e-8 && std::abs(dot(first,second))<1e-8;
    }
    void adapt(const Trajectory& values) noexcept {
        Trajectory x{};
        for(std::size_t i=0;i<dimension;++i) { mean[i]=.99*mean[i]+.01*values[i];x[i]=values[i]-mean[i]; }
        const double y=dot(first,x);
        for(std::size_t i=0;i<dimension;++i) first[i]+=.015*y*(x[i]-y*first[i]);
        if(!normalize(first)) { first.fill(0);first[0]=1; }
        const double projection=dot(first,x);
        Trajectory residual{};
        for(std::size_t i=0;i<dimension;++i) residual[i]=x[i]-projection*first[i];
        const double z=dot(second,residual);
        for(std::size_t i=0;i<dimension;++i) second[i]+=.015*z*(residual[i]-z*second[i]);
        // Re-orthogonalize twice to retain orthogonality under long streams and collinearity.
        for(int pass=0;pass<2;++pass) {
            const double overlap=dot(first,second);
            for(std::size_t i=0;i<dimension;++i) second[i]-=overlap*first[i];
        }
        if(!normalize(second)) {
            const auto axis=static_cast<std::size_t>(std::min_element(first.begin(),first.end(),[](double a,double b) { return std::abs(a)<std::abs(b); })-first.begin());
            second.fill(0);second[axis]=1;
            const double overlap=dot(first,second);
            for(std::size_t i=0;i<dimension;++i) second[i]-=overlap*first[i];
            normalize(second);
        }
    }
    Descriptor project(const Candidate& candidate) const noexcept {
        Trajectory centered{};
        for(std::size_t i=0;i<dimension;++i) centered[i]=candidate.trajectory[i]-mean[i];
        return {candidate.explicit_axis,1/(1+std::exp(-dot(first,centered))),1/(1+std::exp(-dot(second,centered)))};
    }
};
inline std::size_t key(const Descriptor& descriptor) noexcept {
    std::size_t result=0;
    for(double value:descriptor) {
        if(!std::isfinite(value)) return capacity;
        result=result*bins+std::min(bins-1,static_cast<std::size_t>(std::clamp(value,0.0,1.0)*bins));
    }
    return result;
}
struct Elite { bool occupied=false;Candidate candidate{};Descriptor descriptor{}; };
struct Archive {
    std::array<Elite,capacity> cells{};
    std::size_t occupied=0;
    bool submit(const Candidate& candidate,const Descriptor& descriptor) noexcept {
        if(!candidate.valid()) return false;
        for(double d:descriptor) if(!std::isfinite(d) || d<0 || d>1) return false;
        auto& cell=cells[key(descriptor)];
        if(cell.occupied && (candidate.fitness<cell.candidate.fitness ||
            (candidate.fitness==cell.candidate.fitness && candidate.owner>=cell.candidate.owner))) return false;
        if(!cell.occupied) ++occupied;
        cell={true,candidate,descriptor};return true;
    }
    bool valid() const noexcept {
        std::size_t count=0;
        for(std::size_t i=0;i<capacity;++i) if(cells[i].occupied) {
            const auto& cell=cells[i];if(!cell.candidate.valid()) return false;
            for(double d:cell.descriptor) if(!std::isfinite(d) || d<0 || d>1) return false;
            if(key(cell.descriptor)!=i) return false;
            ++count;
        }
        return count==occupied;
    }
    const Elite* donor(std::uint64_t seed) const noexcept {
        if(occupied==0) return nullptr;
        auto selection=static_cast<std::size_t>(seed%occupied);
        for(const auto& cell:cells) if(cell.occupied) { if(selection==0) return &cell;--selection; }
        return nullptr;
    }
    double coverage() const noexcept { return static_cast<double>(occupied)/capacity; }
};
struct State {
    Encoder encoder{};Archive archive{};
    std::uint64_t samples=0,tick=0;
    bool valid() const noexcept {
        if(!encoder.valid() || !archive.valid() || samples<archive.occupied ||
           samples>static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) ||
           tick>static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) ||
           ((samples==0)!=(tick==0)) || ((samples==0)!=(archive.occupied==0))) return false;
        for(const auto& cell:archive.cells) if(cell.occupied) {
            const auto expected=encoder.project(cell.candidate);
            for(std::size_t i=0;i<3;++i) if(std::abs(expected[i]-cell.descriptor[i])>1e-10) return false;
        }
        return true;
    }
    // All validation precedes mutation. Rebin every retained raw trajectory in the updated PCA frame.
    bool update(const Candidate* candidates,std::size_t count,std::uint64_t source_tick) noexcept {
        if(!valid() || !candidates || count==0 || count>150 || source_tick<=tick || source_tick>static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) ||
            samples>static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())-count) return false;
        for(std::size_t i=0;i<count;++i) if(!candidates[i].valid()) return false;
        for(std::size_t i=0;i<count;++i) encoder.adapt(candidates[i].trajectory);
        Archive rebuilt{};
        for(const auto& cell:archive.cells) if(cell.occupied) rebuilt.submit(cell.candidate,encoder.project(cell.candidate));
        for(std::size_t i=0;i<count;++i) rebuilt.submit(candidates[i],encoder.project(candidates[i]));
        archive=rebuilt;samples+=count;tick=source_tick;return valid();
    }
};
static_assert(sizeof(State)+sizeof(Archive)+150*sizeof(Candidate)<300000,"Fixed QD stack budget");
} // namespace girassol::qd
