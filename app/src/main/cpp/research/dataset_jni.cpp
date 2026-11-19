#include "swap_arena.hpp"
#include <jni.h>
#include <stdexcept>
#include <string>
namespace {
std::string string(JNIEnv* env,jstring value) {
    if(!value || env->GetStringUTFLength(value)>4096) throw std::runtime_error("Caminho da arena inválido");
    const auto* utf=env->GetStringUTFChars(value,nullptr);if(!utf) throw std::runtime_error("Falha ao ler caminho");
    std::string result(utf);env->ReleaseStringUTFChars(value,utf);return result;
}
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_enhikyy_girassol_research_NonRootSwapArena_nativeDecode(JNIEnv* env,jobject,jstring path,jstring format,jlong offset,jlong request) {
    try {
        if(offset<0 || request<=0) throw std::runtime_error("Offset inválido");
        const auto result=girassol::research::extract(string(env,path),string(env,format),static_cast<size_t>(offset),100,request);
        auto output=env->NewByteArray(static_cast<jsize>(result.size()));
        if(output) env->SetByteArrayRegion(output,0,static_cast<jsize>(result.size()),reinterpret_cast<const jbyte*>(result.data()));
        return output;
    } catch(const std::exception& error) { if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what()); }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL Java_com_enhikyy_girassol_research_NonRootSwapArena_cancel(JNIEnv*,jobject,jlong request) { girassol::research::cancel_through(request); }
