#pragma once
#include "map_elites_aurora.hpp"
#include <bit>
#include <span>
namespace girassol::qd {
inline constexpr std::size_t candidate_bytes=224,cell_bytes=245,wire_bytes=412+capacity*cell_bytes;
struct Reader {
    std::span<const std::uint8_t> data;std::size_t pos=0;
    std::uint64_t integer(std::size_t n) noexcept { std::uint64_t v=0;for(std::size_t i=0;i<n;++i) v|=std::uint64_t(data[pos++])<<(8*i);return v; }
    double real() noexcept { return std::bit_cast<double>(integer(8)); }
    Candidate candidate(bool padded=false) noexcept {
        Candidate c;c.owner=integer(8);c.generation=static_cast<std::uint32_t>(integer(4));
        if(padded) integer(4);
        c.fitness=real();c.explicit_axis=real();for(auto& v:c.trajectory) v=real();for(auto& v:c.policy) v=real();return c;
    }
};
struct Writer {
    std::span<std::uint8_t> data;std::size_t pos=0;
    void integer(std::uint64_t v,std::size_t n) noexcept { for(std::size_t i=0;i<n;++i) data[pos++]=static_cast<std::uint8_t>(v>>(8*i)); }
    void real(double value) noexcept { integer(std::bit_cast<std::uint64_t>(value),8); }
    void candidate(const Candidate& c) noexcept { integer(c.owner,8);integer(c.generation,4);real(c.fitness);real(c.explicit_axis);for(double v:c.trajectory) real(v);for(double v:c.policy) real(v); }
};
inline bool load(std::span<const std::uint8_t> bytes,State& state) noexcept {
    if(bytes.size()!=wire_bytes) return false;
    Reader r{bytes};if(r.integer(4)!=0x31445147 || r.integer(4)!=1) return false;
    state.samples=r.integer(8);state.tick=r.integer(8);state.archive.occupied=r.integer(4);
    for(auto& v:state.encoder.mean) v=r.real();
    for(auto& v:state.encoder.first) v=r.real();
    for(auto& v:state.encoder.second) v=r.real();
    for(auto& cell:state.archive.cells) {
        const auto start=r.pos;const auto flag=r.integer(1);
        if(flag>1) return false;
        cell={};cell.occupied=flag==1;
        if(!cell.occupied) { for(std::size_t i=0;i<cell_bytes;++i) if(bytes[start+i]!=0) return false;r.pos=start+cell_bytes;continue; }
        cell.candidate=r.candidate();for(auto& v:cell.descriptor) v=r.real();
        const auto expected=state.encoder.project(cell.candidate);
        for(std::size_t i=0;i<3;++i) if(!std::isfinite(expected[i]) || std::abs(cell.descriptor[i]-expected[i])>1e-10) return false;
    }
    return state.valid();
}
inline bool save(const State& state,std::span<std::uint8_t> bytes) noexcept {
    if(bytes.size()!=wire_bytes || !state.valid()) return false;
    Writer w{bytes};w.integer(0x31445147,4);w.integer(1,4);w.integer(state.samples,8);w.integer(state.tick,8);w.integer(state.archive.occupied,4);
    for(double v:state.encoder.mean) w.real(v);
    for(double v:state.encoder.first) w.real(v);
    for(double v:state.encoder.second) w.real(v);
    for(const auto& cell:state.archive.cells) {
        if(!cell.occupied) { for(std::size_t i=0;i<cell_bytes;++i) w.integer(0,1);continue; }
        w.integer(1,1);w.candidate(cell.candidate);for(double v:cell.descriptor) w.real(v);
    }
    return w.pos==wire_bytes;
}
}
