cmake_minimum_required(VERSION 3.22.1)

project(caption_engine LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp)
set(GGML_ROOT ${WHISPER_ROOT}/ggml)
set(GGML_SRC ${GGML_ROOT}/src)
set(FVAD_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libfvad)

# Keep the vendored build CPU-only.  The app can later add a platform-specific
# delegate without changing the Kotlin API or the model package format.
set(GGML_COMMON_SOURCES
    ${GGML_SRC}/ggml.c
    ${GGML_SRC}/ggml.cpp
    ${GGML_SRC}/ggml-alloc.c
    ${GGML_SRC}/ggml-backend.cpp
    ${GGML_SRC}/ggml-backend-dl.cpp
    ${GGML_SRC}/ggml-backend-meta.cpp
    ${GGML_SRC}/ggml-backend-reg.cpp
    ${GGML_SRC}/ggml-opt.cpp
    ${GGML_SRC}/ggml-quants.c
    ${GGML_SRC}/ggml-threading.cpp
    ${GGML_SRC}/gguf.cpp
)

set(GGML_CPU_SOURCES
    ${GGML_SRC}/ggml-cpu/ggml-cpu.c
    ${GGML_SRC}/ggml-cpu/ggml-cpu.cpp
    ${GGML_SRC}/ggml-cpu/repack.cpp
    ${GGML_SRC}/ggml-cpu/hbm.cpp
    ${GGML_SRC}/ggml-cpu/quants.c
    ${GGML_SRC}/ggml-cpu/traits.cpp
    ${GGML_SRC}/ggml-cpu/binary-ops.cpp
    ${GGML_SRC}/ggml-cpu/unary-ops.cpp
    ${GGML_SRC}/ggml-cpu/vec.cpp
    ${GGML_SRC}/ggml-cpu/ops.cpp
)

if (ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    list(APPEND GGML_CPU_SOURCES
        ${GGML_SRC}/ggml-cpu/arch/arm/quants.c
        ${GGML_SRC}/ggml-cpu/arch/arm/repack.cpp
    )
elseif (ANDROID_ABI STREQUAL "x86_64" OR ANDROID_ABI STREQUAL "x86")
    list(APPEND GGML_CPU_SOURCES
        ${GGML_SRC}/ggml-cpu/arch/x86/quants.c
        ${GGML_SRC}/ggml-cpu/arch/x86/repack.cpp
    )
else ()
    message(FATAL_ERROR "Unsupported Android ABI for caption engine: ${ANDROID_ABI}")
endif ()

add_library(ggml STATIC ${GGML_COMMON_SOURCES} ${GGML_CPU_SOURCES})
target_include_directories(ggml
    PUBLIC
        ${GGML_ROOT}/include
    PRIVATE
        ${GGML_SRC}
        ${GGML_SRC}/ggml-cpu
)
target_compile_definitions(ggml PRIVATE
    GGML_USE_CPU
    GGML_VERSION=\"0.18.1\"
    GGML_COMMIT=\"unknown\"
)
target_compile_options(ggml PRIVATE -ffunction-sections -fdata-sections)

add_library(whisper STATIC
    ${WHISPER_ROOT}/src/whisper.cpp
)
target_include_directories(whisper
    PUBLIC
        ${WHISPER_ROOT}/include
        ${GGML_ROOT}/include
    PRIVATE
        ${WHISPER_ROOT}/src
        ${GGML_SRC}
        ${GGML_SRC}/ggml-cpu
)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION=\"1.9.2\" GGML_USE_CPU)
target_compile_options(whisper PRIVATE -ffunction-sections -fdata-sections)
target_link_libraries(whisper PUBLIC ggml)

add_library(caption_engine SHARED
    caption_engine_jni.cpp
    silero_vad_jni.cpp
)
target_include_directories(caption_engine PRIVATE
    ${WHISPER_ROOT}/include
    ${GGML_ROOT}/include
)
target_compile_definitions(caption_engine PRIVATE GGML_USE_CPU)
target_compile_options(caption_engine PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
)
target_link_options(caption_engine PRIVATE -Wl,--gc-sections)
target_link_libraries(caption_engine PRIVATE whisper ggml log android dl m)

# Keep the speech activity classifier in its own small shared object.  The
# recorder can load it without loading the Whisper runtime or its larger
# dependency graph.
set(FVAD_SOURCES
    ${FVAD_ROOT}/src/fvad.c
    ${FVAD_ROOT}/src/signal_processing/division_operations.c
    ${FVAD_ROOT}/src/signal_processing/energy.c
    ${FVAD_ROOT}/src/signal_processing/get_scaling_square.c
    ${FVAD_ROOT}/src/signal_processing/resample_48khz.c
    ${FVAD_ROOT}/src/signal_processing/resample_by_2_internal.c
    ${FVAD_ROOT}/src/signal_processing/resample_fractional.c
    ${FVAD_ROOT}/src/signal_processing/spl_inl.c
    ${FVAD_ROOT}/src/vad/vad_core.c
    ${FVAD_ROOT}/src/vad/vad_filterbank.c
    ${FVAD_ROOT}/src/vad/vad_gmm.c
    ${FVAD_ROOT}/src/vad/vad_sp.c
)

add_library(speech_vad SHARED
    speech_vad_jni.cpp
    ${FVAD_SOURCES}
)
target_include_directories(speech_vad PRIVATE
    ${FVAD_ROOT}/include
    ${FVAD_ROOT}/src
    ${FVAD_ROOT}/src/signal_processing
    ${FVAD_ROOT}/src/vad
)
target_compile_options(speech_vad PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
)
target_link_options(speech_vad PRIVATE -Wl,--gc-sections)
target_link_libraries(speech_vad PRIVATE log m)
