#include "navigation/recast_rvo2_nav.hpp"
#include "physics/spatial.hpp"
#include <cassert>
#include <algorithm>
#include <cmath>
#include <iostream>
#include <limits>

int main() {
    using namespace girassol;using nav::Vec;
    Mesh floor;
    // Independent two-rectangle floor corridor with a rectangular forbidden hole.
    auto quad=[&](float x,float z,float X,float Z) {
        const auto n=static_cast<std::uint32_t>(floor.vertices.size());
        for(auto p:std::array<std::array<float,2>,4>{{{x,z},{x,Z},{X,Z},{X,z}}}) floor.vertices.push_back({p[0],0,p[1],0,0,0,1,0});
        for(auto i:std::array<std::uint32_t,6>{0,1,2,0,2,3}) floor.indices.push_back(n+i);
    };
    quad(-4,-4,4,-1);quad(-4,1,4,4);quad(-4,-1,-1,1);quad(1,-1,4,1);
    nav::DistrictNavMesh mesh(floor);
    const auto path=mesh.path({-3,0},{3,0});assert(path.size()>1 && mesh.bytes()<2*1024*1024);
    Vec previous{-3,0};
    for(auto q:path) { for(int i=0;i<=100;++i) { const auto f=i/100.0;const double x=previous.x+(q.x-previous.x)*f,z=previous.z+(q.z-previous.z)*f;assert(!(std::abs(x)<1.17 && std::abs(z)<1.17)); } previous=q; }
    int failures=0;bool finished=false;
    for(int i=0;i<500;++i) {
        nav::DistrictNavMesh::allocation_failure_after(i);
        try { nav::DistrictNavMesh trial(floor);finished=true;break; } catch(const std::bad_alloc&) { ++failures; }
    }
    nav::DistrictNavMesh::allocation_failure_after(-1);assert(finished && failures>20);
    int queryFailures=0;finished=false;
    for(int i=0;i<100;++i) {
        nav::DistrictNavMesh::allocation_failure_after(i);
        try { assert(mesh.path({-3,0},{3,0}).size()>1);finished=true;break; } catch(const std::bad_alloc&) { ++queryFailures; }
    }
    nav::DistrictNavMesh::allocation_failure_after(-1);assert(finished && queryFailures>3);
    std::array<Vec,2> positions{{{-1,0},{1,0}}},velocities{};
    double closest=100;
    for(int step=0;step<360;++step) {
        std::array<Vec,2> next{};
        for(std::size_t i=0;i<2;++i) {
            const double target=i==0?2:-2;const Vec preferred{std::clamp((target-positions[i].x)*2,-.9,.9),i==0?.025:-.025};
            next[i]=nav::orca(i,positions.data(),velocities.data(),2,preferred,.18,.9,1./60);
        }
        velocities=next;
        for(std::size_t i=0;i<2;++i) { assert(std::hypot(next[i].x,next[i].z)<=.900000001);positions[i].x+=next[i].x/60;positions[i].z+=next[i].z/60; }
        closest=std::min(closest,std::hypot(positions[0].x-positions[1].x,positions[0].z-positions[1].z));assert(closest>.359);
    }
    assert(positions[0].x>1.5 && positions[1].x< -1.5);
    const std::vector<RoomSpec> rooms{{3,3,3.8f,2.6f,0,0},{8,2.5f,3.8f,2.5f,1,0},{2.5f,6.5f,3,2,2,0},{8.5f,6.5f,3,2,3,0},{5.5f,.6f,3,1.4f,4,0},{5.4f,9,2.3f,1.8f,5,0}};
    physics::Scene scene(rooms,42);
    const auto motions=scene.step({{{3,3,3},0,4}},42,1);
    assert(motions.size()==1 && motions[0].route.size()==31);
    // Interaction geometry regression: a valid library aisle must remain usable.
    auto librarian=scene.step({{{5.5,0,.6},0,4}},42,1).front();
    for(int tick=2;tick<20 && !librarian.reached;++tick) librarian=scene.step({librarian.body},42,tick).front();
    assert(librarian.reached && scene.clear(librarian.body.p.x,librarian.body.p.z));
    for(std::size_t destination=0;destination<rooms.size();++destination) {
        auto traveler=scene.step({{{5.5,0,4.5},0,destination}},42,1).front();
        for(int tick=2;tick<180 && !traveler.reached;++tick) traveler=scene.step({traveler.body},42,tick).front();
        if(!traveler.reached) std::cerr<<"Unreachable actual city room "<<destination<<" at "<<traveler.body.p.x<<","<<traveler.body.p.z<<"\n";
        assert(traveler.reached);
    }
    std::cout<<"Recast/Detour obstacle corridor, radius erosion, "<<failures<<" build / "<<queryFailures<<" query allocation failures, ORCA reciprocal passing and real city scene passed\n";
}
