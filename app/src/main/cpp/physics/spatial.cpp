#include "spatial.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <queue>
#include <stdexcept>

namespace girassol::physics {

Point resolveCircleAABB(Point center,double bodyRadius,const Aabb& box) noexcept {
    if(!std::isfinite(center.x)||!std::isfinite(center.z)||!std::isfinite(bodyRadius)||
       !std::isfinite(box.minX)||!std::isfinite(box.minZ)||!std::isfinite(box.maxX)||
       !std::isfinite(box.maxZ)||bodyRadius<=0.0||box.minX>box.maxX||box.minZ>box.maxZ) return center;
    const double cx=std::clamp(center.x,box.minX,box.maxX),cz=std::clamp(center.z,box.minZ,box.maxZ);
    const double dx=center.x-cx,dz=center.z-cz,distance=std::hypot(dx,dz);
    if(distance>=bodyRadius-1e-6) return center;
    if(distance>1e-6) {
        center.x+=dx/distance*(bodyRadius+1e-6-distance);
        center.z+=dz/distance*(bodyRadius+1e-6-distance);
    } else {
        const std::array<double,4> side{center.x-box.minX,box.maxX-center.x,center.z-box.minZ,box.maxZ-center.z};
        const auto nearest=std::min_element(side.begin(),side.end())-side.begin();
        if(nearest==0) center.x=box.minX-bodyRadius-1e-6;
        else if(nearest==1) center.x=box.maxX+bodyRadius+1e-6;
        else if(nearest==2) center.z=box.minZ-bodyRadius-1e-6;
        else center.z=box.maxZ+bodyRadius+1e-6;
    }
    return center;
}

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
struct V2 { double x=0,z=0; };
double dot(V2 a,V2 b) noexcept { return a.x*b.x+a.z*b.z; }
double det(V2 a,V2 b) noexcept { return a.x*b.z-a.z*b.x; }
double sq(V2 a) noexcept { return dot(a,a); }
V2 add(V2 a,V2 b) noexcept { return {a.x+b.x,a.z+b.z}; }
V2 sub(V2 a,V2 b) noexcept { return {a.x-b.x,a.z-b.z}; }
V2 mul(V2 a,double k) noexcept { return {a.x*k,a.z*k}; }
V2 normalize(V2 a) noexcept { const double n=std::hypot(a.x,a.z);return n>eps?mul(a,1.0/n):V2{}; }
struct OrcaLine { V2 point; V2 direction; };

bool linearProgram1(const std::vector<OrcaLine>& lines,std::size_t index,double maxSpeed,
                    V2 optimum,bool directionOpt,V2& result) noexcept {
    const auto& line=lines[index];
    const double projection=dot(line.point,line.direction);
    const double discriminant=projection*projection+maxSpeed*maxSpeed-sq(line.point);
    if(discriminant < -eps) return false;
    const double root=std::sqrt(std::max(0.0,discriminant));
    double left=-projection-root,right=-projection+root;
    for(std::size_t j=0;j<index;++j) {
        const double denominator=det(line.direction,lines[j].direction);
        const double numerator=det(lines[j].direction,sub(line.point,lines[j].point));
        if(std::abs(denominator)<=eps) { if(numerator<0.0) return false; else continue; }
        const double t=numerator/denominator;
        if(denominator>=0.0) right=std::min(right,t); else left=std::max(left,t);
        if(left>right) return false;
    }
    const double t=directionOpt
        ? (dot(optimum,line.direction)>0.0?right:left)
        : std::clamp(dot(line.direction,sub(optimum,line.point)),left,right);
    result=add(line.point,mul(line.direction,t));
    return true;
}
std::size_t linearProgram2(const std::vector<OrcaLine>& lines,double maxSpeed,V2 optimum,
                           bool directionOpt,V2& result) noexcept {
    result=directionOpt?mul(normalize(optimum),maxSpeed)
        :(sq(optimum)>maxSpeed*maxSpeed?mul(normalize(optimum),maxSpeed):optimum);
    for(std::size_t i=0;i<lines.size();++i) if(det(lines[i].direction,sub(lines[i].point,result))>eps) {
        const V2 previous=result;
        if(!linearProgram1(lines,i,maxSpeed,optimum,directionOpt,result)) { result=previous;return i; }
    }
    return lines.size();
}
void linearProgram3(const std::vector<OrcaLine>& lines,std::size_t begin,double maxSpeed,V2& result) {
    double distance=0.0;
    for(std::size_t i=begin;i<lines.size();++i) {
        const double violation=det(lines[i].direction,sub(lines[i].point,result));
        if(violation<=distance) continue;
        std::vector<OrcaLine> projected;projected.reserve(i);
        for(std::size_t j=0;j<i;++j) {
            OrcaLine line;
            const double denominator=det(lines[i].direction,lines[j].direction);
            if(std::abs(denominator)<=eps) {
                if(dot(lines[i].direction,lines[j].direction)>0.0) continue;
                line.point=mul(add(lines[i].point,lines[j].point),.5);
            } else line.point=add(lines[i].point,mul(lines[i].direction,
                det(lines[j].direction,sub(lines[i].point,lines[j].point))/denominator));
            line.direction=normalize(sub(lines[j].direction,lines[i].direction));
            projected.push_back(line);
        }
        const V2 previous=result;
        const V2 optimum{-lines[i].direction.z,lines[i].direction.x};
        if(linearProgram2(projected,maxSpeed,optimum,true,result)<projected.size()) result=previous;
        distance=det(lines[i].direction,sub(lines[i].point,result));
    }
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
        // Repeated circle-vs-AABB projection resolves corners shared by several boxes.
        for(int iteration=0;iteration<12;++iteration) {
            const int i=at(p.x,p.z);if(i<0) return repair(p);
            bool changed=false;
            for(int k:obstacles[static_cast<std::size_t>(i)]) {
                const auto& b=walls[static_cast<std::size_t>(k)];
                if(!overlap(p.x,p.z,b)) continue;
                const Point resolved=resolveCircleAABB(p,radius,{b.x,b.z,b.X,b.Z});
                if(std::hypot(resolved.x-p.x,resolved.z-p.z)>eps) {
                    p.x=resolved.x;p.z=resolved.z;changed=true;
                }
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
std::vector<Velocity> stepRvo2OrcaVelocities(const std::vector<OrcaAgent>& agents,double dt,double timeHorizon) {
    if(!std::isfinite(dt)||!std::isfinite(timeHorizon)||dt<=0.0||timeHorizon<=0.0)
        throw std::invalid_argument("Invalid ORCA time parameters");
    if(agents.size()>4096) throw std::invalid_argument("ORCA agent count exceeds limit");
    std::vector<Velocity> output(agents.size());
    for(std::size_t i=0;i<agents.size();++i) {
        const auto& self=agents[i];
        if(!std::isfinite(self.position.x)||!std::isfinite(self.position.z)||
           !std::isfinite(self.velocity.x)||!std::isfinite(self.velocity.z)||
           !std::isfinite(self.preferredVelocity.x)||!std::isfinite(self.preferredVelocity.z)||
           !std::isfinite(self.radius)||self.radius<=0.0)
            throw std::invalid_argument("Non-finite ORCA agent");
        const V2 velocity{self.velocity.x,self.velocity.z};
        const V2 preferred{self.preferredVelocity.x,self.preferredVelocity.z};
        std::vector<OrcaLine> lines;lines.reserve(agents.size()?agents.size()-1:0);
        for(std::size_t j=0;j<agents.size();++j) if(j!=i) {
            const auto& other=agents[j];
            const V2 relativePosition{other.position.x-self.position.x,other.position.z-self.position.z};
            const V2 relativeVelocity{velocity.x-other.velocity.x,velocity.z-other.velocity.z};
            const double distanceSq=sq(relativePosition),combinedRadius=self.radius+other.radius;
            const double combinedRadiusSq=combinedRadius*combinedRadius;
            V2 direction{},u{};
            if(distanceSq>combinedRadiusSq) {
                const V2 w=sub(relativeVelocity,mul(relativePosition,1.0/timeHorizon));
                const double wSq=sq(w),projection=dot(w,relativePosition);
                if(projection<0.0&&projection*projection>combinedRadiusSq*wSq) {
                    const double length=std::sqrt(std::max(wSq,eps));
                    const V2 unitW=mul(w,1.0/length);
                    direction={unitW.z,-unitW.x};
                    u=mul(unitW,combinedRadius/timeHorizon-length);
                } else {
                    const double leg=std::sqrt(std::max(0.0,distanceSq-combinedRadiusSq));
                    if(det(relativePosition,w)>0.0)
                        direction={(relativePosition.x*leg-relativePosition.z*combinedRadius)/distanceSq,
                                   (relativePosition.x*combinedRadius+relativePosition.z*leg)/distanceSq};
                    else
                        direction={-(relativePosition.x*leg+relativePosition.z*combinedRadius)/distanceSq,
                                   -(-relativePosition.x*combinedRadius+relativePosition.z*leg)/distanceSq};
                    u=sub(mul(direction,dot(relativeVelocity,direction)),relativeVelocity);
                }
            } else {
                const V2 w=sub(relativeVelocity,mul(relativePosition,1.0/dt));
                const double length=std::sqrt(std::max(sq(w),eps));
                const V2 unitW=mul(w,1.0/length);
                direction={unitW.z,-unitW.x};
                u=mul(unitW,combinedRadius/dt-length);
            }
            lines.push_back({add(velocity,mul(u,.5)),direction});
        }
        V2 result{};
        const std::size_t failed=linearProgram2(lines,speed,preferred,false,result);
        if(failed<lines.size()) linearProgram3(lines,failed,speed,result);
        const double magnitude=std::hypot(result.x,result.z);
        if(magnitude>speed) result=mul(result,speed/magnitude);
        output[i]={result.x,result.z};
    }
    return output;
}

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
    std::vector<Point> previous(out.size());
    for(std::size_t i=0;i<out.size();++i) previous[i]=out[i].body.p;
    for(int frame=0;frame<substeps;++frame) {
        std::vector<OrcaAgent> agents;agents.reserve(out.size());
        for(std::size_t i=0;i<out.size();++i) {
            const auto& p=out[i].body.p;
            while(cursor[i]<paths[i].size()&&std::hypot(paths[i][cursor[i]].x-p.x,paths[i][cursor[i]].z-p.z)<.025) ++cursor[i];
            Velocity preferred{};
            if(cursor[i]<paths[i].size()) {
                const auto& goal=paths[i][cursor[i]];
                const double dx=goal.x-p.x,dz=goal.z-p.z,distance=std::hypot(dx,dz);
                if(distance>eps) preferred={dx/distance*speed,dz/distance*speed};
            }
            const Velocity current{(p.x-previous[i].x)/fixed_dt,(p.z-previous[i].z)/fixed_dt};
            agents.push_back({p,current,preferred,radius});
        }
        // Every reciprocal constraint is computed from one immutable substep snapshot.
        const auto velocities=stepRvo2OrcaVelocities(agents,fixed_dt,2.0);
        for(std::size_t offset=0;offset<out.size();++offset) {
            const std::size_t i=(offset+static_cast<std::size_t>(tick%out.size()))%out.size();
            auto& m=out[i];auto& p=m.body.p;const auto before=p;auto next=p;previous[i]=before;
            next.x+=velocities[i].x*fixed_dt;next.z+=velocities[i].z*fixed_dt;
            if(!d.segment(p,next)) next=p;
            next=d.push(next);
            bool occupied=false;
            for(std::size_t j=0;j<out.size();++j) if(j!=i&&
                std::hypot(next.x-out[j].body.p.x,next.z-out[j].body.p.z)<radius*2-eps) { occupied=true;break; }
            if(!occupied) { p.x=next.x;p.z=next.z; }
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
