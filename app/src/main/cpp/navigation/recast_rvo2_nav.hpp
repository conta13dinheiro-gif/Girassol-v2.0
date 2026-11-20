#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>
#include "../city_mesh.hpp"

namespace girassol::nav {
struct Vec { double x=0,z=0; };
/** ORCA's reciprocal velocity half-planes; fixed 150-agent / 149-line budget. */
Vec orca(std::size_t self,const Vec* positions,const Vec* velocities,std::size_t count,Vec preferred,double radius,double speed,double dt);
/** Real Recast voxelization/erosion + Detour polygon A*. Tile <=2MiB, build/query arena <=32MiB. */
class DistrictNavMesh {
    std::vector<unsigned char> tile_;
public:
    explicit DistrictNavMesh(const Mesh& mesh);
    std::vector<Vec> path(Vec start,Vec goal,bool closestReachable=false) const;
    std::size_t bytes() const noexcept { return tile_.size(); }
    static constexpr std::size_t arena_bytes=32*1024*1024;
    // Deterministic failure injection for allocator tests, thread local, disabled with -1.
    static void allocation_failure_after(std::int64_t successful);
};
}
