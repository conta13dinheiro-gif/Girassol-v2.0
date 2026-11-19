#pragma once
#include <array>
#include <span>
namespace girassol::memory {
constexpr int dimensions=16;
struct Entry { std::array<float,dimensions> embedding; float age; float importance; };
struct Hit { int index; float score; };
// Unit-normalized observational embeddings; no language-model embeddings are implied.
// Returns -1 for invalid input. Output is untouched on failure.
int recall(const std::array<float,dimensions>& query,std::span<const Entry> entries,std::span<Hit> output);
}
