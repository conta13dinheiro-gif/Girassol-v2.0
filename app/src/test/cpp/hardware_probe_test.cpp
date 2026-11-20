#include "hardware/soc_hardware_probe.hpp"
#include <cassert>
#include <iostream>
#include <set>
int main() {
    using namespace girassol::hw;
    const auto report=detectNativeHardware();
    assert(!report.abi.empty());
    assert(report.logicalCpuCount>=1);
    assert(!report.preferredGgmlVariantOrder.empty());
    std::set<int> assigned;
    for(int cpu:report.bigCoreIds) { assert(cpu>=0 && cpu<CPU_SETSIZE); assert(assigned.insert(cpu).second); }
    for(int cpu:report.littleCoreIds) { assert(cpu>=0 && cpu<CPU_SETSIZE); assert(assigned.insert(cpu).second); }
    const auto text=report.toKeyValueText();
    assert(text.find("abi=")==0);
    assert(text.find("opencl_loader=")!=std::string::npos);
    assert(text.find("vulkan_loader=")!=std::string::npos);
    assert(text.find("big_cores=")!=std::string::npos);
    assert(!libraryExportsSymbol({},"missing"));
    assert(!pinCurrentThreadToBigCores({}));
    std::cout<<"Native CPU feature, loader discovery and core-cluster probe passed\n";
}
