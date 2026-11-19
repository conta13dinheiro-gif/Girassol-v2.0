#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace girassol {
// Filament needs a tangent FRAME quaternion, rather than a three-float normal.
struct Vertex { float x, y, z, qx, qy, qz, qw; std::uint32_t rgba; };
static_assert(sizeof(Vertex) == 32);
struct RoomSpec { float x, z, width, depth; std::int32_t kind; std::uint32_t style; };
static_assert(sizeof(RoomSpec) == 24);
struct Mesh { std::vector<Vertex> vertices; std::vector<std::uint32_t> indices; };
bool city_mesh(const void* rooms, std::size_t bytes, std::size_t count, std::uint64_t seed, Mesh& out, bool surroundings = true, bool buildings = true);
Mesh citizen_mesh(std::uint32_t rgba, unsigned role = 0);
// Validate both destinations before writing either. Destinations must be disjoint.
bool copy_mesh(const Mesh& mesh, void* vertices, std::size_t vertex_bytes,
               void* indices, std::size_t index_bytes);
}
