#include "physics/spatial.hpp"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <vector>
int main() {
    using namespace girassol::physics;
    const Aabb obstacle{0,0,1,1};
    const Point resolved=resolveCircleAABB({.5,0,.5},.2,obstacle);
    const double dx=resolved.x-std::clamp(resolved.x,obstacle.minX,obstacle.maxX);
    const double dz=resolved.z-std::clamp(resolved.z,obstacle.minZ,obstacle.maxZ);
    assert(std::hypot(dx,dz)>=.2-1e-5);
    std::vector<OrcaAgent> agents{
        {{-.1,0,0},{0,0},{.7,0},.18},
        {{ .1,0,0},{0,0},{-.7,0},.18}
    };
    const auto first=stepRvo2OrcaVelocities(agents,1.0/60.0,2.0);
    const auto second=stepRvo2OrcaVelocities(agents,1.0/60.0,2.0);
    assert(first.size()==2&&second.size()==2);
    for(std::size_t i=0;i<2;++i) {
        assert(first[i].x==second[i].x&&first[i].z==second[i].z);
        assert(std::hypot(first[i].x,first[i].z)<=speed+1e-6);
    }
    const double before=std::hypot(agents[1].position.x-agents[0].position.x,agents[1].position.z-agents[0].position.z);
    const double after=std::hypot((agents[1].position.x+first[1].x/60.0)-(agents[0].position.x+first[0].x/60.0),
                                  (agents[1].position.z+first[1].z/60.0)-(agents[0].position.z+first[0].z/60.0));
    assert(after>before);
    return 0;
}