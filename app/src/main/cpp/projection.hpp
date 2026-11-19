#pragma once
#include <cstddef>

// Interleaved x/z to interleaved isometric x/y. Does not own or mutate world state.
bool project_points(const void* input, std::size_t input_bytes, void* output,
                    std::size_t output_bytes, std::size_t count) noexcept;
