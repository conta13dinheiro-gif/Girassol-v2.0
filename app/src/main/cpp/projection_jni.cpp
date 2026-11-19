#include "projection.hpp"
#include <jni.h>
#include <cstddef>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_render_NativeProjection_nativeProject(JNIEnv* env, jobject,
                                                                 jobject input, jobject output, jint count) {
    if (!input || !output || count < 0) return JNI_FALSE;
    const jlong in_capacity = env->GetDirectBufferCapacity(input);
    const jlong out_capacity = env->GetDirectBufferCapacity(output);
    if (in_capacity < 0 || out_capacity < 0) return JNI_FALSE;
    const void* src = env->GetDirectBufferAddress(input);
    void* dst = env->GetDirectBufferAddress(output);
    return project_points(src, static_cast<std::size_t>(in_capacity), dst,
                          static_cast<std::size_t>(out_capacity), static_cast<std::size_t>(count)) ? JNI_TRUE : JNI_FALSE;
}
