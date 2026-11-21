#pragma once
#include "../city_mesh.hpp"
#include <memory>
#include <vector>

namespace girassol::physics {
inline constexpr double fixed_dt=1.0/60.0, radius=.18, speed=.9;
inline constexpr int substeps=30;
struct Point { double x=0,y=0,z=0; };
struct Aabb { double minX=0,minZ=0,maxX=0,maxZ=0; };
struct Velocity { double x=0,z=0; };
struct OrcaAgent { Point position; Velocity velocity; Velocity preferredVelocity; double radius=girassol::physics::radius; };
Point resolveCircleAABB(Point center,double bodyRadius,const Aabb& box) noexcept;
std::vector<Velocity> stepRvo2OrcaVelocities(const std::vector<OrcaAgent>& agents,double dt=fixed_dt,double timeHorizon=2.0);
struct Body { Point p; double vy=0; std::size_t destination=0; };
struct Motion { Body body; double ground=0,distance=0,heading=0; bool reached=false,corrected=false; std::vector<Point> route; };
/** Immutable navigation and support surfaces derived from the actual Filament triangle mesh. */
class Scene {
    struct Data;
    std::shared_ptr<const Data> data;
public:
    Scene(const std::vector<RoomSpec>& rooms,std::uint64_t seed);
    double surface(double x,double z) const;
    bool clear(double x,double z) const;
    std::vector<Motion> step(const std::vector<Body>& bodies,std::uint64_t seed,std::uint64_t tick) const;
};
}
