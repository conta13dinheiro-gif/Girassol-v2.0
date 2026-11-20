#pragma once

#include <cstdint>
#include <dlfcn.h>
#include <unistd.h>
#include <sys/auxv.h>

#if defined(__has_include)
#  if __has_include(<asm/hwcap.h>)
#    include <asm/hwcap.h>
#  endif
#endif

namespace girassol::hardware {

enum Feature : std::uint64_t {
    kNeon = 1ULL << 0,
    kDotProduct = 1ULL << 1,
    kI8mm = 1ULL << 2,
    kSve2 = 1ULL << 3,
    kOpenClLoader = 1ULL << 4,
    kVulkanLoader = 1ULL << 5
};

struct SocProbeResult {
    std::uint64_t hwcap = 0;
    std::uint64_t hwcap2 = 0;
    std::uint64_t features = 0;
    std::uint32_t onlineCpuCount = 1;
};

inline bool hasSymbol(const char* library, const char* symbol) noexcept {
    void* handle = dlopen(library, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) return false;
    const bool found = dlsym(handle, symbol) != nullptr;
    dlclose(handle);
    return found;
}

/**
 * Read-only capability probe. It never changes CPU affinity or governor state.
 * Backend availability is only a loader/symbol check; actual device enumeration
 * and inference validation must still happen before selecting an accelerator.
 */
inline SocProbeResult probeSoc() noexcept {
    SocProbeResult result{};
    result.hwcap = getauxval(AT_HWCAP);
#ifdef AT_HWCAP2
    result.hwcap2 = getauxval(AT_HWCAP2);
#endif
    const long cpuCount = sysconf(_SC_NPROCESSORS_ONLN);
    if (cpuCount > 0) result.onlineCpuCount = static_cast<std::uint32_t>(cpuCount);

#if defined(__aarch64__)
#  if defined(HWCAP_ASIMD)
    if ((result.hwcap & HWCAP_ASIMD) != 0) result.features |= kNeon;
#  endif
#  if defined(HWCAP_ASIMDDP)
    if ((result.hwcap & HWCAP_ASIMDDP) != 0) result.features |= kDotProduct;
#  endif
#  if defined(HWCAP2_I8MM)
    if ((result.hwcap2 & HWCAP2_I8MM) != 0) result.features |= kI8mm;
#  endif
#  if defined(HWCAP2_SVE2)
    if ((result.hwcap2 & HWCAP2_SVE2) != 0) result.features |= kSve2;
#  endif
#elif defined(__arm__)
#  if defined(HWCAP_NEON)
    if ((result.hwcap & HWCAP_NEON) != 0) result.features |= kNeon;
#  endif
#endif

    if (hasSymbol("libOpenCL.so", "clGetPlatformIDs")) result.features |= kOpenClLoader;
    if (hasSymbol("libvulkan.so", "vkGetInstanceProcAddr")) result.features |= kVulkanLoader;
    return result;
}

} // namespace girassol::hardware
