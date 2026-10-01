# Speech-to-text for voice replies and the assistant: whisper.cpp, pinned to one commit and
# fetched at build time, so nothing outside this folder (cloned-repos included) is needed.
cmake_minimum_required(VERSION 3.22)
project(gearslip_speech CXX C)

set(CMAKE_CXX_STANDARD 17)

# Optimised in debug builds too. Unoptimised, Whisper runs several times slower, and a dev build
# would make voice look far worse than what ships.
foreach(lang C CXX)
    set(CMAKE_${lang}_FLAGS_DEBUG "-O3 -DNDEBUG")
endforeach()

include(FetchContent)
FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/6e4ab854f67f743900934a703d5603419384c961.tar.gz
    URL_HASH SHA256=55b585aec5e1bc03e2a5cea61da97c6406fa833996cf7daeed704972273cc039
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# ARMv8.2 with half floats and dot products: every phone that runs Android 12 or later in
# practice, and what makes Whisper fast enough to answer in under a second.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
FetchContent_MakeAvailable(whisper)

add_library(gearslip_speech SHARED speech_jni.cpp)
target_compile_options(gearslip_speech PRIVATE -O3)
find_library(LOG_LIB log)
target_link_libraries(gearslip_speech whisper android ${LOG_LIB})
target_link_options(gearslip_speech PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
