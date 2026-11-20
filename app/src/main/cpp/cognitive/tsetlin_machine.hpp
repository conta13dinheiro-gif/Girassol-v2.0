#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <random>
#include <sstream>
#include <string>
#include <vector>

namespace girassol::tsetlin {

class PersistentTsetlinMachine {
public:
    PersistentTsetlinMachine(
        std::uint32_t features,
        std::uint32_t clauses,
        std::int32_t states = 64,
        float specificity = 3.5f,
        std::int32_t threshold = 8,
        std::uint64_t seed = 42
    ) : features_(features), clauses_(clauses), states_(states), specificity_(specificity),
        threshold_(threshold), rng_(seed) {
        validateConfiguration(features_, clauses_, states_, specificity_, threshold_);
        const auto count = static_cast<std::size_t>(clauses_) * features_ * 2U;
        automata_.assign(count, states_);
        polarity_.resize(clauses_);
        for (std::uint32_t i = 0; i < clauses_; ++i) polarity_[i] = (i % 2U == 0U) ? 1 : -1;
    }

    bool valid() const noexcept {
        if (features_ == 0 || clauses_ == 0 || states_ < 2 || threshold_ < 1 ||
            !std::isfinite(specificity_) || specificity_ <= 1.0f) return false;
        if (automata_.size() != static_cast<std::size_t>(clauses_) * features_ * 2U ||
            polarity_.size() != clauses_) return false;
        for (std::size_t i = 0; i < automata_.size(); ++i) {
            if (automata_[i] < 1 || automata_[i] > states_ * 2) return false;
        }
        for (std::uint32_t i = 0; i < clauses_; ++i) {
            if (polarity_[i] != ((i % 2U == 0U) ? 1 : -1)) return false;
        }
        return true;
    }

    bool evaluateClause(std::uint32_t clause, const std::vector<std::uint8_t>& input) const noexcept {
        if (clause >= clauses_ || input.size() != features_) return false;
        const auto offset = static_cast<std::size_t>(clause) * features_ * 2U;
        bool included = false;
        for (std::uint32_t f = 0; f < features_; ++f) {
            if (input[f] > 1U) return false;
            const bool positive = automata_[offset + f] > states_;
            const bool negative = automata_[offset + features_ + f] > states_;
            if (positive) {
                included = true;
                if (input[f] == 0U) return false;
            }
            if (negative) {
                included = true;
                if (input[f] != 0U) return false;
            }
        }
        return included;
    }

    // Early exit preserves the sign of the exact vote once no remaining clause
    // can reverse the threshold decision. The returned magnitude is a saturated
    // confidence marker, not the exact full vote after that point.
    std::int32_t predictVoteEarlyExit(const std::vector<std::uint8_t>& input) const noexcept {
        if (input.size() != features_ ||
            !std::all_of(input.begin(), input.end(), [](std::uint8_t v) { return v <= 1U; })) return 0;
        std::int32_t vote = 0;
        std::int32_t remaining = static_cast<std::int32_t>(clauses_);
        for (std::uint32_t c = 0; c < clauses_; ++c) {
            if (evaluateClause(c, input)) vote += polarity_[c];
            --remaining;
            if (std::abs(vote) > threshold_ + remaining) {
                return vote > 0 ? threshold_ + 1 : -(threshold_ + 1);
            }
        }
        return vote;
    }

    float confidence(const std::vector<std::uint8_t>& input) const noexcept {
        const auto vote = std::clamp(predictVoteEarlyExit(input), -threshold_, threshold_);
        return static_cast<float>(std::abs(vote)) / static_cast<float>(threshold_);
    }

    void trainStep(const std::vector<std::uint8_t>& input, bool targetPositive) {
        if (input.size() != features_ ||
            !std::all_of(input.begin(), input.end(), [](std::uint8_t v) { return v <= 1U; })) {
            return;
        }
        const auto vote = std::clamp(predictVoteEarlyExit(input), -threshold_, threshold_);
        const auto target = targetPositive ? 1 : -1;
        const float updateProbability = static_cast<float>(threshold_ - target * vote) /
                                        static_cast<float>(2 * threshold_);
        std::uniform_real_distribution<float> unit(0.0f, 1.0f);
        for (std::uint32_t c = 0; c < clauses_; ++c) {
            if (unit(rng_) >= updateProbability) continue;
            const bool matches = evaluateClause(c, input);
            if (polarity_[c] == target) {
                feedbackTypeI(c, input, matches, unit);
            } else if (matches) {
                feedbackTypeII(c, input);
            }
        }
    }

    std::vector<std::uint8_t> serializeBinary() const {
        if (!valid()) return {};
        std::ostringstream state;
        state << rng_;
        const auto rngState = state.str();
        if (rngState.size() > 8192U) return {};
        std::vector<std::uint8_t> out;
        out.reserve(32U + rngState.size() + polarity_.size() + automata_.size() * 4U);
        out.insert(out.end(), {'G','R','S','T'});
        writeU32(out, 1U);
        writeU32(out, features_);
        writeU32(out, clauses_);
        writeU32(out, static_cast<std::uint32_t>(states_));
        writeU32(out, static_cast<std::uint32_t>(threshold_));
        writeU32(out, floatBits(specificity_));
        writeU32(out, static_cast<std::uint32_t>(rngState.size()));
        out.insert(out.end(), rngState.begin(), rngState.end());
        for (auto p : polarity_) out.push_back(static_cast<std::uint8_t>(p > 0 ? 1 : 0));
        for (auto stateValue : automata_) writeU32(out, static_cast<std::uint32_t>(stateValue));
        return out;
    }

    bool deserializeBinary(const std::vector<std::uint8_t>& blob) {
        constexpr std::size_t header = 4U + 7U * 4U;
        constexpr std::size_t maxBytes = 1024U * 1024U;
        if (blob.size() < header || blob.size() > maxBytes ||
            blob[0] != 'G' || blob[1] != 'R' || blob[2] != 'S' || blob[3] != 'T') return false;
        std::size_t pos = 4U;
        std::uint32_t version=0, features=0, clauses=0, states=0, threshold=0, specificityBits=0, rngLength=0;
        if (!readU32(blob,pos,version) || !readU32(blob,pos,features) || !readU32(blob,pos,clauses) ||
            !readU32(blob,pos,states) || !readU32(blob,pos,threshold) ||
            !readU32(blob,pos,specificityBits) || !readU32(blob,pos,rngLength) || version != 1U ||
            rngLength > 8192U || rngLength > blob.size() - pos) return false;
        const float specificity = bitsFloat(specificityBits);
        if (features == 0 || clauses == 0 || features > 4096U || clauses > 4096U ||
            states < 2U || states > 32768U || threshold == 0U ||
            threshold > static_cast<std::uint32_t>(std::numeric_limits<std::int32_t>::max()) ||
            !std::isfinite(specificity) || specificity <= 1.0f || specificity > 100.0f) return false;
        const std::uint64_t automataCount = static_cast<std::uint64_t>(features) * clauses * 2U;
        const std::uint64_t expected = static_cast<std::uint64_t>(pos) + rngLength + clauses + automataCount * 4U;
        if (automataCount > 4U * 1024U * 1024U || expected != blob.size()) return false;
        const std::string rngText(blob.begin() + static_cast<std::ptrdiff_t>(pos),
                                  blob.begin() + static_cast<std::ptrdiff_t>(pos + rngLength));
        pos += rngLength;
        std::mt19937_64 restoredRng;
        std::istringstream rngStream(rngText);
        if (!(rngStream >> restoredRng)) return false;
        std::vector<std::int8_t> polarity(clauses);
        for (std::uint32_t i = 0; i < clauses; ++i) {
            if (blob[pos] > 1U || static_cast<std::int8_t>(blob[pos] == 1U ? 1 : -1) != ((i % 2U == 0U) ? 1 : -1)) return false;
            polarity[i] = blob[pos++] == 1U ? 1 : -1;
        }
        std::vector<std::int32_t> automata(static_cast<std::size_t>(automataCount));
        for (auto& value : automata) {
            std::uint32_t raw=0;
            if (!readU32(blob,pos,raw) || raw < 1U || raw > states * 2U) return false;
            value = static_cast<std::int32_t>(raw);
        }
        features_ = features;
        clauses_ = clauses;
        states_ = static_cast<std::int32_t>(states);
        threshold_ = static_cast<std::int32_t>(threshold);
        specificity_ = specificity;
        rng_ = restoredRng;
        polarity_ = std::move(polarity);
        automata_ = std::move(automata);
        return valid();
    }

    std::uint32_t numFeatures() const noexcept { return features_; }
    std::uint32_t numClauses() const noexcept { return clauses_; }

private:
    static void validateConfiguration(std::uint32_t features, std::uint32_t clauses,
                                      std::int32_t states, float specificity, std::int32_t threshold) {
        if (features == 0 || features > 4096U || clauses == 0 || clauses > 4096U ||
            states < 2 || states > 32768 || !std::isfinite(specificity) ||
            specificity <= 1.0f || specificity > 100.0f || threshold < 1 ||
            threshold > static_cast<std::int32_t>(clauses)) {
            throw std::invalid_argument("Invalid Tsetlin configuration");
        }
        const std::uint64_t count = static_cast<std::uint64_t>(features) * clauses * 2U;
        if (count > 4U * 1024U * 1024U) throw std::invalid_argument("Tsetlin model exceeds memory budget");
    }

    static std::uint32_t floatBits(float value) noexcept {
        std::uint32_t bits=0; std::memcpy(&bits,&value,sizeof(bits)); return bits;
    }
    static float bitsFloat(std::uint32_t bits) noexcept {
        float value=0; std::memcpy(&value,&bits,sizeof(value)); return value;
    }
    static void writeU32(std::vector<std::uint8_t>& out, std::uint32_t value) {
        for (unsigned shift=0; shift<32; shift+=8) out.push_back(static_cast<std::uint8_t>((value >> shift) & 0xffU));
    }
    static bool readU32(const std::vector<std::uint8_t>& in, std::size_t& pos, std::uint32_t& value) noexcept {
        if (pos > in.size() || in.size() - pos < 4U) return false;
        value = 0;
        for (unsigned shift=0; shift<32; shift+=8) value |= static_cast<std::uint32_t>(in[pos++]) << shift;
        return true;
    }
    template<class Distribution>
    void feedbackTypeI(std::uint32_t clause, const std::vector<std::uint8_t>& input,
                       bool matches, Distribution& unit) {
        const auto offset = static_cast<std::size_t>(clause) * features_ * 2U;
        const float reinforce = (specificity_ - 1.0f) / specificity_;
        const float forget = 1.0f / specificity_;
        const auto maximum = states_ * 2;
        for (std::uint32_t f=0; f<features_; ++f) {
            auto& positive = automata_[offset + f];
            auto& negative = automata_[offset + features_ + f];
            if (matches && input[f] == 1U) {
                if (unit(rng_) < reinforce && positive < maximum) ++positive;
                if (unit(rng_) < forget && negative > 1) --negative;
            } else if (matches) {
                if (unit(rng_) < reinforce && negative < maximum) ++negative;
                if (unit(rng_) < forget && positive > 1) --positive;
            } else {
                if (unit(rng_) < forget && positive > 1) --positive;
                if (unit(rng_) < forget && negative > 1) --negative;
            }
        }
    }
    void feedbackTypeII(std::uint32_t clause, const std::vector<std::uint8_t>& input) noexcept {
        const auto offset = static_cast<std::size_t>(clause) * features_ * 2U;
        for (std::uint32_t f=0; f<features_; ++f) {
            auto& positive = automata_[offset + f];
            auto& negative = automata_[offset + features_ + f];
            if (input[f] == 0U && positive <= states_) ++positive;
            if (input[f] == 1U && negative <= states_) ++negative;
        }
    }

    std::uint32_t features_;
    std::uint32_t clauses_;
    std::int32_t states_;
    float specificity_;
    std::int32_t threshold_;
    std::mt19937_64 rng_;
    std::vector<std::int32_t> automata_;
    std::vector<std::int8_t> polarity_;
};

} // namespace girassol::tsetlin
