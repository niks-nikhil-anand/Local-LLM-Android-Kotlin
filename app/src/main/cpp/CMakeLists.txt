# Native inference for LocalAI: llama.cpp (pinned, see LLAMA_CPP_REVISION.md) compiled into one
# shared library with a narrow JNI surface (localai_llama.cpp). Only the public llama.h C API is
# used; llama.cpp's "common" helpers, tools, server and examples are not built.
cmake_minimum_required(VERSION 3.22)

project(localai_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_CPP_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
if(NOT EXISTS ${LLAMA_CPP_DIR}/include/llama.h)
    message(FATAL_ERROR
        "llama.cpp sources not found at ${LLAMA_CPP_DIR}. "
        "Run scripts/fetch-llama-cpp.sh to check out the pinned revision.")
endif()

# One static llama/ggml build linked into a single .so: no dynamic backend loading, no extra libs.
set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_HTML      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)  # not in the NDK stable API (upstream docs/android.md)
# Portable CPU baseline: GGML_NATIVE would target the build machine, not the phone.
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
# Upstream notes OpenMP cannot be installed as a CMake dependency for Android; ggml's own thread pool is used.
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)  # unsupported on Android per upstream docs
set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)
# GPU backends (Vulkan/OpenCL) are deliberately not built: unverified on target phones.
set(GGML_VULKAN           OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL           OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_CPP_DIR} llama.cpp EXCLUDE_FROM_ALL)

add_library(localai_llama SHARED localai_llama.cpp)
target_link_libraries(localai_llama PRIVATE llama android log)
# 16 KB page alignment for Android 15+ devices (also the NDK r28+ default).
target_link_options(localai_llama PRIVATE "-Wl,-z,max-page-size=16384")
