#include "plasticity.hpp"
#include <jni.h>
#include <cstring>
#include <cmath>

extern "C" JNIEXPORT jboolean JNICALL Java_com_enhikyy_girassol_cognition_NativeMind_advance(JNIEnv* env,jobject,jobject data) {
    constexpr int count=248+8+12+4+8;
    if (!data || env->GetDirectBufferCapacity(data)!=count*static_cast<jlong>(sizeof(double))) return JNI_FALSE;
    auto* address=env->GetDirectBufferAddress(data); if (!address) return JNI_FALSE;
    // memcpy permits unaligned direct buffer slices; no raw C++ layout crosses JNI.
    double values[count]; std::memcpy(values,address,sizeof(values));
    girassol::mind::Frame f;
    std::memcpy(f.weights.data(),values,248*sizeof(double));
    std::memcpy(f.hidden.data(),values+248,8*sizeof(double));
    std::memcpy(f.input.data(),values+256,12*sizeof(double));
    f.reward=values[268]; f.novelty=values[269];
    if (!(values[270]>=0 && values[270]<=7 && std::trunc(values[270])==values[270]) || !(values[271]==0 || values[271]==1)) return JNI_FALSE;
    f.action=static_cast<int>(values[270]); f.learn=values[271]!=0;
    if (!girassol::mind::advance(f)) return JNI_FALSE;
    std::memcpy(values,f.weights.data(),248*sizeof(double));
    std::memcpy(values+248,f.hidden.data(),8*sizeof(double));
    std::memcpy(values+272,f.scores.data(),8*sizeof(double));
    std::memcpy(address,values,sizeof(values));
    return JNI_TRUE;
}
