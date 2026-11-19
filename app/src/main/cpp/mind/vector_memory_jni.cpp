#include "vector_memory.hpp"
#include <jni.h>
#include <cstring>
#include <vector>
extern "C" JNIEXPORT jint JNICALL Java_com_enhikyy_girassol_cognition_NativeMemory_rank(JNIEnv* env,jobject,jobject input,jint count,jint limit,jobject output) {
    if(!input || !output || count<0 || limit<0 || limit>count) return -1;
    constexpr jlong rowBytes=18*sizeof(float), queryBytes=16*sizeof(float);
    if(env->GetDirectBufferCapacity(input)!=queryBytes+static_cast<jlong>(count)*rowBytes || env->GetDirectBufferCapacity(output)!=static_cast<jlong>(limit)*8) return -1;
    auto* bytes=static_cast<const unsigned char*>(env->GetDirectBufferAddress(input));
    auto* target=env->GetDirectBufferAddress(output);
    if(!bytes || (limit && !target)) return -1;
    std::array<float,16> query{}; std::memcpy(query.data(),bytes,queryBytes);
    std::vector<girassol::memory::Entry> rows(static_cast<size_t>(count));
    for(int i=0;i<count;++i) {
        const auto* row=bytes+queryBytes+static_cast<jlong>(i)*rowBytes;
        std::memcpy(rows[i].embedding.data(),row,queryBytes);
        std::memcpy(&rows[i].age,row+queryBytes,sizeof(float));
        std::memcpy(&rows[i].importance,row+queryBytes+sizeof(float),sizeof(float));
    }
    std::vector<girassol::memory::Hit> result(static_cast<size_t>(limit));
    const auto size=girassol::memory::recall(query,rows,result);
    if(size<0) return -1;
    for(int i=0;i<size;++i) {
        // Index bits travel as Int, score as Float in a native-order DirectByteBuffer.
        std::memcpy(static_cast<unsigned char*>(target)+i*8,&result[i].index,4);
        std::memcpy(static_cast<unsigned char*>(target)+i*8+4,&result[i].score,4);
    }
    return size;
}
