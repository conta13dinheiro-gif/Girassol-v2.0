#include "spatial.hpp"
#include <jni.h>
#include <cstring>
#include <cmath>
#include <limits>
#include <optional>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_physics_NativeSpatial_nativeStep(JNIEnv* env,jobject,jobject roomBuffer,jint count,
        jobject bodyBuffer,jint people,jlong seed,jlong tick,jobject resultBuffer) {
    if(!roomBuffer || !bodyBuffer || !resultBuffer || count<=0 || people<=0) return JNI_FALSE;
    const jlong roomBytes=env->GetDirectBufferCapacity(roomBuffer),bodyBytes=env->GetDirectBufferCapacity(bodyBuffer),resultBytes=env->GetDirectBufferCapacity(resultBuffer);
    constexpr jlong inputStride=5*8,outputStride=(8+(girassol::physics::substeps+1)*3)*8;
    if(roomBytes<static_cast<jlong>(count)*24 || bodyBytes<static_cast<jlong>(people)*inputStride || resultBytes<static_cast<jlong>(people)*outputStride) return JNI_FALSE;
    const auto* input=static_cast<const unsigned char*>(env->GetDirectBufferAddress(bodyBuffer));
    auto* dest=static_cast<unsigned char*>(env->GetDirectBufferAddress(resultBuffer));
    const auto* raw=env->GetDirectBufferAddress(roomBuffer);
    if(!input || !dest || !raw) return JNI_FALSE;
    try {
        std::vector<girassol::RoomSpec> rooms(static_cast<std::size_t>(count));std::memcpy(rooms.data(),raw,rooms.size()*24);
        struct Cache { std::vector<girassol::RoomSpec> rooms;std::uint64_t seed=0;std::optional<girassol::physics::Scene> scene; };
        thread_local Cache cache;
        if(!cache.scene || cache.seed!=static_cast<std::uint64_t>(seed) || cache.rooms.size()!=rooms.size() || std::memcmp(cache.rooms.data(),rooms.data(),rooms.size()*24)!=0) {
            auto next=girassol::physics::Scene(rooms,static_cast<std::uint64_t>(seed));cache.rooms=rooms;cache.seed=static_cast<std::uint64_t>(seed);cache.scene=std::move(next);
        }
        std::vector<girassol::physics::Body> bodies;
        for(int i=0;i<people;++i) {
            double v[5];std::memcpy(v,input+static_cast<std::size_t>(i)*inputStride,sizeof(v));
            for(double x:v) if(!std::isfinite(x)) return JNI_FALSE;
            if(std::abs(v[0])>1000 || std::abs(v[1])>1000 || std::abs(v[2])>1000 || std::abs(v[3])>1000 || v[4]<0 || v[4]>=count || std::floor(v[4])!=v[4]) return JNI_FALSE;
            bodies.push_back({{v[0],v[1],v[2]},v[3],static_cast<std::size_t>(v[4])});
        }
        const auto motions=cache.scene->step(bodies,static_cast<std::uint64_t>(seed),static_cast<std::uint64_t>(tick));
        // Commit after every body has passed the solver. JNI writes use memcpy, avoiding alignment UB.
        for(std::size_t i=0;i<motions.size();++i) {
            const auto& m=motions[i];double header[8]{m.body.p.x,m.body.p.y,m.body.p.z,m.body.vy,m.ground,m.distance,m.heading,(m.reached?1.:0.)+(m.corrected?2.:0.)};
            auto* output=dest+i*outputStride;std::memcpy(output,header,sizeof(header));
            for(std::size_t j=0;j<m.route.size();++j) { const double p[3]{m.route[j].x,m.route[j].y,m.route[j].z};std::memcpy(output+sizeof(header)+j*sizeof(p),p,sizeof(p)); }
        }
        return JNI_TRUE;
    } catch(const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());return JNI_FALSE;
    }
}
