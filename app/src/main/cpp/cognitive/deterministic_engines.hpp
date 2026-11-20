#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace girassol::cognition {

struct SubjectiveOpinion {
    float belief = 0.0f;
    float disbelief = 0.0f;
    float uncertainty = 1.0f;
    float baseRate = 0.5f;

    bool valid(float tolerance = 1.0e-4f) const noexcept {
        return std::isfinite(belief) && std::isfinite(disbelief)
            && std::isfinite(uncertainty) && std::isfinite(baseRate)
            && belief >= 0.0f && disbelief >= 0.0f && uncertainty >= 0.0f
            && baseRate >= 0.0f && baseRate <= 1.0f
            && std::fabs((belief + disbelief + uncertainty) - 1.0f) <= tolerance;
    }

    float expectation() const noexcept {
        return belief + baseRate * uncertainty;
    }
};

inline SubjectiveOpinion averageFusion(
    const SubjectiveOpinion& left, const SubjectiveOpinion& right
) noexcept {
    if (!left.valid() || !right.valid()) return {};
    return {
        0.5f * (left.belief + right.belief),
        0.5f * (left.disbelief + right.disbelief),
        0.5f * (left.uncertainty + right.uncertainty),
        0.5f * (left.baseRate + right.baseRate)
    };
}

// Trust opinion ⊗ source opinion: belief in the source scales its evidence;
// distrust and uncertainty are transferred to the resulting uncertainty.
inline SubjectiveOpinion discountTrust(
    const SubjectiveOpinion& trust, const SubjectiveOpinion& source
) noexcept {
    if (!trust.valid() || !source.valid()) return {};
    return {
        trust.belief * source.belief,
        trust.belief * source.disbelief,
        trust.disbelief + trust.uncertainty + trust.belief * source.uncertainty,
        source.baseRate
    };
}

// Cumulative fusion of independent evidence. The vacuous-denominator case
// (two dogmatic opinions) uses a bounded arithmetic consensus rather than NaNs.
inline SubjectiveOpinion cumulativeFusion(
    const SubjectiveOpinion& left, const SubjectiveOpinion& right
) noexcept {
    if (!left.valid() || !right.valid()) return {};
    const float denominator = left.uncertainty + right.uncertainty
        - left.uncertainty * right.uncertainty;
    if (denominator <= 1.0e-6f) {
        return {
            0.5f * (left.belief + right.belief),
            0.5f * (left.disbelief + right.disbelief),
            0.0f,
            0.5f * (left.baseRate + right.baseRate)
        };
    }
    const float rateWeight = left.uncertainty + right.uncertainty;
    const float baseRate = rateWeight > 1.0e-6f
        ? (left.baseRate * right.uncertainty + right.baseRate * left.uncertainty) / rateWeight
        : 0.5f * (left.baseRate + right.baseRate);
    return {
        (left.belief * right.uncertainty + right.belief * left.uncertainty) / denominator,
        (left.disbelief * right.uncertainty + right.disbelief * left.uncertainty) / denominator,
        (left.uncertainty * right.uncertainty) / denominator,
        std::clamp(baseRate, 0.0f, 1.0f)
    };
}

struct DeterministicEngineInput {
    std::array<float, 8> priorState{};
    float threat = 0.0f;
    float socialWarmth = 0.0f;
    float surprise = 0.0f;
    float novelty = 0.0f;
    float predictionError = 0.0f;
    float observedOutcome = 0.0f; // -1 failure, 0.2 reserved, 0.5 move, 1 success
    float dt = 1.0f / 60.0f;
};

struct DeterministicEngineOutput {
    std::array<float, 8> nextState{};
    // Tsetlin confidence, TTT curiosity, episodic Q, subjective probability,
    // variational F, expected G, Brier score, bounded-RSI e-gate.
    std::array<float, 8> signals{};
};

inline float unit(float value) noexcept {
    return std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f;
}

inline DeterministicEngineOutput stepDeterministicEngines(
    const DeterministicEngineInput& input
) noexcept {
    DeterministicEngineOutput output{};
    const float dt = std::isfinite(input.dt) ? std::clamp(input.dt, 0.0001f, 0.1f) : 1.0f / 60.0f;
    const float threat = unit(input.threat);
    const float warmth = unit(input.socialWarmth);
    const float surprise = unit(input.surprise);
    const float novelty = unit(input.novelty);
    const float predictionError = unit(input.predictionError);
    const float outcome = std::isfinite(input.observedOutcome)
        ? std::clamp(input.observedOutcome, -1.0f, 1.0f) : -1.0f;

    // Tsetlin automata: bounded continuous counters represent deterministic clause votes.
    float clauseSum = 0.0f;
    const std::array<float, 4> features{1.0f - threat, warmth, 1.0f - surprise, novelty};
    for (std::size_t i = 0; i < 4; ++i) {
        const float prior = unit(input.priorState[i]);
        const float target = features[i];
        const float direction = outcome >= 0.5f ? target : -target;
        output.nextState[i] = unit(prior + dt * 2.0f * direction);
        clauseSum += output.nextState[i];
    }
    const float reflexConfidence = clauseSum * 0.25f;

    // Autotelic TTT: Ebbinghaus decay plus replay priority for surprising, novel outcomes.
    const float priorRetention = unit(input.priorState[4]);
    const float retention = unit(priorRetention * std::exp(-dt / 900.0f)
        + dt * (0.08f * novelty + 0.05f * std::fabs(outcome)));
    output.nextState[4] = retention;
    const float curiosityBonus = unit((1.0f - retention) * 0.55f + novelty * 0.35f + surprise * 0.10f);

    // Episodic MemRL Q: one bounded TD update, keyed by the canonical action outcome.
    const float oldQ = unit(input.priorState[5]) * 2.0f - 1.0f;
    const float q = std::clamp(oldQ + 0.20f * (outcome - oldQ), -1.0f, 1.0f);
    output.nextState[5] = unit((q + 1.0f) * 0.5f);

    // Jøsang: create the observation opinion, discount it by relational trust,
    // then combine it with the persisted prior using cumulative and average fusion.
    const float evidence = unit(0.15f + 0.70f * warmth + 0.15f * retention);
    const float priorUncertainty = unit(input.priorState[6]);
    const float priorProbability = unit((oldQ + 1.0f) * 0.5f);
    const SubjectiveOpinion priorOpinion{
        priorProbability * (1.0f - priorUncertainty),
        (1.0f - priorProbability) * (1.0f - priorUncertainty),
        priorUncertainty,
        0.5f
    };
    const float observedProbability = unit((outcome + 1.0f) * 0.5f);
    const SubjectiveOpinion observationOpinion{
        evidence * observedProbability,
        evidence * (1.0f - observedProbability),
        1.0f - evidence,
        0.5f
    };
    const SubjectiveOpinion trustOpinion{warmth, 0.0f, 1.0f - warmth, 0.5f};
    const SubjectiveOpinion trustedObservation = discountTrust(trustOpinion, observationOpinion);
    const SubjectiveOpinion fusedOpinion = cumulativeFusion(priorOpinion, trustedObservation);
    const SubjectiveOpinion meanOpinion = averageFusion(priorOpinion, trustedObservation);
    const float projected = unit(0.8f * fusedOpinion.expectation() + 0.2f * meanOpinion.expectation());
    output.nextState[6] = fusedOpinion.uncertainty;

    // Variational free energy and expected free energy remain finite and bounded.
    const float freeEnergy = unit(0.55f * predictionError + 0.30f * threat + 0.15f * surprise);
    const float expectedFreeEnergy = unit(0.45f * threat + 0.30f * (1.0f - reflexConfidence)
        + 0.25f * (1.0f - novelty));

    // Brier calibration is updated from the prior success probability and observed outcome.
    const float predictedSuccess = unit((oldQ + 1.0f) * 0.5f);
    const float targetSuccess = unit((outcome + 1.0f) * 0.5f);
    const float previousBrier = unit(input.priorState[7]);
    const float calibrationError = predictedSuccess - targetSuccess;
    const float brier = unit(0.8f * previousBrier + 0.2f * calibrationError * calibrationError);
    output.nextState[7] = brier;

    // Four promotion e-gates: calibrated evidence, bounded surprise, low free energy,
    // and no severe threat. This is a deterministic proposal gate, not self-modifying code.
    const bool evidenceGate = reflexConfidence >= 0.62f && projected >= 0.45f;
    const bool calibrationGate = brier <= 0.30f;
    const bool stabilityGate = freeEnergy <= 0.72f && surprise <= 0.85f;
    const bool safetyGate = threat <= 0.80f;
    const float promotionGate = evidenceGate && calibrationGate && stabilityGate && safetyGate ? 1.0f : 0.0f;

    output.signals = {
        reflexConfidence,
        curiosityBonus,
        q,
        projected,
        freeEnergy,
        expectedFreeEnergy,
        brier,
        promotionGate
    };
    return output;
}

} // namespace girassol::cognition


// Extended deterministic primitives for the 06-09 cognitive pipeline.
// These are bounded, allocation-conscious value types; callers own persistence.
namespace extended {

struct TsetlinClause {
    std::uint32_t id = 0;
    std::uint16_t state = 0; // 0..2N-1 automaton state
    std::uint32_t featureMask = 0;
    bool polarity = true;
};

struct TsetlinMachine {
    static constexpr std::uint16_t kMaxState = 31;
    std::array<TsetlinClause, 32> clauses{};
    std::uint8_t count = 0;

    bool add(TsetlinClause clause) noexcept {
        if (count >= clauses.size() || clause.state > kMaxState) return false;
        clauses[count++] = clause;
        return true;
    }

    float predict(std::uint32_t features, float threshold = 0.05f) const noexcept {
        if (count == 0) return 0.0f;
        std::int32_t votes = 0;
        for (std::uint8_t i = 0; i < count; ++i) {
            const auto& c = clauses[i];
            const bool matches = (features & c.featureMask) == c.featureMask;
            if (matches && c.state >= 16) votes += c.polarity ? 1 : -1;
        }
        const float confidence = static_cast<float>(votes) / static_cast<float>(count);
        return std::fabs(confidence) < threshold ? 0.0f : confidence;
    }

    /** Deterministic Type-I/Type-II feedback over bounded two-action automata. */
    void observe(std::uint32_t features, float reward) noexcept {
        if (!std::isfinite(reward) || std::fabs(reward) < 0.01f) return;
        for (std::uint8_t i = 0; i < count; ++i) {
            auto& clause = clauses[i];
            const bool matches = (features & clause.featureMask) == clause.featureMask;
            const bool reinforceMatch = (reward > 0.0f) == clause.polarity;
            const bool increase = matches == reinforceMatch;
            if (increase) clause.state = std::min<std::uint16_t>(kMaxState, static_cast<std::uint16_t>(clause.state + 1));
            else if (clause.state > 0) --clause.state;
        }
    }
};

inline std::vector<std::uint8_t> serializeGrst(const TsetlinMachine& machine) {
    std::vector<std::uint8_t> out;
    out.reserve(8 + static_cast<std::size_t>(machine.count) * 11);
    auto put = [&out](std::uint32_t value, int bytes) {
        for (int i = 0; i < bytes; ++i) out.push_back(static_cast<std::uint8_t>(value >> (i * 8)));
    };
    put(0x54535247u, 4); // "GRST" little-endian
    put(1, 2);
    put(machine.count, 2);
    for (std::uint8_t i = 0; i < machine.count; ++i) {
        const auto& c = machine.clauses[i];
        put(c.id, 4); put(c.state, 2); put(c.featureMask, 4); put(c.polarity ? 1u : 0u, 1);
    }
    return out;
}

inline bool deserializeGrst(const std::uint8_t* data, std::size_t size, TsetlinMachine& out) noexcept {
    if (data == nullptr || size < 8) return false;
    auto get = [data, size](std::size_t offset, int bytes, std::uint32_t& value) {
        if (offset > size || static_cast<std::size_t>(bytes) > size - offset) return false;
        value = 0;
        for (int i = 0; i < bytes; ++i) value |= static_cast<std::uint32_t>(data[offset + i]) << (i * 8);
        return true;
    };
    std::uint32_t magic = 0, version = 0, count = 0;
    if (!get(0,4,magic) || !get(4,2,version) || !get(6,2,count)
        || magic != 0x54535247u || version != 1 || count > 32
        || size != 8 + static_cast<std::size_t>(count) * 11) return false;
    TsetlinMachine candidate{};
    std::size_t offset = 8;
    for (std::uint32_t i = 0; i < count; ++i) {
        std::uint32_t id=0,state=0,mask=0,polarity=0;
        if (!get(offset,4,id) || !get(offset+4,2,state) || !get(offset+6,4,mask)
            || !get(offset+10,1,polarity) || state > TsetlinMachine::kMaxState || polarity > 1) return false;
        candidate.add({id,static_cast<std::uint16_t>(state),mask,polarity==1});
        offset += 11;
    }
    out = candidate;
    return true;
}

class PrioritizedReplayTree {
public:
    static constexpr std::size_t kCapacity = 64;
    void update(std::size_t index, float priority) noexcept {
        if (index >= kCapacity || !std::isfinite(priority)) return;
        std::size_t node = index + kCapacity;
        tree_[node] = std::max(0.0f, priority);
        while (node > 1) { node /= 2; tree_[node] = tree_[node*2] + tree_[node*2+1]; }
    }
    float total() const noexcept { return tree_[1]; }
    std::size_t sample(float mass) const noexcept {
        if (!(total() > 0.0f)) return kCapacity;
        float remaining = std::clamp(mass, 0.0f, std::nextafter(total(), 0.0f));
        std::size_t node = 1;
        while (node < kCapacity) {
            const auto left = node * 2;
            if (remaining < tree_[left]) node = left;
            else { remaining -= tree_[left]; node = left + 1; }
        }
        return node - kCapacity;
    }
private:
    std::array<float, kCapacity * 2> tree_{};
};

struct OnlineEwc {
    std::array<float, 32> fisher{};
    std::array<float, 32> anchor{};
    float penalty(const std::array<float,32>& parameters, float lambda) const noexcept {
        if (!std::isfinite(lambda) || lambda < 0.0f) return 0.0f;
        double sum = 0.0;
        for (std::size_t i=0;i<parameters.size();++i) {
            const float d=parameters[i]-anchor[i];
            sum += static_cast<double>(std::max(0.0f,fisher[i]))*d*d;
        }
        return static_cast<float>(std::min<double>(lambda*sum, 1.0e12));
    }
    void consolidate(const std::array<float,32>& parameters, const std::array<float,32>& newFisher,
                     float decay = 0.95f) noexcept {
        decay = std::clamp(std::isfinite(decay)?decay:0.95f,0.0f,1.0f);
        for(std::size_t i=0;i<parameters.size();++i) {
            anchor[i]=std::isfinite(parameters[i])?parameters[i]:anchor[i];
            fisher[i]=decay*std::max(0.0f,fisher[i])+(1.0f-decay)*std::max(0.0f,newFisher[i]);
        }
    }
};

struct Signature256 { std::array<std::uint64_t,4> words{}; };
inline unsigned hammingDistance(const Signature256& a,const Signature256& b) noexcept {
    unsigned distance=0;
    for(std::size_t i=0;i<4;++i) distance += static_cast<unsigned>(__builtin_popcountll(a.words[i]^b.words[i]));
    return distance;
}
inline float reciprocalRankFusion(unsigned rankA,unsigned rankB,unsigned k=60) noexcept {
    return 1.0f/(static_cast<float>(k)+static_cast<float>(rankA)+1.0f)
         + 1.0f/(static_cast<float>(k)+static_cast<float>(rankB)+1.0f);
}
inline float hawkesDecay(float intensity,float dt,float decay,float excitation) noexcept {
    if(!std::isfinite(intensity)||!std::isfinite(dt)||!std::isfinite(decay)||!std::isfinite(excitation)) return 0.0f;
    const float safeDt=std::clamp(dt,0.0f,60.0f);
    const float safeDecay=std::clamp(decay,0.0f,100.0f);
    return std::clamp(intensity*std::exp(-safeDecay*safeDt)+std::max(0.0f,excitation),0.0f,1.0e6f);
}

struct CoreSelf final {
    const std::array<float,8> identity;
    explicit CoreSelf(std::array<float,8> values) : identity(values) {}
    CoreSelf(const CoreSelf&) = default;
    CoreSelf& operator=(const CoreSelf&) = delete;
};
struct PianoCandidate { float utility=0.0f; float epistemic=0.0f; float identityAlignment=0.0f; };
inline std::array<std::size_t,3> pianoTop3(const std::array<PianoCandidate,8>& candidates,
                                          const CoreSelf& self) noexcept {
    std::array<std::size_t,3> best{8,8,8};
    std::array<float,3> scores{-1.0e30f,-1.0e30f,-1.0e30f};
    float identityNorm=0.0f;
    for(float v:self.identity) identityNorm += v*v;
    for(std::size_t i=0;i<candidates.size();++i) {
        const auto& c=candidates[i];
        const float score=c.utility+0.25f*c.epistemic+0.5f*c.identityAlignment*std::sqrt(identityNorm);
        for(std::size_t slot=0;slot<3;++slot) if(score>scores[slot]) {
            for(std::size_t j=2;j>slot;--j){scores[j]=scores[j-1];best[j]=best[j-1];}
            scores[slot]=score;best[slot]=i;break;
        }
    }
    return best;
}
inline float expectedFreeEnergy(const PianoCandidate& policy,const CoreSelf& self) noexcept {
    float norm=0.0f; for(float v:self.identity) norm += v*v;
    const float identityPenalty=std::clamp(1.0f-policy.identityAlignment*std::sqrt(norm),0.0f,1.0f);
    return std::clamp(0.45f*(1.0f-policy.utility)+0.30f*(1.0f-policy.epistemic)+0.25f*identityPenalty,0.0f,1.0f);
}

} // namespace extended
