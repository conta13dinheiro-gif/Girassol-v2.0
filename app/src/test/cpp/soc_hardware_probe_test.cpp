#include "hardware/soc_hardware_probe.hpp"

#include <cassert>
#include <cstdint>

int main() {
    const auto report = girassol::hardware::probeSoc();
    assert(report.onlineCpuCount >= 1);
    constexpr std::uint64_t known = girassol::hardware::kNeon |
        girassol::hardware::kDotProduct | girassol::hardware::kI8mm |
        girassol::hardware::kSve2 | girassol::hardware::kOpenClDevice |
        girassol::hardware::kVulkanDevice;
    assert((report.features & ~known) == 0);
    // Device flags are set only after clGetDeviceIDs/vkEnumeratePhysicalDevices succeeds.
    return 0;
}
