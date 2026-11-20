#pragma once
#include <algorithm>
#include <cstdint>
#include <dlfcn.h>
#include <fstream>
#include <sched.h>
#include <sstream>
#include <string>
#include <utility>
#include <vector>
#include <unistd.h>
#if defined(__aarch64__) || defined(__arm__)
#include <sys/auxv.h>
#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1UL << 20)
#endif
#ifndef HWCAP2_I8MM
#define HWCAP2_I8MM (1UL << 13)
#endif
#ifndef HWCAP2_SVE2
#define HWCAP2_SVE2 (1UL << 1)
#endif
#ifndef HWCAP_NEON
#define HWCAP_NEON (1UL << 12)
#endif
#endif
#if defined(__x86_64__) || defined(__i386__)
#if __has_include(<cpuid.h>)
#include <cpuid.h>
#define GIRASSOL_HAS_CPUID_HEADER 1
#endif
#endif

namespace girassol::hw {
struct NativeHardwareCapabilities {
    std::string abi = "unknown";
    bool hasNeon = false, hasDotProd = false, hasI8mm = false, hasSve2 = false, hasAvx2 = false;
    bool openClLoaderAvailable = false, vulkanLoaderAvailable = false;
    std::vector<int> bigCoreIds, littleCoreIds;
    std::string preferredGgmlVariantOrder = "portable";
    int logicalCpuCount = 1;

    std::string toKeyValueText() const {
        const auto join = [](const std::vector<int>& values) {
            std::ostringstream out;
            for (std::size_t i=0;i<values.size();++i) { if(i) out << ','; out << values[i]; }
            return out.str();
        };
        std::ostringstream out;
        out << "abi=" << abi << '\n'
            << "neon=" << (hasNeon?"true":"false") << '\n'
            << "dotprod=" << (hasDotProd?"true":"false") << '\n'
            << "i8mm=" << (hasI8mm?"true":"false") << '\n'
            << "sve2=" << (hasSve2?"true":"false") << '\n'
            << "avx2=" << (hasAvx2?"true":"false") << '\n'
            << "opencl_loader=" << (openClLoaderAvailable?"true":"false") << '\n'
            << "vulkan_loader=" << (vulkanLoaderAvailable?"true":"false") << '\n'
            << "logical_cpus=" << logicalCpuCount << '\n'
            << "big_cores=" << join(bigCoreIds) << '\n'
            << "little_cores=" << join(littleCoreIds) << '\n'
            << "ggml_variants=" << preferredGgmlVariantOrder << '\n';
        return out.str();
    }
};

inline bool libraryExportsSymbol(const std::vector<const char*>& candidates, const char* symbol) {
    if (!symbol || !*symbol) return false;
    for (const char* path : candidates) {
        if (!path || !*path) continue;
        void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        if (!handle) continue;
        const bool found = dlsym(handle, symbol) != nullptr;
        dlclose(handle);
        if (found) return true;
    }
    return false;
}
inline long cpuMaxFrequencyKhz(int cpu) {
    if(cpu<0) return 0;
    std::ifstream input("/sys/devices/system/cpu/cpu"+std::to_string(cpu)+"/cpufreq/cpuinfo_max_freq");
    long value=0;
    return (input>>value && value>0) ? value : 0;
}
inline void detectCoreClusters(NativeHardwareCapabilities& caps) {
    const long configured=sysconf(_SC_NPROCESSORS_CONF);
    const int count=configured>0 ? static_cast<int>(std::min<long>(configured,CPU_SETSIZE)) : 1;
    const long online=sysconf(_SC_NPROCESSORS_ONLN);
    caps.logicalCpuCount=online>0 ? static_cast<int>(online) : 1;
    long maximum=0;
    std::vector<std::pair<int,long>> frequencies;
    for(int cpu=0;cpu<count;++cpu) {
        const long frequency=cpuMaxFrequencyKhz(cpu);
        if(frequency>0) { frequencies.emplace_back(cpu,frequency); maximum=std::max(maximum,frequency); }
    }
    if(maximum<=0) return;
    for(const auto& [cpu,frequency]:frequencies) {
        if(static_cast<double>(frequency)>=static_cast<double>(maximum)*0.72) caps.bigCoreIds.push_back(cpu);
        else caps.littleCoreIds.push_back(cpu);
    }
}
inline bool pinCurrentThreadToBigCores(const std::vector<int>& ids) {
    if(ids.empty()) return false;
    cpu_set_t set; CPU_ZERO(&set); bool any=false;
    for(int cpu:ids) if(cpu>=0 && cpu<CPU_SETSIZE) { CPU_SET(cpu,&set); any=true; }
    return any && sched_setaffinity(0,sizeof(set),&set)==0;
}
inline NativeHardwareCapabilities detectNativeHardware() {
    NativeHardwareCapabilities caps;
#if defined(__aarch64__)
    caps.abi="arm64-v8a"; caps.hasNeon=true;
    const unsigned long hwcap=getauxval(AT_HWCAP);
#if defined(AT_HWCAP2)
    const unsigned long hwcap2=getauxval(AT_HWCAP2);
#else
    const unsigned long hwcap2=0;
#endif
    caps.hasDotProd=(hwcap&HWCAP_ASIMDDP)!=0;
    caps.hasI8mm=(hwcap2&HWCAP2_I8MM)!=0;
    caps.hasSve2=(hwcap2&HWCAP2_SVE2)!=0;
    caps.preferredGgmlVariantOrder="android_armv8.6_1,android_armv8.2_2,android_armv8.2_1,android_armv8.0_1";
#elif defined(__arm__)
    caps.abi="armeabi-v7a"; caps.hasNeon=(getauxval(AT_HWCAP)&HWCAP_NEON)!=0;
    caps.preferredGgmlVariantOrder=caps.hasNeon?"android_armv7_neon":"portable_arm";
#elif defined(__x86_64__)
    caps.abi="x86_64"; caps.preferredGgmlVariantOrder="x86_64_avx2,x86_64_avx,x86_64_sse42";
#if defined(GIRASSOL_HAS_CPUID_HEADER)
    unsigned int eax=0,ebx=0,ecx=0,edx=0;
    const unsigned int maxLeaf=__get_cpuid_max(0,nullptr);
    bool avxStateEnabled=false;
    if(maxLeaf>=1 && __get_cpuid(1,&eax,&ebx,&ecx,&edx)) {
        constexpr unsigned int kOsXsave=1U<<27U, kAvx=1U<<28U;
        if((ecx&(kOsXsave|kAvx))==(kOsXsave|kAvx)) {
            unsigned int xcrLow=0,xcrHigh=0;
            __asm__ volatile("xgetbv":"=a"(xcrLow),"=d"(xcrHigh):"c"(0));
            (void)xcrHigh; avxStateEnabled=(xcrLow&0x6U)==0x6U;
        }
    }
    if(maxLeaf>=7 && avxStateEnabled) {
        __get_cpuid_count(7,0,&eax,&ebx,&ecx,&edx); caps.hasAvx2=(ebx&(1U<<5U))!=0;
    }
#endif
#endif
    caps.openClLoaderAvailable=libraryExportsSymbol(
        {"libOpenCL.so","/vendor/lib64/libOpenCL.so","/system/vendor/lib64/libOpenCL.so",
         "/system/lib64/libOpenCL.so","/vendor/lib/libOpenCL.so","/system/lib/libOpenCL.so"},
        "clGetPlatformIDs");
    caps.vulkanLoaderAvailable=libraryExportsSymbol(
        {"libvulkan.so","/system/lib64/libvulkan.so","/vendor/lib64/libvulkan.so",
         "/system/lib/libvulkan.so","/vendor/lib/libvulkan.so"},"vkGetInstanceProcAddr");
    detectCoreClusters(caps);
    return caps;
}
} // namespace girassol::hw
