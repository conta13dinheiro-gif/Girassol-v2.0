#include "projection.hpp"
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>

bool project_points(const void* input, std::size_t input_bytes, void* output,
                    std::size_t output_bytes, std::size_t count) noexcept {
    constexpr std::size_t stride = sizeof(float) * 2;
    if (count > 65536 || count > std::numeric_limits<std::size_t>::max() / stride) return false;
    const std::size_t bytes = count * stride;
    if (input_bytes < bytes || output_bytes < bytes) return false;
    if (count == 0) return true;
    if (!input || !output) return false;
    const auto in_addr = reinterpret_cast<std::uintptr_t>(input);
    const auto out_addr = reinterpret_cast<std::uintptr_t>(output);
    if (in_addr > std::numeric_limits<std::uintptr_t>::max() - bytes ||
        out_addr > std::numeric_limits<std::uintptr_t>::max() - bytes ||
        (in_addr < out_addr + bytes && out_addr < in_addr + bytes)) return false;
    const auto* src = static_cast<const unsigned char*>(input);
    auto* dst = static_cast<unsigned char*>(output);
    for (std::size_t i = 0; i < count; ++i) {
        float x, z;
        std::memcpy(&x, src + i * stride, sizeof(float));
        std::memcpy(&z, src + i * stride + sizeof(float), sizeof(float));
        if (!std::isfinite(x) || !std::isfinite(z) || !std::isfinite(x - z) || !std::isfinite((x + z) * .52f)) return false;
    }
    for (std::size_t i = 0; i < count; ++i) {
        float x, z;
        std::memcpy(&x, src + i * stride, sizeof(float));
        std::memcpy(&z, src + i * stride + sizeof(float), sizeof(float));
        const float projected[] = {x - z, (x + z) * .52f};
        std::memcpy(dst + i * stride, projected, stride);
    }
    return true;
}
