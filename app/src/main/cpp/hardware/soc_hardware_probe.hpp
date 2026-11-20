#pragma once

#include <algorithm>
#include <cstdint>
#include <dlfcn.h>
#include <sys/auxv.h>
#include <unistd.h>
#include <vector>

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
    kOpenClDevice = 1ULL << 4,
    kVulkanDevice = 1ULL << 5
};

struct SocProbeResult {
    std::uint64_t hwcap = 0;
    std::uint64_t hwcap2 = 0;
    std::uint64_t features = 0;
    std::uint32_t onlineCpuCount = 1;
};

/** A loader is reported only when it enumerates at least one physical compute device. */
inline bool hasOpenClDevice() noexcept {
    using ClInt = std::int32_t;
    using ClUInt = std::uint32_t;
    using ClDeviceType = std::uint64_t;
    using ClPlatform = void*;
    using ClDevice = void*;
    constexpr ClInt kSuccess = 0;
    constexpr ClDeviceType kAllDevices = ~ClDeviceType{0};
    void* library = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) return false;
    const auto getPlatforms = reinterpret_cast<ClInt (*)(ClUInt, ClPlatform*, ClUInt*)>(
        dlsym(library, "clGetPlatformIDs"));
    const auto getDevices = reinterpret_cast<ClInt (*)(ClPlatform, ClDeviceType, ClUInt, ClDevice*, ClUInt*)>(
        dlsym(library, "clGetDeviceIDs"));
    bool found = false;
    if (getPlatforms != nullptr && getDevices != nullptr) {
        ClUInt platformCount = 0;
        if (getPlatforms(0, nullptr, &platformCount) == kSuccess && platformCount > 0 && platformCount <= 64) {
            std::vector<ClPlatform> platforms(platformCount);
            if (getPlatforms(platformCount, platforms.data(), nullptr) == kSuccess) {
                for (const auto platform : platforms) {
                    ClUInt deviceCount = 0;
                    if (getDevices(platform, kAllDevices, 0, nullptr, &deviceCount) == kSuccess && deviceCount > 0) {
                        found = true;
                        break;
                    }
                }
            }
        }
    }
    dlclose(library);
    return found;
}

inline bool hasVulkanPhysicalDevice() noexcept {
    using VkResult = std::int32_t;
    using VkInstance = void*;
    using VkPhysicalDevice = void*;
    struct VkApplicationInfo {
        std::uint32_t sType;
        const void* pNext;
        const char* pApplicationName;
        std::uint32_t applicationVersion;
        const char* pEngineName;
        std::uint32_t engineVersion;
        std::uint32_t apiVersion;
    };
    struct VkInstanceCreateInfo {
        std::uint32_t sType;
        const void* pNext;
        std::uint32_t flags;
        const VkApplicationInfo* pApplicationInfo;
        std::uint32_t enabledLayerCount;
        const char* const* ppEnabledLayerNames;
        std::uint32_t enabledExtensionCount;
        const char* const* ppEnabledExtensionNames;
    };
    void* library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) return false;
    const auto createInstance = reinterpret_cast<VkResult (*)(const VkInstanceCreateInfo*, const void*, VkInstance*)>(
        dlsym(library, "vkCreateInstance"));
    const auto enumerate = reinterpret_cast<VkResult (*)(VkInstance, std::uint32_t*, VkPhysicalDevice*)>(
        dlsym(library, "vkEnumeratePhysicalDevices"));
    const auto destroy = reinterpret_cast<void (*)(VkInstance, const void*)>(
        dlsym(library, "vkDestroyInstance"));
    bool found = false;
    if (createInstance != nullptr && enumerate != nullptr && destroy != nullptr) {
        const VkApplicationInfo appInfo{0, nullptr, "GirassolProbe", 1, "Girassol", 1, 0};
        const VkInstanceCreateInfo createInfo{1, nullptr, 0, &appInfo, 0, nullptr, 0, nullptr};
        VkInstance instance = nullptr;
        if (createInstance(&createInfo, nullptr, &instance) == 0 && instance != nullptr) {
            std::uint32_t count = 0;
            if (enumerate(instance, &count, nullptr) == 0 && count > 0 && count <= 256) {
                std::vector<VkPhysicalDevice> devices(count);
                found = enumerate(instance, &count, devices.data()) == 0 && count > 0 &&
                    std::any_of(devices.begin(), devices.begin() + count, [](auto device) { return device != nullptr; });
            }
            destroy(instance, nullptr);
        }
    }
    dlclose(library);
    return found;
}

/** Read-only SoC inventory; CPU placement remains under Android's scheduler and ADPF. */
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

    if (hasOpenClDevice()) result.features |= kOpenClDevice;
    if (hasVulkanPhysicalDevice()) result.features |= kVulkanDevice;
    return result;
}

} // namespace girassol::hardware
