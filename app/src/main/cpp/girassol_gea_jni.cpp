#include <jni.h>

// This call cannot allocate or throw across the JNI boundary.
extern "C" JNIEXPORT jint JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_contractVersion(JNIEnv*, jobject) {
    return 2;
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
