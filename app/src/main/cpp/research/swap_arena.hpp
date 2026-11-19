#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
namespace girassol::research {
/** File-backed read arena, not OS swap. Never exposes an address beyond its scoped lifetime. */
class NonRootSwapArena {
    int fd_=-1;void* address_=nullptr;size_t length_=0;
public:
    explicit NonRootSwapArena(const std::string& path,size_t maximum=150u*1024*1024);
    ~NonRootSwapArena();
    NonRootSwapArena(const NonRootSwapArena&)=delete;
    NonRootSwapArena& operator=(const NonRootSwapArena&)=delete;
    const char* data() const noexcept { return static_cast<const char*>(address_); }
    size_t size() const noexcept { return length_; }
};
std::string extract(const std::string& path,const std::string& format,size_t offset,size_t limit=100,std::int64_t request=0);
void cancel_through(std::int64_t request) noexcept;
}
