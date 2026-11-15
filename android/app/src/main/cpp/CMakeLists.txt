cmake_minimum_required(VERSION 3.22)
project(v2text_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
# Vendored whisper.cpp (see third_party/whisper.cpp/VENDORED_FROM.txt)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# CPU-only static ggml, tuned for ARMv8.2 with fp16 + dot-product instructions.
# The Galaxy A52s (Snapdragon 778G: Cortex-A78/A55) supports both; the app checks
# /proc/cpuinfo at runtime and refuses to load this library on CPUs without them.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
add_subdirectory(${WHISPER_DIR}/ggml ${CMAKE_BINARY_DIR}/ggml)

add_library(v2text_whisper SHARED
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/v2text_jni.c)
target_include_directories(v2text_whisper PRIVATE
    ${WHISPER_DIR}/include ${WHISPER_DIR}/src ${WHISPER_DIR}/ggml/include)
target_compile_definitions(v2text_whisper PRIVATE GGML_USE_CPU WHISPER_VERSION="vendored")
target_compile_options(v2text_whisper PRIVATE -O3 -march=armv8.2-a+fp16+dotprod
    -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(v2text_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384)
find_library(LOG_LIB log)
target_link_libraries(v2text_whisper ${LOG_LIB} android ggml)
