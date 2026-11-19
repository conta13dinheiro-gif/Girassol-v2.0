#include "city_mesh.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <utility>

namespace girassol {
namespace {
using P = std::array<float, 3>;
constexpr std::uint32_t color(unsigned r, unsigned g, unsigned b, unsigned surface = 2) {
    return r | (g << 8) | (b << 16) | (surface << 24);
}
Vertex vertex(P p, P n, std::uint32_t rgba) {
    float qx=-n[1], qy=n[0], qz=0, qw=1+n[2];
    if (qw < 1e-6f) { qx=0; qy=1; qz=0; qw=0; }
    const float length=std::sqrt(qx*qx+qy*qy+qz*qz+qw*qw);
    return {p[0],p[1],p[2],qx/length,qy/length,qz/length,qw/length,rgba};
}
void face(Mesh& m, const std::array<P, 4>& points, P n, std::uint32_t rgba) {
    const auto start=static_cast<std::uint32_t>(m.vertices.size());
    for (auto p:points) m.vertices.push_back(vertex(p,n,rgba));
    for (auto i:{0U,1U,2U,0U,2U,3U}) m.indices.push_back(start+i);
}
void box(Mesh& m, float x, float y, float z, float w, float h, float d, std::uint32_t c) {
    const float X=x+w, Y=y+h, Z=z+d;
    face(m, {{{x,y,Z},{X,y,Z},{X,Y,Z},{x,Y,Z}}}, {0,0,1}, c);
    face(m, {{{X,y,z},{x,y,z},{x,Y,z},{X,Y,z}}}, {0,0,-1}, c);
    face(m, {{{X,y,Z},{X,y,z},{X,Y,z},{X,Y,Z}}}, {1,0,0}, c);
    face(m, {{{x,y,z},{x,y,Z},{x,Y,Z},{x,Y,z}}}, {-1,0,0}, c);
    face(m, {{{x,Y,Z},{X,Y,Z},{X,Y,z},{x,Y,z}}}, {0,1,0}, c);
    face(m, {{{x,y,z},{X,y,z},{X,y,Z},{x,y,Z}}}, {0,-1,0}, c);
}
// Analytic curved surfaces with tangent frames; no imported asset dependency.
void triangle(Mesh& m, P a, P b, P c, std::uint32_t rgba) {
    P u{b[0]-a[0],b[1]-a[1],b[2]-a[2]}, v{c[0]-a[0],c[1]-a[1],c[2]-a[2]};
    P n{u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]};
    const float length=std::sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);
    if (length<1e-8f) return;
    for (auto& x:n) x/=length;
    for (auto p:{a,b,c}) {
        m.indices.push_back(static_cast<std::uint32_t>(m.vertices.size()));
        m.vertices.push_back(vertex(p,n,rgba));
    }
}
void ellipsoid(Mesh& m, float x,float y,float z,float rx,float ry,float rz,std::uint32_t rgba,int segments=12,int rings=8) {
    constexpr float pi=3.14159265359f;
    auto point=[&](int row,int col) -> P {
        const float p=pi*static_cast<float>(row)/static_cast<float>(rings);
        const float t=2*pi*static_cast<float>(col)/static_cast<float>(segments);
        return {x+rx*std::sin(p)*std::cos(t),y+ry*std::cos(p),z+rz*std::sin(p)*std::sin(t)};
    };
    for (int j=0;j<rings;++j) for (int i=0;i<segments;++i) {
        auto a=point(j,i),b=point(j+1,i),c=point(j+1,i+1),d=point(j,i+1);
        auto curved=[&](P first,P second,P third) {
            for (auto p:{first,second,third}) {
                P n{(p[0]-x)/(rx*rx),(p[1]-y)/(ry*ry),(p[2]-z)/(rz*rz)};
                const float length=std::sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);
                for (auto& component:n) component/=length;
                m.indices.push_back(static_cast<std::uint32_t>(m.vertices.size()));
                m.vertices.push_back(vertex(p,n,rgba));
            }
        };
        if (j<rings-1) curved(a,c,b);
        if (j>0) curved(a,d,c);
    }
}
void post(Mesh& m,float x,float y,float z,float radius,float height,std::uint32_t rgba) {
    constexpr int sides=10; constexpr float pi=3.14159265359f;
    for (int i=0;i<sides;++i) {
        const float a=2*pi*static_cast<float>(i)/sides,b=2*pi*static_cast<float>(i+1)/sides;
        P p{x+radius*std::cos(a),y,z+radius*std::sin(a)},q{x+radius*std::cos(b),y,z+radius*std::sin(b)};
        P u{p[0],y+height,p[2]},v{q[0],y+height,q[2]};
        triangle(m,p,u,v,rgba); triangle(m,p,v,q,rgba);
        triangle(m,{x,y+height,z},v,u,rgba); triangle(m,{x,y,z},p,q,rgba);
    }
}
void table(Mesh& m,float x,float z,float w,float d,std::uint32_t c) {
    box(m,x,.55f,z,w,.10f,d,c);
    for (float dx : {.06f,w-.12f}) for (float dz : {.06f,d-.12f})
        post(m,x+dx,.13f,z+dz,.025f,.42f,color(60,70,76,4));
}
void tree(Mesh& m,float x,float z,float size,unsigned variation) {
    post(m,x,0,z,.10f*size,1.35f*size,color(89,61,39,3));
    for (int j=0;j<3;++j) {
        const float t=static_cast<float>(j);
        ellipsoid(m,x+std::sin(t*3+static_cast<float>(variation))*.15f*size,
            (1.2f+t*.4f)*size,z+std::cos(t*2)*.10f*size,
            (.65f-t*.12f)*size,.54f*size,(.58f-t*.10f)*size,
            color(42+variation%25,96+variation%35,65+variation%16,7));
    }
}
void roof(Mesh& m,const RoomSpec& r,float height) {
    const float x=r.x-r.width*.5f-.14f,X=r.x+r.width*.5f+.14f;
    const float z=r.z-r.depth*.5f-.14f,Z=r.z+r.depth*.5f+.14f;
    const float top=height+.55f;
    const auto c=color(52,75,79,4);
    // Gabled roof, with an open front quarter for a readable inhabited cutaway.
    triangle(m,{x,height,z},{r.x,top,z},{r.x,top,Z},c);
    triangle(m,{x,height,z},{r.x,top,Z},{x,height,Z},c);
    triangle(m,{r.x,top,z},{X,height,z},{X,height,Z},c);
    triangle(m,{r.x,top,z},{X,height,Z},{r.x,top,Z},c);
    // Solar collectors on the roof's lit slope.
    for (int i=0;i<3;++i) {
        const float zz=z+.18f+(Z-z)*static_cast<float>(i)/3;
        const float ww=(r.width*.5f)*.58f;
        triangle(m,{r.x+.08f,top-.015f,zz},{r.x+ww,top-.24f,zz},{r.x+ww,top-.24f,zz+.20f},color(21,56,86,4));
        triangle(m,{r.x+.08f,top-.015f,zz},{r.x+ww,top-.24f,zz+.20f},{r.x+.08f,top-.015f,zz+.20f},color(21,56,86,4));
    }
}
void building(Mesh& m,const RoomSpec& r,std::uint64_t seed) {
    const float x=r.x-r.width/2,z=r.z-r.depth/2;
    const float h=r.kind==4?1.7f:1.4f;
    box(m,x-.10f,.01f,z-.10f,r.width+.2f,.13f,r.depth+.2f,color(135,139,125));
    box(m,x,.14f,z,r.width,.035f,r.depth,color(134,108,76,3));
    // Two detailed enclosing facades; the south and east are deliberately cut away.
    box(m,x,.16f,z,r.width,h,.12f,color(191,180,151));
    box(m,x,.16f,z,.12f,h,r.depth,color(172,163,137));
    box(m,x-.025f,h+.12f,z-.025f,r.width+.05f,.075f,.17f,color(74,98,87,4));
    for (float xx=x+.35f;xx<x+r.width-.2f;xx+=.65f) {
        box(m,xx,.62f,z+.125f,.38f,.57f,.035f,color(87,151,166,5));
        box(m,xx+.17f,.62f,z+.163f,.025f,.57f,.025f,color(70,86,82,4));
        box(m,xx-.03f,.58f,z+.12f,.44f,.04f,.08f,color(219,202,158));
    }
    const float w=r.width*.67f;
    if (r.kind==1 || r.kind==6) {
        const int beds=std::max(1,std::min(3,static_cast<int>(r.width/.8f)));
        for (int i=0;i<beds;++i) {
            const float bx=x+.23f+static_cast<float>(i)*r.width/static_cast<float>(beds);
            box(m,bx,.18f,z+.4f,.49f,.18f,r.depth*.63f,color(89,63,43,3));
            ellipsoid(m,bx+.245f,.39f,z+.6f,.23f,.10f,.18f,color(225,213,180,10),8,6);
            box(m,bx,.36f,z+.78f,.49f,.10f,r.depth*.39f,color(52,111,113,10));
        }
        roof(m,r,h+.22f);
    } else if (r.kind==4) {
        box(m,x+.18f,.18f,z+.18f,w,1.0f,.27f,color(104,72,42,3));
        for (int shelf=0;shelf<2;++shelf) for (int i=0;i<9;++i) {
            const unsigned c=static_cast<unsigned>((seed+r.style+static_cast<unsigned>(i)*37)%65);
            box(m,x+.23f+static_cast<float>(i)*w/10,.28f+static_cast<float>(shelf)*.44f,z+.16f,w/14,.31f,.19f,color(91+c,100+c/2,92+c,10));
        }
        table(m,x+.3f,z+r.depth*.68f,w,.32f,color(160,110,65,3));
        // Open book and cognitive terminal.
        box(m,r.x-.15f,.65f,r.z+.18f,.28f,.025f,.21f,color(226,219,188,10));
    } else if (r.kind==2) {
        table(m,x+.22f,z+.32f,w,.35f,color(155,101,58,3));
        post(m,r.x,.16f,r.z+.35f,.12f,.55f,color(64,114,140,5));
        post(m,r.x,.72f,r.z+.35f,.14f,.06f,color(90,172,192,6));
        post(m,x+.48f,.66f,z+.48f,.095f,.22f,color(114,119,120,4));
    } else if (r.kind==3) {
        table(m,x+.2f,z+.45f,w,.5f,color(155,101,58,3));
        for (int i=0;i<4;++i) for (int j=0;j<2;++j)
            ellipsoid(m,x+.4f+static_cast<float>(i)*w/5,.76f,z+.6f+static_cast<float>(j)*.14f,.07f,.07f,.07f,color(196,123+static_cast<unsigned>(j)*30,51,1),8,4);
        // Timber pergola with fabric strips.
        for (float dx:{.12f,r.width-.16f}) post(m,x+dx,.16f,z+r.depth-.2f,.045f,1.4f,color(111,77,46,3));
        for (int i=0;i<6;++i) box(m,x+.1f+static_cast<float>(i)*r.width/6,1.55f,z+.4f,r.width/6-.025f,.04f,r.depth-.5f,color(i%2?217:63,i%2?193:117,i%2?141:96,10));
    } else {
        table(m,x+.27f,z+.4f,w,.40f,color(153,105,65,3));
        if (r.kind==5) {
            post(m,r.x,.66f,r.z,.10f,.23f,color(78,94,101,4));
            box(m,r.x-.17f,.70f,r.z-.04f,.34f,.06f,.08f,color(71,104,124,4));
        } else {
            box(m,x+.3f,.18f,z+r.depth*.7f,w,.32f,.40f,color(48,111,90,10));
            box(m,x+.3f,.49f,z+r.depth*.87f,w,.28f,.10f,color(59,130,105,10));
        }
    }
    // Warm wall luminaire is an explicit emissive surface, not a bright-color heuristic.
    post(m,r.x,.85f,z+.24f,.045f,.40f,color(255,202,113,8));
    // Living roof planter on the cutaway wall.
    box(m,x-.05f,h+.20f,z+r.depth-.5f,.24f,.12f,.45f,color(116,86,52,3));
    ellipsoid(m,x+.07f,h+.45f,z+r.depth-.28f,.20f,.24f,.25f,color(49,120,74,7),8,6);
}
bool disjoint(const void* a, std::size_t na, const void* b, std::size_t nb) {
    const auto x=reinterpret_cast<std::uintptr_t>(a), y=reinterpret_cast<std::uintptr_t>(b);
    return x<=y ? na<=y-x : nb<=x-y;
}
}

bool city_mesh(const void* input, std::size_t bytes, std::size_t count, std::uint64_t seed, Mesh& out, bool surroundings, bool buildings) {
    if (count>64 || count>bytes/sizeof(RoomSpec) || (count && !input)) return false;
    std::vector<RoomSpec> rooms(count);
    if (count) std::memcpy(rooms.data(),input,count*sizeof(RoomSpec));
    for (auto r : rooms) {
        if (!std::isfinite(r.x) || !std::isfinite(r.z) || !std::isfinite(r.width) || !std::isfinite(r.depth) ||
            std::abs(r.x)>100 || std::abs(r.z)>100 || r.width<.5f || r.width>32 || r.depth<.5f || r.depth>32 ||
            r.kind<0 || r.kind>6) return false;
    }
    Mesh m;
    float loX=-1,loZ=-1,hiX=12,hiZ=11;
    for (auto r:rooms) { loX=std::min(loX,r.x-r.width/2-.5f); hiX=std::max(hiX,r.x+r.width/2+.5f);
        loZ=std::min(loZ,r.z-r.depth/2-.5f); hiZ=std::max(hiZ,r.z+r.depth/2+.5f); }
    if (surroundings) {
        // Smooth peripheral relief; occupied parcels remain level with canonical positions.
        auto terrain=[&](float x,float z) -> float {
            const float outside=std::max({loX-x,x-hiX,loZ-z,z-hiZ,0.f});
            return -.025f+std::min(outside*.18f,.8f)*(std::sin(x*.6f)*std::cos(z*.5f)*.32f+.32f);
        };
        constexpr int grid=28;
        for (int iz=0;iz<grid;++iz) for (int ix=0;ix<grid;++ix) {
            const float x=loX-5+(hiX-loX+10)*static_cast<float>(ix)/grid;
            const float z=loZ-5+(hiZ-loZ+10)*static_cast<float>(iz)/grid;
            const float X=loX-5+(hiX-loX+10)*static_cast<float>(ix+1)/grid;
            const float Z=loZ-5+(hiZ-loZ+10)*static_cast<float>(iz+1)/grid;
            const auto c=color(46+static_cast<unsigned>(ix*7+iz*3)%13,92+static_cast<unsigned>(iz*3)%16,58,1);
            triangle(m,{x,terrain(x,z),z},{x,terrain(x,Z),Z},{X,terrain(X,Z),Z},c);
            triangle(m,{x,terrain(x,z),z},{X,terrain(X,Z),Z},{X,terrain(X,z),z},c);
        }
        // Walkways link the actual published buildings to a central civic spine.
        for (auto r:rooms) {
            box(m,std::min(5.5f,r.x),.008f,r.z-.23f,std::abs(r.x-5.5f)+.08f,.025f,.46f,color(159,155,133));
            box(m,5.27f,.01f,std::min(4.5f,r.z),.46f,.025f,std::abs(4.5f-r.z)+.08f,color(159,155,133));
        }
        post(m,5.5f,.02f,4.5f,.62f,.10f,color(143,149,138));
        post(m,5.5f,.12f,4.5f,.48f,.04f,color(45,115,142,6));
        for (int i=0;i<16;++i) {
            const float t=static_cast<float>(i)/16*6.2831853f;
            const float x=(loX+hiX)*.5f+std::cos(t)*((hiX-loX)*.5f+1.4f);
            const float z=(loZ+hiZ)*.5f+std::sin(t)*((hiZ-loZ)*.5f+1.4f);
            tree(m,x,z,.85f+static_cast<float>((seed+static_cast<unsigned>(i)*11)%24)/100,static_cast<unsigned>(i)*13);
            if (i%2==0) { post(m,x+.3f,0,z+.3f,.028f,1.5f,color(58,75,78,4));
                ellipsoid(m,x+.3f,1.53f,z+.3f,.09f,.12f,.09f,color(255,207,133,8),8,4); }
        }
        // Small water garden, bridge and shoreline stones outside occupied parcels.
        ellipsoid(m,loX-2,.035f,loZ-1,1.25f,.03f,1.55f,color(41,112,133,6),24,4);
        for (int i=0;i<7;++i) {
            const float t=static_cast<float>(i)*.8976f;
            ellipsoid(m,loX-2+std::cos(t)*1.3f,.05f,loZ-1+std::sin(t)*1.6f,.22f,.16f,.19f,color(115,124,115),8,6);
        }
        for (int i=0;i<9;++i) box(m,loX-2.4f,.16f,loZ-2.4f+static_cast<float>(i)*.3f,.8f,.06f,.27f,color(135,91,49,3));
    }
    if (buildings) for (auto r:rooms) building(m,r,seed);
    out=std::move(m);
    return true;
}

Mesh citizen_mesh(std::uint32_t rgba, unsigned role) {
    Mesh m;
    auto tagged=[&](unsigned tag) { return (rgba&0xffffffU)|(tag<<24); };
    const auto skin=color(204,159,116,12);
    // Separate tagged articulated limbs, animated around their joints in the vertex shader.
    ellipsoid(m,0,.72f,0,.18f,.27f,.115f,tagged(10));
    ellipsoid(m,0,1.13f,0,.132f,.17f,.125f,skin);
    ellipsoid(m,0,1.25f,-.015f,.14f,.065f,.132f,color(49,38,29,12));
    for (int side=0;side<2;++side) {
        const float x=side==0?-.09f:.09f;
        ellipsoid(m,x,.26f,0,.066f,.255f,.071f,color(48,63,75,side==0?15:16));
        ellipsoid(m,x,.055f,.035f,.076f,.047f,.12f,color(36,42,47,side==0?15:16),10,6);
        ellipsoid(m,side==0?-.23f:.23f,.66f,0,.055f,.21f,.061f,tagged(side==0?13:14),10,6);
        ellipsoid(m,side==0?-.23f:.23f,.44f,0,.048f,.062f,.052f,color(204,159,116,side==0?13:14),10,6);
    }
    // Eyes face +Z and remain with the animated head.
    for (float x:{-.046f,.046f}) ellipsoid(m,x,1.155f,.116f,.017f,.019f,.014f,color(30,37,40,12),8,4);
    if (role==1) { // Writing/study: satchel and book.
        box(m,-.16f,.58f,-.19f,.32f,.24f,.10f,color(122,81,43,3));
        box(m,.21f,.46f,.015f,.10f,.13f,.16f,color(58,133,163,14));
    } else if (role==2) { // Building: protective cap.
        ellipsoid(m,0,1.29f,0,.16f,.065f,.145f,color(230,169,55,12));
    } else if (role==3) { // Cafe: apron.
        box(m,-.12f,.49f,.111f,.24f,.35f,.025f,color(221,211,170,10));
    }
    return m;
}

bool copy_mesh(const Mesh& m, void* v, std::size_t nv, void* i, std::size_t ni) {
    const auto vb=m.vertices.size()*sizeof(Vertex), ib=m.indices.size()*sizeof(std::uint32_t);
    if (!v || !i || nv<vb || ni<ib || !disjoint(v,vb,i,ib)) return false;
    std::memcpy(v,m.vertices.data(),vb); std::memcpy(i,m.indices.data(),ib);
    return true;
}
}
