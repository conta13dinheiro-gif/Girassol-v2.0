#pragma once
#include <span>
namespace girassol::semantic {
struct Hit { int index;float similarity; };
// Bounded shards of full-dimensional model embeddings; callers merge top-k across shards.
int search(std::span<const float> query,std::span<const float> rows,int dimensions,std::span<Hit> output);
}
