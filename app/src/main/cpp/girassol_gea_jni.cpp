#include <jni.h>

// This call cannot allocate or throw across the JNI boundary.
extern "C" JNIEXPORT jint JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_contractVersion(JNIEnv*, jobject) {
    return 3;
}

#include "cognitive/tsetlin_machine.hpp"
#include <new>
namespace {
void invalid(JNIEnv* env) {
    if(!env->ExceptionCheck()) if(auto type=env->FindClass("java/lang/IllegalArgumentException")) env->ThrowNew(type,"Invalid Tsetlin state or input");
}
bool model(JNIEnv* env,jbyteArray input,girassol::TsetlinMachine& machine) {
    if(!input) { invalid(env);return false; }
    const auto size=env->GetArrayLength(input);
    if(size==0) return true;
    if(size!=static_cast<jsize>(machine.bytes)) { invalid(env);return false; }
    std::array<std::uint8_t,girassol::TsetlinMachine::bytes> data{};
    env->GetByteArrayRegion(input,0,size,reinterpret_cast<jbyte*>(data.data()));
    if(env->ExceptionCheck()) return false;
    if(!machine.load(data)) { invalid(env);return false; }
    return true;
}
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_tsetlinTrain(JNIEnv* env,jobject,jbyteArray input,jint features,jint label,jlong seed) {
    girassol::TsetlinMachine machine;
    if(features<0 || features>65535 || label<0 || label>1) { invalid(env);return nullptr; }
    if(!model(env,input,machine)) return nullptr;
    machine.train(features,label==1,static_cast<std::uint64_t>(seed));
    auto output=env->NewByteArray(machine.bytes);
    if(output) env->SetByteArrayRegion(output,0,machine.bytes,reinterpret_cast<const jbyte*>(machine.state.data()));
    return output;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_tsetlinVote(JNIEnv* env,jobject,jbyteArray input,jint features,jboolean early) {
    girassol::TsetlinMachine machine;
    if(features<0 || features>65535) { invalid(env);return 0; }
    if(!model(env,input,machine)) return 0;
    return machine.vote(features,early==JNI_TRUE);
}

#include "evolution/qd_wire.hpp"
namespace {
void invalid_qd(JNIEnv* env) {
    if(!env->ExceptionCheck()) if(auto type=env->FindClass("java/lang/IllegalArgumentException")) env->ThrowNew(type,"Invalid bounded QD state or batch");
}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_qdUpdate(JNIEnv* env,jobject,jobject output,jobject input,jint count,jlong tick,jboolean reset) {
    using namespace girassol::qd;
    if(!output || !input || count<1 || count>150 || tick<1 ||
       env->GetDirectBufferCapacity(output)!=static_cast<jlong>(wire_bytes) ||
       env->GetDirectBufferCapacity(input)!=static_cast<jlong>(candidate_bytes*count)) { invalid_qd(env);return JNI_FALSE; }
    auto* bytes=static_cast<std::uint8_t*>(env->GetDirectBufferAddress(output));
    auto* batch=static_cast<const std::uint8_t*>(env->GetDirectBufferAddress(input));
    if(!bytes || !batch) { invalid_qd(env);return JNI_FALSE; }
    const auto klass=env->GetObjectClass(output);
    if(!klass) return JNI_FALSE;
    const auto method=env->GetMethodID(klass,"isReadOnly","()Z");
    const bool read_only=method && env->CallBooleanMethod(output,method)==JNI_TRUE;
    env->DeleteLocalRef(klass);
    if(env->ExceptionCheck()) return JNI_FALSE;
    if(!method || read_only) { invalid_qd(env);return JNI_FALSE; }
    // No native heap allocation. All reads finish before the caller-owned output is modified.
    State state{};
    if(reset!=JNI_TRUE && !load({bytes,wire_bytes},state)) { invalid_qd(env);return JNI_FALSE; }
    std::array<Candidate,150> candidates{};
    for(int i=0;i<count;++i) {
        Reader reader{{batch+i*candidate_bytes,candidate_bytes}};
        candidates[i]=reader.candidate(true);
        for(std::size_t j=12;j<16;++j) if(batch[i*candidate_bytes+j]!=0) { invalid_qd(env);return JNI_FALSE; }
    }
    if(!state.update(candidates.data(),static_cast<std::size_t>(count),static_cast<std::uint64_t>(tick))) { invalid_qd(env);return JNI_FALSE; }
    return save(state,{bytes,wire_bytes}) ? JNI_TRUE : JNI_FALSE;
}
