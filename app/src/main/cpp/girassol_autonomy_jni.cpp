#include "cognitive/anokhin_gnw_core.hpp"
#include "hardware/soc_hardware_probe.hpp"

#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <unordered_set>
#include <vector>

namespace {
using girassol::psyche::AttachmentStyle;
using girassol::psyche::HumanSoulState;

constexpr std::size_t kStateFloats = 34;
constexpr std::size_t kContextFloats = 6;
constexpr std::size_t kSensoryFloats = 16;
constexpr std::size_t kStride = kStateFloats + kContextFloats + kSensoryFloats;

enum Field : std::size_t {
    kAge = 0,
    kHealth = 1,
    kAttachment = 2,
    kBigFive = 3,
    kHormones = 8,
    kPad = 11,
    kExpectedSensory = 14,
    kPredictionError = 30,
    kPhase = 31,
    kNaturalFrequency = 32,
    kIgnition = 33,
    kThreat = 34,
    kWarmth = 35,
    kSurprise = 36,
    kSalience = 37,
    kSomaticBias = 38,
    kDeltaTime = 39,
    kActualSensory = 40
};

void throwIllegalArgument(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    if (type != nullptr) env->ThrowNew(type, message);
}

bool finiteRange(float value, float low, float high) {
    return std::isfinite(value) && value >= low && value <= high;
}

bool decodeCitizen(const jfloat* values, std::uint64_t id, HumanSoulState& citizen) {
    citizen.citizenId = id;
    citizen.ageYears = values[kAge];
    citizen.biologicalHealth = values[kHealth];
    const float style = values[kAttachment];
    if (!finiteRange(citizen.ageYears, 0.0f, 120.0f)
        || !finiteRange(citizen.biologicalHealth, 0.0f, 1.0f)
        || !finiteRange(style, 0.0f, 3.0f)
        || std::floor(style) != style) return false;
    citizen.attachment = static_cast<AttachmentStyle>(static_cast<std::uint8_t>(style));
    for (std::size_t i = 0; i < citizen.bigFive.size(); ++i) {
        const float value = values[kBigFive + i];
        if (!finiteRange(value, 0.0f, 1.0f)) return false;
        citizen.bigFive[i] = value;
    }
    citizen.hormones.cortisol = values[kHormones];
    citizen.hormones.oxytocin = values[kHormones + 1];
    citizen.hormones.dopamine = values[kHormones + 2];
    if (!finiteRange(citizen.hormones.cortisol, 0.0f, 1.0f)
        || !finiteRange(citizen.hormones.oxytocin, 0.0f, 1.0f)
        || !finiteRange(citizen.hormones.dopamine, 0.0f, 1.0f)) return false;
    citizen.pad.pleasure = values[kPad];
    citizen.pad.arousal = values[kPad + 1];
    citizen.pad.dominance = values[kPad + 2];
    if (!finiteRange(citizen.pad.pleasure, -1.0f, 1.0f)
        || !finiteRange(citizen.pad.arousal, -1.0f, 1.0f)
        || !finiteRange(citizen.pad.dominance, -1.0f, 1.0f)) return false;
    for (std::size_t i = 0; i < citizen.ard.expectedSensory.size(); ++i) {
        const float value = values[kExpectedSensory + i];
        if (!finiteRange(value, 0.0f, 1.0f)) return false;
        citizen.ard.expectedSensory[i] = value;
        if (!finiteRange(values[kActualSensory + i], 0.0f, 1.0f)) return false;
    }
    citizen.ard.lastPredictionError = values[kPredictionError];
    citizen.kuramotoPhase = values[kPhase];
    citizen.kuramotoNaturalFreq = values[kNaturalFrequency];
    citizen.ignitionIndex = values[kIgnition];
    return finiteRange(citizen.ard.lastPredictionError, 0.0f, 1.0f)
        && std::isfinite(citizen.kuramotoPhase)
        && finiteRange(citizen.kuramotoNaturalFreq, 0.25f, 4.0f)
        && finiteRange(citizen.ignitionIndex, 0.0f, 1.0f)
        && finiteRange(values[kThreat], 0.0f, 1.0f)
        && finiteRange(values[kWarmth], 0.0f, 1.0f)
        && finiteRange(values[kSurprise], 0.0f, 1.0f)
        && finiteRange(values[kSalience], 0.0f, 1.0f)
        && finiteRange(values[kSomaticBias], -1.0f, 1.0f)
        && finiteRange(values[kDeltaTime], 0.0001f, 0.1f);
}

void encodeCitizen(const HumanSoulState& citizen, jfloat* values) {
    values[kAge] = citizen.ageYears;
    values[kHealth] = citizen.biologicalHealth;
    values[kAttachment] = static_cast<float>(citizen.attachment);
    for (std::size_t i = 0; i < citizen.bigFive.size(); ++i) values[kBigFive + i] = citizen.bigFive[i];
    values[kHormones] = citizen.hormones.cortisol;
    values[kHormones + 1] = citizen.hormones.oxytocin;
    values[kHormones + 2] = citizen.hormones.dopamine;
    values[kPad] = citizen.pad.pleasure;
    values[kPad + 1] = citizen.pad.arousal;
    values[kPad + 2] = citizen.pad.dominance;
    for (std::size_t i = 0; i < citizen.ard.expectedSensory.size(); ++i) {
        values[kExpectedSensory + i] = citizen.ard.expectedSensory[i];
    }
    values[kPredictionError] = citizen.ard.lastPredictionError;
    values[kPhase] = static_cast<float>(citizen.kuramotoPhase);
    values[kNaturalFrequency] = citizen.kuramotoNaturalFreq;
    values[kIgnition] = citizen.ignitionIndex;
}
} // namespace

extern "C" JNIEXPORT jfloat JNICALL
Java_com_enhikyy_girassol_cognition_NativeAutonomy_advance(
    JNIEnv* env,
    jobject,
    jlongArray idsArray,
    jfloatArray packedArray,
    jfloat couplingK
) {
    if (idsArray == nullptr || packedArray == nullptr || !std::isfinite(couplingK)) {
        throwIllegalArgument(env, "Entradas da dinâmica Anokhin ausentes ou inválidas");
        return std::numeric_limits<jfloat>::quiet_NaN();
    }
    const jsize count = env->GetArrayLength(idsArray);
    if (count <= 0 || count > 4096
        || env->GetArrayLength(packedArray) != static_cast<jsize>(static_cast<std::size_t>(count) * kStride)) {
        throwIllegalArgument(env, "Dimensão do ensemble Anokhin inválida");
        return std::numeric_limits<jfloat>::quiet_NaN();
    }

    std::vector<jlong> ids(static_cast<std::size_t>(count));
    std::vector<jfloat> packed(static_cast<std::size_t>(count) * kStride);
    env->GetLongArrayRegion(idsArray, 0, count, ids.data());
    env->GetFloatArrayRegion(packedArray, 0, static_cast<jsize>(packed.size()), packed.data());
    if (env->ExceptionCheck()) return std::numeric_limits<jfloat>::quiet_NaN();

    std::unordered_set<jlong> uniqueIds;
    std::vector<HumanSoulState> citizens;
    citizens.reserve(static_cast<std::size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        if (!uniqueIds.insert(ids[static_cast<std::size_t>(i)]).second) {
            throwIllegalArgument(env, "Identidade duplicada no ensemble Anokhin");
            return std::numeric_limits<jfloat>::quiet_NaN();
        }
        auto* values = packed.data() + static_cast<std::size_t>(i) * kStride;
        HumanSoulState citizen;
        if (!decodeCitizen(values, static_cast<std::uint64_t>(ids[static_cast<std::size_t>(i)]), citizen)) {
            throwIllegalArgument(env, "Estado psicológico fora dos limites canônicos");
            return std::numeric_limits<jfloat>::quiet_NaN();
        }

        std::array<float, 16> actual{};
        for (std::size_t j = 0; j < actual.size(); ++j) actual[j] = values[kActualSensory + j];
        const float predictionError = citizen.ard.evaluateOutcome(actual);
        const float deltaTime = values[kDeltaTime];
        citizen.ageYears = std::clamp(citizen.ageYears + deltaTime / (365.25f * 24.0f * 3600.0f), 0.0f, 120.0f);
        citizen.hormones.stepODE(
            deltaTime,
            values[kThreat],
            values[kWarmth],
            std::max(values[kSurprise], predictionError)
        );
        citizen.biologicalHealth = std::clamp(
            citizen.biologicalHealth - deltaTime * std::max(0.0f, citizen.hormones.cortisol - 0.8f) * 0.0001f,
            0.0f, 1.0f
        );

        const float cortisol = citizen.hormones.cortisol;
        const float oxytocin = citizen.hormones.oxytocin;
        const float dopamine = citizen.hormones.dopamine;
        citizen.pad.pleasure = std::clamp(0.45f * oxytocin + 0.25f * dopamine - 0.35f * cortisol - 0.15f, -1.0f, 1.0f);
        citizen.pad.arousal = std::clamp(0.55f * cortisol + 0.35f * dopamine - 0.25f, -1.0f, 1.0f);
        citizen.pad.dominance = std::clamp(0.45f * (1.0f - cortisol) + 0.25f * citizen.biologicalHealth - 0.35f, -1.0f, 1.0f);
        citizen.computeIgnition(values[kSalience], values[kSomaticBias]);
        citizens.push_back(citizen);
    }

    const float orderParameter = girassol::psyche::stepKuramotoEnsemble(
        citizens,
        std::clamp(couplingK, 0.0f, 4.0f),
        packed[kDeltaTime]
    );
    if (!std::isfinite(orderParameter)) {
        throwIllegalArgument(env, "Parâmetro de sincronização Kuramoto inválido");
        return std::numeric_limits<jfloat>::quiet_NaN();
    }
    for (jsize i = 0; i < count; ++i) {
        auto* values = packed.data() + static_cast<std::size_t>(i) * kStride;
        encodeCitizen(citizens[static_cast<std::size_t>(i)], values);
    }
    env->SetFloatArrayRegion(packedArray, 0, static_cast<jsize>(packed.size()), packed.data());
    if (env->ExceptionCheck()) return std::numeric_limits<jfloat>::quiet_NaN();
    return orderParameter;
}


extern "C" JNIEXPORT jlongArray JNICALL
Java_com_enhikyy_girassol_hardware_HardwareAutoTuner_nativeProbe(
    JNIEnv* env,
    jobject
) {
    const auto probe = girassol::hardware::probeSoc();
    const jlong values[] = {
        static_cast<jlong>(probe.hwcap),
        static_cast<jlong>(probe.hwcap2),
        static_cast<jlong>(probe.features),
        static_cast<jlong>(probe.onlineCpuCount)
    };
    jlongArray result = env->NewLongArray(4);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 4, values);
    return result;
}
