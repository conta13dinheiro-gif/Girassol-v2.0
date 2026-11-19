# MIT dependency pinned by commit AND archive digest; no provider API or model download.
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_AVX OFF CACHE BOOL "" FORCE)
set(GGML_AVX2 OFF CACHE BOOL "" FORCE)
set(GGML_FMA OFF CACHE BOOL "" FORCE)
set(GGML_F16C OFF CACHE BOOL "" FORCE)
FetchContent_Declare(llama
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/ec7f3ac9ab33e46b136eb5ab6a76c4d81f57c7f1
    URL_HASH SHA256=21e88c79dbbec249a91b093b56f375a3248a9bbe80c5fb19d42c0849f148aa66
    TLS_VERIFY ON)
FetchContent_MakeAvailable(llama)

# b4514's llama-mmap.h omits <cstdint>; newer standard libraries no longer include it indirectly.
# Inject the missing standard header without modifying the pinned upstream source archive.
target_compile_options(llama PRIVATE -include cstdint)

# Development APKs also need an optimized CPU backend to stay within the inference budget.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    foreach(cpu_target llama ggml ggml-base ggml-cpu)
        target_compile_options(${cpu_target} PRIVATE -O2)
    endforeach()
endif()
