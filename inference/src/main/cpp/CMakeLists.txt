cmake_minimum_required(VERSION 3.22.1)
project("pocketfinancer_llm")

# ── llama.cpp ────────────────────────────────────────────────────────────────
# FetchContent pulls llama.cpp at a pinned commit so builds are reproducible.
include(FetchContent)

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        b4799
    GIT_SHALLOW    TRUE
)

# Prevent llama.cpp from building examples / server / tests (we only need libllama)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "")
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "")
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "")
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "")
set(LLAMA_STANDALONE     ON  CACHE BOOL "")

# CPU-only build for Android. Metal / CUDA / Vulkan / OpenCL / BLAS / RPC all off.
set(GGML_METAL  OFF CACHE BOOL "")
set(GGML_CUDA   OFF CACHE BOOL "")
set(GGML_VULKAN OFF CACHE BOOL "")
set(GGML_OPENCL OFF CACHE BOOL "")
set(GGML_BLAS   OFF CACHE BOOL "")
set(GGML_RPC    OFF CACHE BOOL "")
set(LLAMA_CURL  OFF CACHE BOOL "")

FetchContent_MakeAvailable(llama_cpp)

# ── JNI bridge ───────────────────────────────────────────────────────────────
add_library(pocketfinancer_llm SHARED llama_jni.cpp)

target_include_directories(pocketfinancer_llm PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/common
)

target_link_libraries(pocketfinancer_llm
    llama
    ggml
    ggml-base
    log
)
