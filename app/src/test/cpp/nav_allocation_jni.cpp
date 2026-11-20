#include <atomic>
#include <cstddef>
#include <cstdlib>
#include <new>

// Host linker fault injection only. Production code and Android binaries are unchanged.
extern "C" void* __real__Znam(std::size_t bytes);
extern "C" void* __wrap__Znam(std::size_t bytes) {
    static std::atomic<bool> failed{false};
    if(bytes==32*1024*1024 && std::getenv("GIRASSOL_TEST_NAV_OOM") && !failed.exchange(true)) throw std::bad_alloc();
    return __real__Znam(bytes);
}
