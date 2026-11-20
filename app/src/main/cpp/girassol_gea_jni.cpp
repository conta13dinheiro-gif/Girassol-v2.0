#include "cognitive/tsetlin_machine.hpp"

#include <jni.h>
#include <cstdint>
#include <memory>
#include <mutex>
#include <new>
#include <unordered_map>
#include <vector>

namespace {
std::mutex gTsetlinMutex;
std::unordered_map<jlong, std::unique_ptr<girassol::tsetlin::PersistentTsetlinMachine>> gTsetlinModels;
jlong gNextTsetlinHandle = 1;

void throwJava(JNIEnv* env, const char* className, const char* message) {
    if (env->ExceptionCheck()) return;
    if (jclass type = env->FindClass(className)) env->ThrowNew(type, message);
}

bool readFeatures(JNIEnv* env, jbyteArray array, std::vector<std::uint8_t>& output) {
    if (array == nullptr) return false;
    const jsize size = env->GetArrayLength(array);
    if (size <= 0 || size > 4096) return false;
    try {
        std::vector<jbyte> bytes(static_cast<std::size_t>(size));
        env->GetByteArrayRegion(array, 0, size, bytes.data());
        if (env->ExceptionCheck()) return false;
        output.resize(static_cast<std::size_t>(size));
        for (jsize i = 0; i < size; ++i) {
            const auto value = static_cast<std::uint8_t>(bytes[static_cast<std::size_t>(i)]);
            if (value > 1U) return false;
            output[static_cast<std::size_t>(i)] = value;
        }
        return true;
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Tsetlin feature allocation failed");
        return false;
    }
}
} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_contractVersion(JNIEnv*, jobject) {
    return 1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_nativeCreateTsetlin(
    JNIEnv* env, jobject, jint features, jint clauses, jint states, jfloat specificity,
    jint threshold, jlong seed
) {
    try {
        auto model = std::make_unique<girassol::tsetlin::PersistentTsetlinMachine>(
            static_cast<std::uint32_t>(features), static_cast<std::uint32_t>(clauses),
            states, specificity, threshold, static_cast<std::uint64_t>(seed)
        );
        std::lock_guard<std::mutex> lock(gTsetlinMutex);
        const jlong handle = gNextTsetlinHandle++;
        if (handle <= 0) {
            gNextTsetlinHandle = 2;
            throw std::overflow_error("Tsetlin handle space exhausted");
        }
        gTsetlinModels.emplace(handle, std::move(model));
        return handle;
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Tsetlin model allocation failed");
    } catch (const std::exception& error) {
        throwJava(env, "java/lang/IllegalArgumentException", error.what());
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_nativeDestroyTsetlin(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gTsetlinMutex);
    gTsetlinModels.erase(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_nativeTsetlinTrainStep(
    JNIEnv* env, jobject, jlong handle, jbyteArray featuresArray, jboolean targetPositive
) {
    std::vector<std::uint8_t> features;
    if (!readFeatures(env, featuresArray, features)) {
        throwJava(env, "java/lang/IllegalArgumentException", "Invalid Tsetlin feature vector");
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(gTsetlinMutex);
    const auto it = gTsetlinModels.find(handle);
    if (it == gTsetlinModels.end() || features.size() != it->second->numFeatures()) return JNI_FALSE;
    try {
        it->second->trainStep(features, targetPositive == JNI_TRUE);
        return JNI_TRUE;
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Tsetlin training allocation failed");
    } catch (const std::exception& error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_nativeTsetlinPredictVote(
    JNIEnv* env, jobject, jlong handle, jbyteArray featuresArray
) {
    std::vector<std::uint8_t> features;
    if (!readFeatures(env, featuresArray, features)) {
        throwJava(env, "java/lang/IllegalArgumentException", "Invalid Tsetlin feature vector");
        return 0;
    }
    std::lock_guard<std::mutex> lock(gTsetlinMutex);
    const auto it = gTsetlinModels.find(handle);
    if (it == gTsetlinModels.end() || features.size() != it->second->numFeatures()) return 0;
    return it->second->predictVoteEarlyExit(features);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_nativeTsetlinConfidence(
    JNIEnv* env, jobject, jlong handle, jbyteArray featuresArray
) {
    std::vector<std::uint8_t> features;
    if (!readFeatures(env, featuresArray, features)) {
        throwJava(env, "java/lang/IllegalArgumentException", "Invalid Tsetlin feature vector");
        return 0.0f;
    }
    std::lock_guard<std::mutex> lock(gTsetlinMutex);
    const auto it = gTsetlinModels.find(handle);
    if (it == gTsetlinModels.end() || features.size() != it->second->numFeatures()) return 0.0f;
    return it->second->confidence(features);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_nativeTsetlinSerialize(
    JNIEnv* env, jobject, jlong handle
) {
    try {
        std::vector<std::uint8_t> blob;
        {
            std::lock_guard<std::mutex> lock(gTsetlinMutex);
            const auto it = gTsetlinModels.find(handle);
            if (it == gTsetlinModels.end()) return nullptr;
            blob = it->second->serializeBinary();
        }
        if (blob.empty() || blob.size() > 1024U * 1024U) return nullptr;
        jbyteArray result = env->NewByteArray(static_cast<jsize>(blob.size()));
        if (result == nullptr) return nullptr;
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(blob.size()),
                                reinterpret_cast<const jbyte*>(blob.data()));
        return result;
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Tsetlin serialization allocation failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_nativeTsetlinRestore(
    JNIEnv* env, jobject, jbyteArray blobArray
) {
    if (blobArray == nullptr) return 0;
    const jsize size = env->GetArrayLength(blobArray);
    if (size < 32 || size > 1024 * 1024) {
        throwJava(env, "java/lang/IllegalArgumentException", "Tsetlin snapshot size is invalid");
        return 0;
    }
    try {
        std::vector<jbyte> bytes(static_cast<std::size_t>(size));
        env->GetByteArrayRegion(blobArray, 0, size, bytes.data());
        if (env->ExceptionCheck()) return 0;
        std::vector<std::uint8_t> blob(static_cast<std::size_t>(size));
        for (jsize i = 0; i < size; ++i) blob[static_cast<std::size_t>(i)] = static_cast<std::uint8_t>(bytes[static_cast<std::size_t>(i)]);
        auto model = std::make_unique<girassol::tsetlin::PersistentTsetlinMachine>(1, 1, 2, 3.5f, 1, 42U);
        if (!model->deserializeBinary(blob)) {
            throwJava(env, "java/lang/IllegalArgumentException", "Tsetlin snapshot is invalid");
            return 0;
        }
        std::lock_guard<std::mutex> lock(gTsetlinMutex);
        const jlong handle = gNextTsetlinHandle++;
        if (handle <= 0) {
            gNextTsetlinHandle = 2;
            throw std::overflow_error("Tsetlin handle space exhausted");
        }
        gTsetlinModels.emplace(handle, std::move(model));
        return handle;
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Tsetlin restore allocation failed");
    } catch (const std::exception& error) {
        throwJava(env, "java/lang/IllegalArgumentException", error.what());
    }
    return 0;
}
