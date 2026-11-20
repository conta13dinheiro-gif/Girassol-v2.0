#include "mind/vector_memory.hpp"
#include "mind/semantic_index.hpp"
#include <array>
#include <cassert>
#include <cstdlib>
#include <new>
#include <iostream>

extern "C" void* __real_malloc(std::size_t);
extern "C" void* __real_calloc(std::size_t, std::size_t);
namespace {
long countdown = -1;
bool fail() {
    if (countdown < 0) return false;
    if (countdown-- == 0) return true;
    return false;
}
}
extern "C" void* __wrap_malloc(std::size_t size) { return fail() ? nullptr : __real_malloc(size); }
extern "C" void* __wrap_calloc(std::size_t n, std::size_t size) { return fail() ? nullptr : __real_calloc(n, size); }
void* operator new(std::size_t size) {
    if (fail()) throw std::bad_alloc();
    if (void* p = __real_malloc(size ? size : 1)) return p;
    throw std::bad_alloc();
}
void* operator new[](std::size_t size) { return ::operator new(size); }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }
void operator delete[](void* p, std::size_t) noexcept { std::free(p); }

template<class F> void exhaust(F operation) {
    int failures = 0;
    for (long index = 0; index < 4096; ++index) {
        countdown = index;
        bool succeeded = false;
        try { operation(); succeeded = true; }
        catch (const std::bad_alloc&) { ++failures; }
        countdown = -1;
        operation(); // Each failed operation must leave the process usable.
        if (succeeded) { assert(failures > 10); std::cout << failures << " allocation failure positions recovered\n"; return; }
    }
    assert(false && "allocation injection did not converge");
}
int main() {
    std::array<float,16> query{}; query[0] = 1;
    std::array<girassol::memory::Entry,3> rows{};
    for (auto& row : rows) row = {query, 0, .5f};
    std::array<girassol::memory::Hit,3> hits{};
    exhaust([&] { assert(girassol::memory::recall(query, rows, hits) == 3); });
    std::array<float,48> embeddings{};
    embeddings[0] = embeddings[16] = embeddings[32] = 1;
    std::array<girassol::semantic::Hit,3> semanticHits{};
    exhaust([&] { assert(girassol::semantic::search(query, embeddings, 16, semanticHits) == 3); });
}
