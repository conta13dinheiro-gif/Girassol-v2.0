#include "semantic_index.hpp"
#include <jni.h>
#include <cstdint>
#include <cstring>
#include <vector>
extern "C" JNIEXPORT jint JNICALL Java_com_enhikyy_girassol_cognition_NativeSemanticMemory_rank(JNIEnv* env,jobject,jobject input,jint dimensions,jint count,jint limit,jobject output) {
    if(!input || !output || dimensions<1 || dimensions>32768 || count<0 || count>2048 || limit<0 || limit>count) return -1;
    const jlong values=(static_cast<jlong>(count)+1)*dimensions;
    if(env->GetDirectBufferCapacity(input)!=values*4 || env->GetDirectBufferCapacity(output)!=static_cast<jlong>(limit)*8) return -1;
    auto* data=static_cast<const float*>(env->GetDirectBufferAddress(input));auto* target=static_cast<unsigned char*>(env->GetDirectBufferAddress(output));
    if(!data || reinterpret_cast<std::uintptr_t>(data)%alignof(float) || (limit && !target)) return -1;
    std::vector<girassol::semantic::Hit> hits(static_cast<size_t>(limit));
    const int found=girassol::semantic::search({data,static_cast<size_t>(dimensions)},{data+dimensions,static_cast<size_t>(count)*dimensions},dimensions,hits);
    if(found<0) return -1;
    for(int i=0;i<found;++i) { std::memcpy(target+i*8,&hits[i].index,4);std::memcpy(target+i*8+4,&hits[i].similarity,4); }
    return found;
}
