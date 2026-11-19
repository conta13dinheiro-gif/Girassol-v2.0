#include "swap_arena.hpp"
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdexcept>
namespace girassol::research {
NonRootSwapArena::NonRootSwapArena(const std::string& path,size_t maximum) {
    fd_=open(path.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(fd_<0) throw std::runtime_error("Não foi possível abrir a arena de dados");
    struct stat status{};
    if(fstat(fd_,&status)!=0 || !S_ISREG(status.st_mode) || status.st_size<=0 || static_cast<unsigned long long>(status.st_size)>maximum) {
        close(fd_);fd_=-1;throw std::runtime_error("Arquivo vazio, não regular ou acima do orçamento");
    }
    length_=static_cast<size_t>(status.st_size);
    address_=mmap(nullptr,length_,PROT_READ,MAP_PRIVATE,fd_,0);
    if(address_==MAP_FAILED) { address_=nullptr;close(fd_);fd_=-1;throw std::runtime_error("Mapeamento de dados indisponível"); }
    madvise(address_,length_,MADV_SEQUENTIAL);
}
NonRootSwapArena::~NonRootSwapArena() { if(address_) munmap(address_,length_);if(fd_>=0) close(fd_); }
}
