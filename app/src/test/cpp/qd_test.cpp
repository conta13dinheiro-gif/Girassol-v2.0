#include "evolution/qd_wire.hpp"
#include <cassert>
#include <cstdlib>
#include <cstdio>
#include <new>
namespace { bool forbid=false;std::size_t allocations=0; }
void* operator new(std::size_t n) { if(forbid) ++allocations;if(auto* p=std::malloc(n)) return p;throw std::bad_alloc(); }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete(void* p,std::size_t) noexcept { std::free(p); }
using namespace girassol::qd;
int main() {
 forbid=true;
 Encoder e;
 for(int i=0;i<100000;++i) {
  Trajectory x{};x.fill(.5);
  const double y=.3*std::sin(i*.73),z=.1*std::sin(i*.17);
  x[0]+=y/std::sqrt(2.0);x[1]+=y/std::sqrt(2.0);x[2]+=z/std::sqrt(2.0);x[3]+=z/std::sqrt(2.0);
  e.adapt(x);assert(e.valid());
 }
 Trajectory a{};a[0]=a[1]=1/std::sqrt(2.0);
 Trajectory b{};b[2]=b[3]=1/std::sqrt(2.0);
 assert(std::abs(dot(e.first,a))>.99 && std::abs(dot(e.second,b))>.99);
 for(int i=0;i<1000;++i) { e.adapt(Trajectory{});assert(e.valid()); }
 assert(key({0,0,0})==0 && key({1,1,1})==511);
 assert(key({NAN,0,0})==512);
 Candidate c;c.owner=5;c.generation=1;c.fitness=.2;c.explicit_axis=.5;c.trajectory.fill(.5);c.policy.fill(.2);
 Archive archive;assert(archive.submit(c,{0,0,0}));assert(archive.occupied==1);
 c.fitness=.1;assert(!archive.submit(c,{0,0,0}));c.fitness=.2;c.owner=6;assert(!archive.submit(c,{0,0,0}));
 c.owner=4;assert(archive.submit(c,{0,0,0}));assert(archive.donor(0)->candidate.owner==4);
 c.owner=8;c.fitness=.9;assert(archive.submit(c,{1,1,1}));assert(archive.occupied==2 && archive.donor(1)->candidate.owner==8);
 assert(archive.coverage()==2.0/512 && archive.valid());c.trajectory[0]=NAN;assert(!archive.submit(c,{.5,.5,.5}));
 State state,repeat;std::array<Candidate,150> batch{};
 for(std::size_t i=0;i<batch.size();++i) {
  auto& v=batch[i];v.owner=i;v.generation=1;v.fitness=i/150.0;v.explicit_axis=(i%8)/7.0;
  for(std::size_t j=0;j<16;++j) v.trajectory[j]=((i*7+j*13)%101)/100.0;
  for(std::size_t j=0;j<8;++j) v.policy[j]=((i+j)%17)/2.0-4;
 }
 assert(state.update(batch.data(),150,1));assert(repeat.update(batch.data(),150,1));
 std::array<std::uint8_t,wire_bytes> bytes{},same{},saved{};
 assert(save(state,bytes));assert(save(repeat,same));assert(bytes==same);saved=bytes;
 State restored;assert(load(bytes,restored));assert(save(restored,same) && same==bytes);
 const auto count=state.samples;
 batch[0].policy[0]=INFINITY;assert(!state.update(batch.data(),150,2));assert(state.samples==count);
 assert(save(state,same) && same==bytes);batch[0].policy[0]=1;
 assert(!state.update(batch.data(),151,2));assert(!state.update(batch.data(),150,1));
 assert(state.update(batch.data(),150,2));assert(state.samples==300 && state.valid());
 for(const auto& cell:state.archive.cells) if(cell.occupied) {
  const auto expected=state.encoder.project(cell.candidate);for(std::size_t i=0;i<3;++i) assert(std::abs(expected[i]-cell.descriptor[i])<1e-12);
 }
 bytes[0]^=1;assert(!load(bytes,restored));bytes=saved;bytes[4]=2;assert(!load(bytes,restored));
 assert(!load(std::span<const std::uint8_t>(saved).first(wire_bytes-1),restored));
 // Reject finite-looking bad orthogonality, occupancy counts, and poisoned descriptors.
 bytes=saved;for(std::size_t i=0;i<128;++i) bytes[284+i]=bytes[156+i];assert(!load(bytes,restored));
 bytes=saved;bytes[24]=0xff;bytes[25]=0xff;assert(!load(bytes,restored));
 bytes=saved;const auto bad=std::bit_cast<std::uint64_t>(static_cast<double>(NAN));for(int i=0;i<8;++i) bytes[28+i]=static_cast<std::uint8_t>(bad>>(8*i));assert(!load(bytes,restored));
 assert(allocations==0);forbid=false;
 std::printf("QD: Oja known PCs, Gram-Schmidt, rebin, MAP-Elites, binary restore/corruption, bounded batch and zero heap passed; state=%zu wire=%zu\n",sizeof(State),wire_bytes);
}
