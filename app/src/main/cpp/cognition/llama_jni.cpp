#include "inference.hpp"
#include <jni.h>
#include <exception>
#include <stdexcept>
#include <string>

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_enhikyy_girassol_cognition_NativeCognition_nativeInfer(JNIEnv* env, jobject, jstring path, jbyteArray prompt, jlong request, jstring adapter, jboolean review) {
    try {
        if (!path || !prompt || env->GetArrayLength(prompt) > 4096 || env->GetStringUTFLength(path) > 4096)
            throw std::runtime_error("Parâmetros JNI inválidos");
        const char* utf = env->GetStringUTFChars(path, nullptr);
        if (!utf) return nullptr;
        const std::string filename(utf);
        env->ReleaseStringUTFChars(path, utf);
        const auto length = env->GetArrayLength(prompt);
        std::string input(length, '\0');
        env->GetByteArrayRegion(prompt, 0, length, reinterpret_cast<jbyte*>(input.data()));
        if (env->ExceptionCheck()) return nullptr;
        std::string adapterPath;
        if (adapter) {
            if (env->GetStringUTFLength(adapter)>4096) throw std::runtime_error("Caminho LoRA inválido");
            const char* name=env->GetStringUTFChars(adapter,nullptr);
            if (!name) return nullptr;
            adapterPath=name; env->ReleaseStringUTFChars(adapter,name);
        }
        const auto output = girassol::cognition::infer(filename, input, request,30000,adapterPath,review ? girassol::cognition::Task::KnowledgeReview : girassol::cognition::Task::Activity);
        auto result = env->NewByteArray(static_cast<jsize>(output.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte*>(output.data()));
        return result;
    } catch (const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
    } catch (...) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Falha nativa na inferência");
    }
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_cognition_NativeCognition_cancel(JNIEnv*, jobject, jlong request) {
    girassol::cognition::cancel_through(request);
}
