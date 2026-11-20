#include "cognitive/anokhin_gnw_core.hpp"
#include "cognitive/deterministic_engines.hpp"
#include "hardware/soc_hardware_probe.hpp"

#include <jni.h>
#include <algorithm>
#include <array>
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
constexpr std::size_t kEngineStateFloats = 8;
constexpr std::size_t kEngineSignalFloats = 8;
constexpr std::size_t kExtendedStateFloats = 52;
constexpr std::size_t kCandidateBiasFloats = 8;
constexpr std::size_t kExtendedStateOffset = 72;
constexpr std::size_t kCandidateBiasOffset = 124;
constexpr std::size_t kStride = kStateFloats + kContextFloats + kSensoryFloats + kEngineStateFloats +
    kEngineSignalFloats + kExtendedStateFloats + kCandidateBiasFloats;

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

std::uint64_t mix64(std::uint64_t x) noexcept {
    x ^= x >> 30; x *= 0xbf58476d1ce4e5b9ULL;
    x ^= x >> 27; x *= 0x94d049bb133111ebULL;
    return x ^ (x >> 31);
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

        girassol::cognition::DeterministicEngineInput engineInput{};
        for (std::size_t j = 0; j < engineInput.priorState.size(); ++j) {
            if (!finiteRange(values[56 + j], 0.0f, 1.0f)) {
                throwIllegalArgument(env, "Estado persistido dos motores cognitivos inválido");
                return std::numeric_limits<jfloat>::quiet_NaN();
            }
            engineInput.priorState[j] = values[56 + j];
        }
        engineInput.threat = values[kThreat];
        engineInput.socialWarmth = values[kWarmth];
        engineInput.surprise = std::max(values[kSurprise], predictionError);
        engineInput.novelty = values[kActualSensory + 8];
        engineInput.predictionError = predictionError;
        const float observed = values[kActualSensory + 9];
        engineInput.observedOutcome = observed >= 0.90f ? 1.0f
            : observed >= 0.50f ? 0.25f
            : observed >= 0.20f ? -0.25f : -1.0f;
        engineInput.dt = deltaTime;
        auto engineOutput = girassol::cognition::stepDeterministicEngines(engineInput);
        auto* mutableValues = packed.data() + static_cast<std::size_t>(i) * kStride;
        std::array<float, kExtendedStateFloats> extendedState{};
        std::copy_n(values + kExtendedStateOffset, kExtendedStateFloats, extendedState.begin());
        for (std::size_t j=0;j<extendedState.size();++j) {
            const float high = j >= 32 && j < 48 ? 65535.0f
                : (j == 48 || j == 49 ? 1.0e9f : 1.0f);
            if (!finiteRange(extendedState[j],0.0f,high)
                || (j >= 32 && j < 48 && std::floor(extendedState[j]) != extendedState[j])) {
                throwIllegalArgument(env,"Estado persistido dos motores estendidos inválido");
                return std::numeric_limits<jfloat>::quiet_NaN();
            }
        }

        // 06 · Tsetlin clauses. Their automata are serialized as GRST each tick and
        // the exact states are stored in the canonical extended-state block.
        extended::TsetlinMachine tsetlin{};
        std::uint32_t featureBits=0;
        for(std::size_t j=0;j<kSensoryFloats;++j)
            if(values[kActualSensory+j]>=.5f) featureBits|=(1u<<j);
        for(std::uint32_t j=0;j<8;++j) {
            const auto state=static_cast<std::uint16_t>(std::clamp(
                static_cast<int>(std::lround(extendedState[j]*31.0f)),0,31));
            if(!tsetlin.add({j,state,static_cast<std::uint32_t>(1u<<j),(j%2)==0})) {
                throwIllegalArgument(env,"Falha ao configurar máquina Tsetlin");
                return std::numeric_limits<jfloat>::quiet_NaN();
            }
        }
        tsetlin.observe(featureBits,engineInput.observedOutcome);
        const auto grst=extended::serializeGrst(tsetlin);
        extended::TsetlinMachine restored{};
        if(!extended::deserializeGrst(grst.data(),grst.size(),restored)) {
            throwIllegalArgument(env,"Codec GRST rejeitou o estado Tsetlin ativo");
            return std::numeric_limits<jfloat>::quiet_NaN();
        }
        const float tsetlinVote=restored.predict(featureBits);
        for(std::size_t j=0;j<8;++j)
            extendedState[j]=static_cast<float>(restored.clauses[j].state)/31.0f;

        // 07 · PER samples an action-conditioned priority tree; Online-EWC
        // consolidates eight engine parameters and eight Tsetlin automata.
        extended::PrioritizedReplayTree replay{};
        const float novelty=values[kActualSensory+8];
        for(std::size_t j=0;j<8;++j) {
            const float priority=std::clamp(
                .82f*extendedState[8+j]+.18f*(.10f+.28f*novelty+
                    .32f*engineInput.predictionError+.30f*std::fabs(engineInput.observedOutcome)),
                .02f,1.0f);
            extendedState[8+j]=priority;
            replay.update(j,priority);
        }
        const auto randomBits=mix64(static_cast<std::uint64_t>(ids[static_cast<std::size_t>(i)]) ^
            (static_cast<std::uint64_t>(values[kPhase]*100000.0f)));
        const float replayMass=replay.total()*static_cast<float>(
            static_cast<double>(randomBits>>11)*(1.0/9007199254740992.0));
        const std::size_t replayIndex=replay.sample(replayMass);
        const float replayPriority=replayIndex<8?extendedState[8+replayIndex]:0.0f;

        extended::OnlineEwc ewc{};
        std::array<float,32> parameters{},newFisher{};
        for(std::size_t j=0;j<32;++j) {
            const std::size_t slot=j%8;
            ewc.anchor[j]=extendedState[16+slot];
            ewc.fisher[j]=extendedState[24+slot];
            parameters[j]=j<8?engineOutput.nextState[j]
                :j<16?extendedState[j-8]:values[kActualSensory+(j%kSensoryFloats)];
            newFisher[j]=std::clamp(.02f+.36f*engineInput.surprise+
                .30f*engineInput.predictionError+.24f*novelty,0.0f,1.0f);
        }
        const float ewcPenalty=ewc.penalty(parameters,.035f);
        ewc.consolidate(parameters,newFisher,.95f);
        for(std::size_t j=0;j<8;++j) {
            float anchor=0.0f,fisher=0.0f;
            for(std::size_t k=0;k<4;++k) { anchor+=ewc.anchor[j+8*k];fisher+=ewc.fisher[j+8*k]; }
            extendedState[16+j]=std::clamp(anchor*.25f,0.0f,1.0f);
            extendedState[24+j]=std::clamp(fisher*.25f,0.0f,1.0f);
        }

        // 08 · Rolling 256-bit perceptual signature. 16 words of 16 bits are
        // stored losslessly in the canonical frame and mirrored to the GRS blob.
        extended::Signature256 currentSignature{},previousSignature{};
        for(std::size_t j=0;j<kSensoryFloats;++j) {
            const auto q=static_cast<std::uint64_t>(std::lround(
                std::clamp(values[kActualSensory+j],0.0f,1.0f)*65535.0f));
            auto& word=currentSignature.words[j%4];
            word=mix64(word ^ mix64(q ^ (static_cast<std::uint64_t>(j)<<32) ^
                static_cast<std::uint64_t>(ids[static_cast<std::size_t>(i)])));
        }
        for(std::size_t j=0;j<16;++j)
            previousSignature.words[j/4] |= static_cast<std::uint64_t>(
                static_cast<std::uint16_t>(extendedState[32+j])) << ((j%4)*16);
        const float signatureNovelty=static_cast<float>(
            extended::hammingDistance(currentSignature,previousSignature))/256.0f;
        for(std::size_t j=0;j<16;++j)
            extendedState[32+j]=static_cast<float>((currentSignature.words[j/4]>>((j%4)*16))&0xffffULL);

        // 09 · Immutable CoreSelf and PIANO rank the eight available activities.
        // Reciprocal-rank fusion combines homeostatic and replay rankings.
        std::array<float,8> homeostasis{
            1.0f-values[kActualSensory+1],1.0f-values[kActualSensory],
            1.0f-values[kActualSensory+2],1.0f-values[kActualSensory],
            1.0f-values[kActualSensory+3],1.0f-values[kActualSensory+4],
            1.0f-values[kActualSensory+4],1.0f-values[kActualSensory+4]};
        std::array<float,8> memoryScore{};
        for(std::size_t j=0;j<8;++j)
            memoryScore[j]=.58f*engineOutput.nextState[j]+.27f*extendedState[8+j]+
                .15f*(engineOutput.signals[2]+1.0f)*.5f;
        std::array<std::size_t,8> homeRank{},memoryRank{};
        for(std::size_t j=0;j<8;++j) homeRank[j]=memoryRank[j]=j;
        std::stable_sort(homeRank.begin(),homeRank.end(),[&](auto a,auto b) {
            return homeostasis[a]==homeostasis[b]?a<b:homeostasis[a]>homeostasis[b];
        });
        std::stable_sort(memoryRank.begin(),memoryRank.end(),[&](auto a,auto b) {
            return memoryScore[a]==memoryScore[b]?a<b:memoryScore[a]>memoryScore[b];
        });
        std::array<unsigned,8> rankHome{},rankMemory{};
        for(unsigned rank=0;rank<8;++rank) { rankHome[homeRank[rank]]=rank;rankMemory[memoryRank[rank]]=rank; }
        const std::array<float,8> identity{
            citizen.bigFive[0],citizen.bigFive[1],citizen.bigFive[2],citizen.bigFive[3],
            citizen.bigFive[4],citizen.biologicalHealth,values[kWarmth],
            citizen.hormones.dopamine};
        const extended::CoreSelf coreSelf(identity);
        std::array<extended::PianoCandidate,8> candidates{};
        for(std::size_t j=0;j<8;++j) {
            const float fused=extended::reciprocalRankFusion(
                rankHome[j],rankMemory[j]);
            const float target=(j==4?values[kWarmth]:j>=5?values[kActualSensory+4]:homeostasis[j]);
            const float alignment=std::clamp(1.0f-std::fabs(identity[j]-target),0.0f,1.0f);
            candidates[j].utility=homeostasis[j]*1.2f+memoryScore[j]*.35f+fused*12.0f;
            candidates[j].epistemic=std::clamp(.55f*novelty+.45f*signatureNovelty,0.0f,1.0f);
            candidates[j].identityAlignment=alignment;
        }
        const auto piano=extended::pianoTop3(candidates,coreSelf);
        for(std::size_t j=0;j<8;++j) mutableValues[kCandidateBiasOffset+j]=-.025f;
        for(const auto action:piano) if(action<8) mutableValues[kCandidateBiasOffset+action]=.065f;
        if(replayIndex<8) mutableValues[kCandidateBiasOffset+replayIndex]+=.015f;
        const float freeEnergy=extended::expectedFreeEnergy(
            candidates[piano[0]<8?piano[0]:0],coreSelf);

        // 10 · RSI promotion ledger and Hawkes social-intensity trace.
        const float promotionGate=engineOutput.signals[7];
        if(promotionGate>=.75f) extendedState[48]=std::min(16777216.0f,extendedState[48]+1.0f);
        else extendedState[49]=std::min(16777216.0f,extendedState[49]+1.0f);
        extendedState[50]=std::clamp(promotionGate,0.0f,1.0f);
        extendedState[51]=std::clamp(extended::hawkesDecay(
            extendedState[51],engineInput.dt,1.4f,
            .018f*values[kWarmth]+.012f*values[kActualSensory+3]),0.0f,1.0f);

        engineOutput.signals[0]=std::clamp(engineOutput.signals[0]+.05f*std::fabs(tsetlinVote),0.0f,1.0f);
        engineOutput.signals[1]=std::clamp(engineOutput.signals[1]+.16f*signatureNovelty,0.0f,1.0f);
        engineOutput.signals[2]=std::clamp(engineOutput.signals[2]+.12f*(replayPriority-.5f),-1.0f,1.0f);
        engineOutput.signals[4]=std::clamp(engineOutput.signals[4]+.08f*ewcPenalty,0.0f,1.0f);
        engineOutput.signals[5]=std::clamp(.75f*engineOutput.signals[5]+.25f*freeEnergy,0.0f,1.0f);
        engineOutput.signals[7]=std::clamp(engineOutput.signals[7]*(.8f+.2f*extendedState[51]),0.0f,1.0f);

        for (std::size_t j = 0; j < engineOutput.nextState.size(); ++j) {
            mutableValues[56 + j] = engineOutput.nextState[j];
            mutableValues[64 + j] = engineOutput.signals[j];
        }
        std::copy(extendedState.begin(),extendedState.end(),mutableValues+kExtendedStateOffset);
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
