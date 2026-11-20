#include "recast_rvo2_nav.hpp"
#include <Recast.h>
#include <RecastAlloc.h>
#include <DetourAlloc.h>
#include <DetourNavMesh.h>
#include <DetourNavMeshBuilder.h>
#include <DetourNavMeshQuery.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <limits>
#include <mutex>
#include <new>
#include <stdexcept>

namespace girassol::nav {
namespace {
constexpr std::size_t tile_limit=2*1024*1024;
struct Arena {
    std::unique_ptr<std::byte[]> data=std::make_unique<std::byte[]>(DistrictNavMesh::arena_bytes);
    std::size_t used=0;
    std::int64_t fail=-1;
    void* allocate(std::size_t n) noexcept {
        constexpr auto alignment=alignof(std::max_align_t);
        const auto offset=(used+alignment-1)&~(alignment-1);
        if(fail==0 || n>DistrictNavMesh::arena_bytes-offset) return nullptr;
        if(fail>0) --fail;
        used=offset+n;return data.get()+offset;
    }
};
thread_local std::unique_ptr<Arena> pool;
thread_local std::int64_t fail_after=-1;
thread_local Arena* active=nullptr;
void* recast_alloc(std::size_t n,rcAllocHint) { void* p=active?active->allocate(n):nullptr;if(!p) throw std::bad_alloc();return p; }
void* detour_alloc(std::size_t n,dtAllocHint) { void* p=active?active->allocate(n):nullptr;if(!p) throw std::bad_alloc();return p; }
// Bump arena: individual releases retain storage; Scope bounds lifetime and the next operation reclaims all bytes.
void arena_free(void* pointer) { (void)pointer; }
struct Scope {
    Scope() {
        static std::once_flag installed;
        std::call_once(installed,[]{ rcAllocSetCustom(recast_alloc,arena_free);dtAllocSetCustom(detour_alloc,arena_free); });
        if(active) throw std::logic_error("Nested navigation arena");
        if(!pool) pool=std::make_unique<Arena>();
        pool->used=0;pool->fail=fail_after;active=pool.get();
    }
    ~Scope() { active=nullptr; }
};
template<class T,void(*Free)(T*)> using Owned=std::unique_ptr<T,decltype(Free)>;
void needed(bool value) { if(!value) throw std::bad_alloc(); }
void detour_ok(dtStatus status) { if(dtStatusFailed(status)) { if(status&DT_OUT_OF_MEMORY) throw std::bad_alloc();throw std::runtime_error("Detour navigation failure"); } }
}
void DistrictNavMesh::allocation_failure_after(std::int64_t n) { fail_after=n; }
DistrictNavMesh::DistrictNavMesh(const Mesh& mesh) {
    if(mesh.vertices.empty() || mesh.vertices.size()>400000 || mesh.indices.empty() || mesh.indices.size()>2400000 || mesh.indices.size()%3) throw std::invalid_argument("Navigation geometry budget");
    Scope scope;rcContext ctx(false);
    const int nv=static_cast<int>(mesh.vertices.size()),nt=static_cast<int>(mesh.indices.size()/3);
    auto* vertices=static_cast<float*>(active->allocate(mesh.vertices.size()*3*sizeof(float)));
    auto* triangles=static_cast<int*>(active->allocate(mesh.indices.size()*sizeof(int)));
    auto* areas=static_cast<unsigned char*>(active->allocate(static_cast<std::size_t>(nt)));
    needed(vertices && triangles && areas);
    for(int i=0;i<nv;++i) { const auto& v=mesh.vertices[static_cast<std::size_t>(i)];vertices[i*3]=v.x;vertices[i*3+1]=v.y;vertices[i*3+2]=v.z; }
    for(std::size_t i=0;i<mesh.indices.size();++i) { if(mesh.indices[i]>=mesh.vertices.size()) throw std::invalid_argument("Navigation index");triangles[i]=static_cast<int>(mesh.indices[i]); }
    std::memset(areas,0,static_cast<std::size_t>(nt));
    rcConfig cfg{};cfg.cs=.05f;cfg.ch=.05f;cfg.walkableSlopeAngle=45;cfg.walkableHeight=26;cfg.walkableClimb=5;cfg.walkableRadius=2;
    cfg.maxEdgeLen=120;cfg.maxSimplificationError=.8f;cfg.minRegionArea=4;cfg.mergeRegionArea=20;cfg.maxVertsPerPoly=6;
    rcCalcBounds(vertices,nv,cfg.bmin,cfg.bmax);cfg.bmin[1]-=.1f;cfg.bmax[1]+=1.4f;
    const float spanX=cfg.bmax[0]-cfg.bmin[0],spanZ=cfg.bmax[2]-cfg.bmin[2];
    if(!std::isfinite(spanX) || !std::isfinite(spanZ) || spanX<=0 || spanZ<=0 || spanX>256 || spanZ>256) throw std::invalid_argument("Navigation extent");
    // Keep the clearance radius physical even when a large world requires coarser voxels.
    cfg.cs=std::max(cfg.cs,std::sqrt(spanX*spanZ/262144.f));cfg.walkableRadius=static_cast<int>(std::ceil(.18f/cfg.cs));
    rcCalcGridSize(cfg.bmin,cfg.bmax,cfg.cs,&cfg.width,&cfg.height);
    if(cfg.width<=0 || cfg.height<=0 || static_cast<std::size_t>(cfg.width)*cfg.height>270000) throw std::invalid_argument("Navigation voxel budget");
    Owned<rcHeightfield,rcFreeHeightField> hf(rcAllocHeightfield(),rcFreeHeightField);needed(bool(hf));
    needed(rcCreateHeightfield(&ctx,*hf,cfg.width,cfg.height,cfg.bmin,cfg.bmax,cfg.cs,cfg.ch));
    rcMarkWalkableTriangles(&ctx,cfg.walkableSlopeAngle,vertices,nv,triangles,nt,areas);
    // Match the physical support contract: furniture and roofs are obstacles, never
    // traversable tops. Merely checking slope would route capsules onto low beds.
    for(int i=0;i<nt;++i) {
        const auto& a=mesh.vertices[mesh.indices[static_cast<std::size_t>(i)*3]];
        const auto& b=mesh.vertices[mesh.indices[static_cast<std::size_t>(i)*3+1]];
        const auto& c=mesh.vertices[mesh.indices[static_cast<std::size_t>(i)*3+2]];
        if(std::max({a.y,b.y,c.y})>.26f && (a.rgba>>24)!=1) areas[i]=RC_NULL_AREA;
    }
    needed(rcRasterizeTriangles(&ctx,vertices,nv,triangles,areas,nt,*hf,cfg.walkableClimb));
    rcFilterLowHangingWalkableObstacles(&ctx,cfg.walkableClimb,*hf);rcFilterLedgeSpans(&ctx,cfg.walkableHeight,cfg.walkableClimb,*hf);rcFilterWalkableLowHeightSpans(&ctx,cfg.walkableHeight,*hf);
    Owned<rcCompactHeightfield,rcFreeCompactHeightfield> compact(rcAllocCompactHeightfield(),rcFreeCompactHeightfield);needed(bool(compact));
    needed(rcBuildCompactHeightfield(&ctx,cfg.walkableHeight,cfg.walkableClimb,*hf,*compact));
    needed(rcErodeWalkableArea(&ctx,cfg.walkableRadius,*compact));
    needed(rcBuildRegionsMonotone(&ctx,*compact,0,cfg.minRegionArea,cfg.mergeRegionArea));
    Owned<rcContourSet,rcFreeContourSet> contours(rcAllocContourSet(),rcFreeContourSet);needed(bool(contours));
    needed(rcBuildContours(&ctx,*compact,cfg.maxSimplificationError,cfg.maxEdgeLen,*contours));
    Owned<rcPolyMesh,rcFreePolyMesh> polygons(rcAllocPolyMesh(),rcFreePolyMesh);needed(bool(polygons));
    needed(rcBuildPolyMesh(&ctx,*contours,cfg.maxVertsPerPoly,*polygons));
    if(polygons->npolys<=0 || polygons->npolys>8192) throw std::runtime_error("Navigation polygon budget");
    for(int i=0;i<polygons->npolys;++i) { polygons->flags[i]=1;polygons->areas[i]=0; }
    dtNavMeshCreateParams params{};params.verts=polygons->verts;params.vertCount=polygons->nverts;params.polys=polygons->polys;
    params.polyAreas=polygons->areas;params.polyFlags=polygons->flags;params.polyCount=polygons->npolys;params.nvp=polygons->nvp;
    params.walkableHeight=1.3f;params.walkableRadius=.18f;params.walkableClimb=.25f;params.cs=cfg.cs;params.ch=cfg.ch;
    rcVcopy(params.bmin,polygons->bmin);rcVcopy(params.bmax,polygons->bmax);params.buildBvTree=true;
    unsigned char* tile=nullptr;int bytes=0;needed(dtCreateNavMeshData(&params,&tile,&bytes));
    if(bytes<=0 || static_cast<std::size_t>(bytes)>tile_limit) throw std::length_error("Navigation tile budget");
    tile_.assign(tile,tile+bytes);
}
std::vector<Vec> DistrictNavMesh::path(Vec start,Vec goal,bool closestReachable) const {
    if(!std::isfinite(start.x) || !std::isfinite(start.z) || !std::isfinite(goal.x) || !std::isfinite(goal.z)) throw std::invalid_argument("Navigation coordinates");
    Scope scope;
    auto* data=static_cast<unsigned char*>(active->allocate(tile_.size()));needed(data);std::memcpy(data,tile_.data(),tile_.size());
    Owned<dtNavMesh,dtFreeNavMesh> nav(dtAllocNavMesh(),dtFreeNavMesh);needed(bool(nav));detour_ok(nav->init(data,static_cast<int>(tile_.size()),0));
    Owned<dtNavMeshQuery,dtFreeNavMeshQuery> query(dtAllocNavMeshQuery(),dtFreeNavMeshQuery);needed(bool(query));detour_ok(query->init(nav.get(),8192));
    const float from[3]{static_cast<float>(start.x),0,static_cast<float>(start.z)},to[3]{static_cast<float>(goal.x),0,static_cast<float>(goal.z)},extents[3]{.6f,2.f,.6f};
    dtPolyRef a=0,b=0;float p[3]{},q[3]{};dtQueryFilter filter;
    detour_ok(query->findNearestPoly(from,extents,&filter,&a,p));detour_ok(query->findNearestPoly(to,extents,&filter,&b,q));
    if(!a) return {};
    if(closestReachable) {
        // Interaction points can lie in an enclosed furniture island. Select the
        // nearest polygon in the start component rather than an unreachable island.
        std::array<dtPolyRef,8192> reachable{};int found=0;
        const auto search=query->findPolysAroundCircle(a,p,512,&filter,reachable.data(),nullptr,nullptr,&found,8192);
        detour_ok(search);if(search&(DT_BUFFER_TOO_SMALL|DT_OUT_OF_NODES)) return {};
        double best=std::numeric_limits<double>::infinity();b=0;
        for(int i=0;i<found;++i) {
            float candidate[3]{};detour_ok(query->closestPointOnPoly(reachable[static_cast<std::size_t>(i)],to,candidate,nullptr));
            const double dx=candidate[0]-to[0],dz=candidate[2]-to[2];const double score=dx*dx+dz*dz;
            if(score<best) { best=score;b=reachable[static_cast<std::size_t>(i)];std::copy(candidate,candidate+3,q); }
        }
    }
    if(!b) return {};
    std::array<dtPolyRef,512> corridor{};int count=0;const auto status=query->findPath(a,b,p,q,&filter,corridor.data(),&count,static_cast<int>(corridor.size()));
    detour_ok(status);if(!count || corridor[static_cast<std::size_t>(count-1)]!=b || (status&(DT_BUFFER_TOO_SMALL|DT_OUT_OF_NODES|DT_PARTIAL_RESULT))) return {};
    std::array<float,512*3> points{};int n=0;
    const auto straight=query->findStraightPath(p,q,corridor.data(),count,points.data(),nullptr,nullptr,&n,512);
    detour_ok(straight);if(straight&DT_BUFFER_TOO_SMALL) return {};
    std::vector<Vec> result;result.reserve(static_cast<std::size_t>(n));
    for(int i=1;i<n;++i) result.push_back({points[static_cast<std::size_t>(i)*3],points[static_cast<std::size_t>(i)*3+2]});
    if(result.empty()) result.push_back({q[0],q[2]});
    return result;
}
}
