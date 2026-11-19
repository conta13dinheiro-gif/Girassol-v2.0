#include "city_mesh.hpp"
#include <jni.h>
#include <new>

extern "C" JNIEXPORT jlong JNICALL
Java_com_enhikyy_girassol_render_NativeCityMesh_nativeBuild(JNIEnv* env, jobject, jobject input,
        jint count, jlong seed, jint mode, jint color, jobject vertices, jobject indices) {
    if (count<0 || count>64 || (mode<0 || mode>3)) return 0;
    try {
        girassol::Mesh mesh;
        if (mode!=1) {
            if (!input) return 0;
            const auto bytes=env->GetDirectBufferCapacity(input);
            if (bytes<0 || !girassol::city_mesh(env->GetDirectBufferAddress(input),
                    static_cast<std::size_t>(bytes),static_cast<std::size_t>(count),static_cast<std::uint64_t>(seed),mesh,mode!=2,mode!=3)) return 0;
        } else mesh=girassol::citizen_mesh(static_cast<std::uint32_t>(color),static_cast<unsigned>(count));
        const auto result=(static_cast<jlong>(mesh.vertices.size())<<32) | static_cast<jlong>(mesh.indices.size());
        if (!vertices && !indices) return result;
        if (!vertices || !indices) return 0;
        const auto nv=env->GetDirectBufferCapacity(vertices), ni=env->GetDirectBufferCapacity(indices);
        if (nv<0 || ni<0 || !girassol::copy_mesh(mesh,env->GetDirectBufferAddress(vertices),
                static_cast<std::size_t>(nv),env->GetDirectBufferAddress(indices),static_cast<std::size_t>(ni))) return 0;
        return result;
    } catch (const std::bad_alloc&) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),"Native city mesh allocation failed");
        return 0;
    }
}
