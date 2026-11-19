#include "city_mesh.hpp"
#include <cassert>
#include <cmath>
#include <cstring>
#include <limits>

using namespace girassol;
static void check(const Mesh& mesh) {
    assert(!mesh.vertices.empty() && mesh.indices.size()%3==0);
    for (const auto& v : mesh.vertices) {
        assert(std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z));
        assert(std::abs(v.qx*v.qx+v.qy*v.qy+v.qz*v.qz+v.qw*v.qw-1)<1e-5);
        const auto tag=v.rgba>>24; assert((tag>=1 && tag<=8) || tag==10 || (tag>=12 && tag<=16));
    }
    for (std::size_t i=0;i<mesh.indices.size();i+=3) {
        for (auto j=i;j<i+3;++j) assert(mesh.indices[j]<mesh.vertices.size());
        const auto& a=mesh.vertices[mesh.indices[i]];
        const auto& b=mesh.vertices[mesh.indices[i+1]];
        const auto& c=mesh.vertices[mesh.indices[i+2]];
        const float ux=b.x-a.x,uy=b.y-a.y,uz=b.z-a.z,vx=c.x-a.x,vy=c.y-a.y,vz=c.z-a.z;
        const float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
        const float qnx=2*(a.qx*a.qz+a.qw*a.qy),qny=2*(a.qy*a.qz-a.qw*a.qx),qnz=1-2*(a.qx*a.qx+a.qy*a.qy);
        assert(nx*qnx+ny*qny+nz*qnz>0); // Tangent frame and front-face winding agree.
    }
}
int main() {
    RoomSpec rooms[]={{3,3,3.8f,2.6f,0,9},{8,2.5f,3.8f,2.5f,1,1},{2.5f,6.5f,3,2,2,1},
        {8.5f,6.5f,3,2,3,1},{5.5f,.6f,3,1.4f,4,1},{5.4f,9,2.3f,1.8f,5,1},{9,10,1.7f,1.5f,6,1}};
    const auto original=rooms[0];
    Mesh a,b,c;
    assert(city_mesh(rooms,sizeof(rooms),7,42,a)); check(a);
    assert(city_mesh(rooms,sizeof(rooms),7,42,b));
    assert(a.indices==b.indices && a.vertices.size()==b.vertices.size());
    assert(std::memcmp(a.vertices.data(),b.vertices.data(),a.vertices.size()*sizeof(Vertex))==0);
    assert(std::memcmp(&original,&rooms[0],sizeof(RoomSpec))==0);
    assert(city_mesh(rooms,sizeof(rooms),7,43,c));
    assert(std::memcmp(a.vertices.data(),c.vertices.data(),a.vertices.size()*sizeof(Vertex))!=0);
    auto actor=citizen_mesh(0xff98bbcc); check(actor);
    float maxY=0; for (auto v : actor.vertices) maxY=std::max(maxY,v.y); assert(maxY>1.3f && maxY<1.4f);
    for (unsigned role=1;role<=3;++role) { auto posed=citizen_mesh(0xff98bbcc,role); check(posed); assert(posed.vertices.size()>actor.vertices.size()); }
    Mesh landscape, building;
    assert(city_mesh(rooms,sizeof(rooms),7,42,landscape,true,false)); check(landscape);
    assert(city_mesh(rooms,sizeof(rooms),7,42,building,false,true)); check(building);
    assert(a.vertices.size()==landscape.vertices.size()+building.vertices.size());
    assert(!city_mesh(nullptr,0,1,0,b));
    assert(!city_mesh(rooms,sizeof(rooms)-1,7,0,b));
    assert(!city_mesh(rooms,sizeof(rooms),65,0,b));
    rooms[0].x=std::numeric_limits<float>::quiet_NaN(); assert(!city_mesh(rooms,sizeof(rooms),7,0,b));
    rooms[0]=original; rooms[0].width=0; assert(!city_mesh(rooms,sizeof(rooms),7,0,b));
    rooms[0]=original; rooms[0].kind=7; assert(!city_mesh(rooms,sizeof(rooms),7,0,b));
    assert(city_mesh(nullptr,0,0,0,b)); check(b);
    std::vector<unsigned char> vb(a.vertices.size()*sizeof(Vertex),0x55),ib(a.indices.size()*4,0x66);
    assert(!copy_mesh(a,vb.data(),vb.size(),ib.data(),ib.size()-1));
    for (auto byte : vb) assert(byte==0x55);
    for (auto byte : ib) assert(byte==0x66);
    assert(!copy_mesh(a,vb.data(),vb.size(),vb.data()+4,ib.size()));
    assert(copy_mesh(a,vb.data(),vb.size(),ib.data(),ib.size()));
    assert(std::memcmp(vb.data(),a.vertices.data(),vb.size())==0);
    assert(std::memcmp(ib.data(),a.indices.data(),ib.size())==0);
    // Maximum supported input is still bounded in memory.
    RoomSpec maximum[64]; for (auto& r : maximum) r=original;
    assert(city_mesh(maximum,sizeof(maximum),64,42,b)); check(b);
    assert(b.vertices.size()<250000 && b.indices.size()<500000);
}
