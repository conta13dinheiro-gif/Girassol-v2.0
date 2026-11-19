#include "spatial.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <queue>
#include <stdexcept>

namespace girassol::physics {
namespace {
constexpr double baseCell=.10,eps=1e-6;
struct Box { double x,z,X,Z; };
struct Floor { Point a,b,c; };
bool overlap(double x,double z,const Box& b,double r=radius) {
    const double dx=x-std::clamp(x,b.x,b.X),dz=z-std::clamp(z,b.z,b.Z);
    return dx*dx+dz*dz<(r-eps)*(r-eps);
}
bool inside(const Point& p,const RoomSpec& r) {
    return std::abs(p.x-r.x)<=r.width*.5-radius && std::abs(p.z-r.z)<=r.depth*.5-radius;
}
std::uint64_t mix(std::uint64_t x) { x^=x>>30;x*=0xbf58476d1ce4e5b9ULL;x^=x>>27;x*=0x94d049bb133111ebULL;return x^(x>>31); }
}
struct Scene::Data {
    std::vector<RoomSpec> rooms;
    std::vector<Box> walls;
    std::vector<Floor> floors;
    double x=-5,z=-5,cell=baseCell; int width=0,depth=0;
    std::vector<std::vector<int>> obstacles,supports;
    std::vector<unsigned char> blocked;
    int at(double X,double Z) const {
        const int ix=static_cast<int>(std::floor((X-x)/cell)),iz=static_cast<int>(std::floor((Z-z)/cell));
        return ix<0 || iz<0 || ix>=width || iz>=depth ? -1 : iz*width+ix;
    }
    Point point(int i) const { return {x+(i%width+.5)*cell,0,z+(i/width+.5)*cell}; }
    template<class F> void cells(Box b,F f) const {
        const int loX=std::clamp(static_cast<int>(std::floor((b.x-x)/cell)),0,width-1);
        const int hiX=std::clamp(static_cast<int>(std::floor((b.X-x)/cell)),0,width-1);
        const int loZ=std::clamp(static_cast<int>(std::floor((b.z-z)/cell)),0,depth-1);
        const int hiZ=std::clamp(static_cast<int>(std::floor((b.Z-z)/cell)),0,depth-1);
        for(int iz=loZ;iz<=hiZ;++iz) for(int ix=loX;ix<=hiX;++ix) f(iz*width+ix);
    }
    bool clear(double X,double Z) const {
        const int i=at(X,Z); if(i<0) return false;
        for(int k:obstacles[static_cast<std::size_t>(i)]) if(overlap(X,Z,walls[static_cast<std::size_t>(k)])) return false;
        return true;
    }
    Point repair(Point p) const {
        // Legacy saves can start inside furniture; deterministic nearest free cell repairs only that state.
        if(clear(p.x,p.z)) return p;
        double best=std::numeric_limits<double>::infinity();Point q=p;
        for(std::size_t i=0;i<blocked.size();++i) if(!blocked[i]) {
            auto c=point(static_cast<int>(i));const double d=std::hypot(c.x-p.x,c.z-p.z);
            if(d<best) { best=d;q.x=c.x;q.z=c.z; }
        }
        if(!std::isfinite(best)) throw std::runtime_error("No navigable surface");
        return q;
    }
    Point push(Point p) const {
        // Circle-vs-AABB minimum translation, repeated because corners can share obstacles.
        for(int iteration=0;iteration<12;++iteration) {
            const int i=at(p.x,p.z);if(i<0) return repair(p);
            bool changed=false;
            for(int k:obstacles[static_cast<std::size_t>(i)]) {
                const auto& b=walls[static_cast<std::size_t>(k)];if(!overlap(p.x,p.z,b)) continue;
                double dx=p.x-std::clamp(p.x,b.x,b.X),dz=p.z-std::clamp(p.z,b.z,b.Z);
                const double d=std::hypot(dx,dz);
                if(d>eps) { p.x+=dx/d*(radius+eps-d);p.z+=dz/d*(radius+eps-d); }
                else {
                    const std::array<double,4> distances{p.x-b.x,b.X-p.x,p.z-b.z,b.Z-p.z};
                    const auto side=std::min_element(distances.begin(),distances.end())-distances.begin();
                    if(side==0) p.x=b.x-radius-eps;else if(side==1) p.x=b.X+radius+eps;
                    else if(side==2) p.z=b.z-radius-eps;else p.z=b.Z+radius+eps;
                }
                changed=true;
            }
            if(!changed) return p;
        }
        return repair(p);
    }
    bool segment(Point a,Point b) const {
        const int n=std::max(1,static_cast<int>(std::ceil(std::hypot(a.x-b.x,a.z-b.z)/(.25*radius))));
        for(int j=1;j<=n;++j) { const double f=static_cast<double>(j)/n;
            if(!clear(a.x+(b.x-a.x)*f,a.z+(b.z-a.z)*f)) return false; }
        return true;
    }
    std::vector<Point> path(Point start,Point goal,std::uint64_t seed) const {
        const int first=at(start.x,start.z),last=at(goal.x,goal.z);if(first<0 || last<0) return {};
        if(segment(start,goal)) return {goal};
        struct Node { double f;int i;bool operator<(const Node& b) const { return f==b.f ? i>b.i : f>b.f; } };
        std::priority_queue<Node> open;std::vector<double> cost(blocked.size(),std::numeric_limits<double>::infinity());
        std::vector<int> parent(blocked.size(),-1);cost[static_cast<std::size_t>(first)]=0;open.push({0,first});
        constexpr std::array<int,8> dx{-1,0,1,-1,1,-1,0,1},dz{-1,-1,-1,0,0,1,1,1};
        while(!open.empty()) {
            const int i=open.top().i;open.pop();if(i==last) break;
            for(int j=0;j<8;++j) {
                const int nx=i%width+dx[j],nz=i/width+dz[j];if(nx<0 || nz<0 || nx>=width || nz>=depth) continue;
                const int next=nz*width+nx;if(blocked[static_cast<std::size_t>(next)]) continue;
                if(dx[j]!=0 && dz[j]!=0 && (blocked[static_cast<std::size_t>(i+dx[j])] || blocked[static_cast<std::size_t>(i+dz[j]*width)])) continue;
                const double noise=static_cast<double>(mix(seed^static_cast<std::uint64_t>(next))&255U)/255.*.015;
                const double value=cost[static_cast<std::size_t>(i)]+(dx[j] && dz[j]?1.4142135623730951:1)+noise;
                if(value>=cost[static_cast<std::size_t>(next)]) continue;
                cost[static_cast<std::size_t>(next)]=value;parent[static_cast<std::size_t>(next)]=i;
                const auto p=point(next);open.push({value+std::hypot(p.x-goal.x,p.z-goal.z)/cell,next});
            }
        }
        if(first!=last && parent[static_cast<std::size_t>(last)]<0) return {};
        std::vector<Point> result{goal};int i=last;
        while(i!=first) { result.push_back(point(i));i=parent[static_cast<std::size_t>(i)];if(i<0) return {}; }
        std::reverse(result.begin(),result.end());return result;
    }
};
Scene::Scene(const std::vector<RoomSpec>& rooms,std::uint64_t seed) {
    auto d=std::make_shared<Data>();d->rooms=rooms;
    Mesh mesh;if(!city_mesh(rooms.data(),rooms.size()*sizeof(RoomSpec),rooms.size(),seed,mesh)) throw std::invalid_argument("Invalid scene");
    double X=17,Z=16;
    for(const auto& v:mesh.vertices) { d->x=std::min(d->x,static_cast<double>(v.x)-1);d->z=std::min(d->z,static_cast<double>(v.z)-1);X=std::max(X,static_cast<double>(v.x)+1);Z=std::max(Z,static_cast<double>(v.z)+1); }
    const double spanX=std::max(baseCell,X-d->x),spanZ=std::max(baseCell,Z-d->z);
    // The occupancy index has two vector objects and one byte per tile. Keep its
    // baseline allocation within a memory budget and trade grid density for extent.
    constexpr double indexBudgetBytes=32.0*1024.0*1024.0;
    const double bytesPerTile=static_cast<double>(2*sizeof(std::vector<int>)+sizeof(unsigned char));
    const double tileBudget=indexBudgetBytes/bytesPerTile;
    d->cell=std::max(baseCell,std::sqrt(spanX*spanZ/tileBudget));
    d->width=static_cast<int>(std::ceil((X-d->x)/d->cell));d->depth=static_cast<int>(std::ceil((Z-d->z)/d->cell));
    const auto count=static_cast<std::size_t>(d->width)*static_cast<std::size_t>(d->depth);
    d->obstacles.resize(count);d->supports.resize(count);d->blocked.resize(count);
    for(std::size_t i=0;i<mesh.indices.size();i+=3) {
        const auto& a=mesh.vertices[mesh.indices[i]];const auto& b=mesh.vertices[mesh.indices[i+1]];const auto& c=mesh.vertices[mesh.indices[i+2]];
        const double lo=std::min({a.y,b.y,c.y}),hi=std::max({a.y,b.y,c.y});
        const Box bounds{std::min({a.x,b.x,c.x}),std::min({a.z,b.z,c.z}),std::max({a.x,b.x,c.x}),std::max({a.z,b.z,c.z})};
        const double nx=(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y),ny=(b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z),nz=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
        if(hi>.20 && lo<1.32 && std::abs(ny)<.75*std::sqrt(nx*nx+ny*ny+nz*nz)) {
            const int id=static_cast<int>(d->walls.size());d->walls.push_back(bounds);
            d->cells({bounds.x-radius,bounds.z-radius,bounds.X+radius,bounds.Z+radius},[&](int tile) { d->obstacles[static_cast<std::size_t>(tile)].push_back(id); });
        }
        // All upward terrain triangles support weight; interior tops above step height are furniture.
        if((hi<=.26 || ((a.rgba>>24)==1 && std::abs(ny)>.8*std::sqrt(nx*nx+ny*ny+nz*nz))) && std::abs(ny)>1e-8) {
            const int id=static_cast<int>(d->floors.size());d->floors.push_back({{a.x,a.y,a.z},{b.x,b.y,b.z},{c.x,c.y,c.z}});
            d->cells(bounds,[&](int tile) { d->supports[static_cast<std::size_t>(tile)].push_back(id); });
        }
    }
    for(std::size_t i=0;i<count;++i) { const auto p=d->point(static_cast<int>(i));d->blocked[i]=!d->clear(p.x,p.z); }
    data=std::move(d);
}
double Scene::surface(double x,double z) const {
    const auto& d=*data;const int tile=d.at(x,z);double y=0;
    if(tile<0) return y;
    for(int k:d.supports[static_cast<std::size_t>(tile)]) {
        const auto& f=d.floors[static_cast<std::size_t>(k)];
        const double det=(f.b.z-f.c.z)*(f.a.x-f.c.x)+(f.c.x-f.b.x)*(f.a.z-f.c.z);
        if(std::abs(det)<1e-12) continue;
        const double a=((f.b.z-f.c.z)*(x-f.c.x)+(f.c.x-f.b.x)*(z-f.c.z))/det;
        const double b=((f.c.z-f.a.z)*(x-f.c.x)+(f.a.x-f.c.x)*(z-f.c.z))/det;
        if(a>=-eps && b>=-eps && a+b<=1+eps) y=std::max(y,a*f.a.y+b*f.b.y+(1-a-b)*f.c.y);
    }
    return y;
}
bool Scene::clear(double x,double z) const { return data->clear(x,z); }
std::vector<Motion> Scene::step(const std::vector<Body>& bodies,std::uint64_t seed,std::uint64_t tick) const {
    const auto& d=*data;std::vector<Motion> out;std::vector<std::vector<Point>> paths;
    for(std::size_t i=0;i<bodies.size();++i) {
        if(bodies[i].destination>=d.rooms.size()) throw std::invalid_argument("Invalid destination");
        Motion m;m.body=bodies[i];m.body.p=d.repair(m.body.p);m.corrected=m.body.p.x!=bodies[i].p.x || m.body.p.z!=bodies[i].p.z;
        bool overlapping=false;
        for(const auto& prior:out) if(std::hypot(m.body.p.x-prior.body.p.x,m.body.p.z-prior.body.p.z)<radius*2) overlapping=true;
        if(overlapping) {
            double nearest=std::numeric_limits<double>::infinity();Point safe=m.body.p;
            for(std::size_t tile=0;tile<d.blocked.size();++tile) if(!d.blocked[tile]) {
                const auto p=d.point(static_cast<int>(tile));bool free=true;
                for(const auto& prior:out) if(std::hypot(p.x-prior.body.p.x,p.z-prior.body.p.z)<radius*2+eps) free=false;
                const double dist=std::hypot(p.x-m.body.p.x,p.z-m.body.p.z);
                if(free && dist<nearest) { nearest=dist;safe=p; }
            }
            if(!std::isfinite(nearest)) throw std::runtime_error("No non-overlapping spawn position");
            m.body.p.x=safe.x;m.body.p.z=safe.z;m.corrected=true;
        }
        // Find a walkable interaction point inside the actual room, reserving space for earlier agents.
        const auto& room=d.rooms[m.body.destination];Point goal=m.body.p;double best=std::numeric_limits<double>::infinity();
        d.cells({room.x-room.width/2,room.z-room.depth/2,room.x+room.width/2,room.z+room.depth/2},[&](int tile) {
            if(d.blocked[static_cast<std::size_t>(tile)]) return;
            const auto p=d.point(tile);if(!inside(p,room)) return;
            double crowd=0;for(const auto& prior:paths) if(!prior.empty()) { const auto g=prior.back();if(std::hypot(g.x-p.x,g.z-p.z)<radius*2.1) crowd+=10; }
            const double score=std::hypot(p.x-room.x,p.z-room.z)+crowd;
            if(score<best) { best=score;goal=p; }
        });
        m.body.p.y=std::max(m.body.p.y,surface(m.body.p.x,m.body.p.z));m.route.push_back(m.body.p);
        paths.push_back(d.path(m.body.p,goal,mix(seed^mix(tick/120)^mix(i))));out.push_back(std::move(m));
    }
    std::vector<std::size_t> cursor(bodies.size(),0);
    for(int frame=0;frame<substeps;++frame) {
        for(std::size_t offset=0;offset<out.size();++offset) {
            const std::size_t i=(offset+static_cast<std::size_t>(tick%out.size()))%out.size();auto& m=out[i];auto& p=m.body.p;
            while(cursor[i]<paths[i].size() && std::hypot(paths[i][cursor[i]].x-p.x,paths[i][cursor[i]].z-p.z)<.025) ++cursor[i];
            const auto before=p;auto next=p;
            if(cursor[i]<paths[i].size()) {
                const auto goal=paths[i][cursor[i]];double dx=goal.x-p.x,dz=goal.z-p.z;
                const double distance=std::hypot(dx,dz);if(distance>eps) { const double step=std::min(speed*fixed_dt,distance);next.x+=dx/distance*step;next.z+=dz/distance*step; }
                // Deterministic reciprocal separation steering. Every candidate is swept against solids below.
                for(std::size_t j=0;j<out.size();++j) if(j!=i) {
                    dx=p.x-out[j].body.p.x;dz=p.z-out[j].body.p.z;const double dist=std::hypot(dx,dz);
                    if(dist>eps && dist<radius*2.5) { const double strength=(radius*2.5-dist)*.08;next.x+=dx/dist*strength;next.z+=dz/dist*strength; }
                }
                const double delta=std::hypot(next.x-p.x,next.z-p.z);
                if(delta>speed*fixed_dt) { next.x=p.x+(next.x-p.x)*speed*fixed_dt/delta;next.z=p.z+(next.z-p.z)*speed*fixed_dt/delta; }
                if(!d.segment(p,next)) { next=p; } // Never tunnel through a thin wall.
                next=d.push(next);
                bool occupied=false;
                for(std::size_t j=0;j<out.size();++j) if(j!=i && std::hypot(next.x-out[j].body.p.x,next.z-out[j].body.p.z)<radius*2-eps) { occupied=true;break; }
                if(!occupied) { p.x=next.x;p.z=next.z; }
            }
            m.ground=surface(p.x,p.z);m.body.vy-=9.81*fixed_dt;p.y+=m.body.vy*fixed_dt;
            if(p.y<=m.ground) { p.y=m.ground;m.body.vy=0; }
            const double traveled=std::hypot(p.x-before.x,p.z-before.z);m.distance+=traveled;
            if(traveled>eps) m.heading=std::atan2(p.x-before.x,p.z-before.z);
            m.route.push_back(p);
        }
    }
    for(std::size_t i=0;i<out.size();++i) {
        auto& m=out[i];m.reached=!paths[i].empty() && inside(m.body.p,d.rooms[m.body.destination]) &&
            std::hypot(m.body.p.x-paths[i].back().x,m.body.p.z-paths[i].back().z)<.10 && m.body.vy==0;
        if(!clear(m.body.p.x,m.body.p.z)) throw std::runtime_error("Unresolved solid collision");
    }
    return out;
}
}
