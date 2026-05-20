cmake_minimum_required(VERSION 3.22)
project(hyperion_grabber_android CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Paths relative to the repo root (one level up from this file)
set(ROOT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/..)

# flatbuffers headers — fetched by the host build and committed to the repo
# (core/include/generated/hyperion_generated.h + flatbuffers source headers)
# We reference the FetchContent cache populated during the host build.
# If not present, run a native cmake build first: cmake -B build && cmake --build build
set(FLATBUFFERS_INCLUDE_DIR "${ROOT_DIR}/build/_deps/flatbuffers-src/include"
    CACHE PATH "Path to flatbuffers headers (populated by host cmake build)")

if(NOT EXISTS "${FLATBUFFERS_INCLUDE_DIR}/flatbuffers/flatbuffers.h")
    message(FATAL_ERROR
        "flatbuffers headers not found at ${FLATBUFFERS_INCLUDE_DIR}.\n"
        "Run a native host build first:\n"
        "  cmake -B ${ROOT_DIR}/build ${ROOT_DIR} && cmake --build ${ROOT_DIR}/build\n"
        "Then rebuild the Android target.")
endif()

# ── Shared core library ──────────────────────────────────────────────────────
add_library(hyperion_core STATIC
    ${ROOT_DIR}/core/src/hyperion_client.cpp
    ${ROOT_DIR}/core/src/frame_processor.cpp
    ${ROOT_DIR}/core/src/grabber_base.cpp
)
target_include_directories(hyperion_core PUBLIC
    ${ROOT_DIR}/core/include
    ${ROOT_DIR}/core/include/generated
    ${FLATBUFFERS_INCLUDE_DIR}
)

# ── Android JNI shared library ───────────────────────────────────────────────
add_library(hyperion_grabber SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/app/src/main/cpp/jni_bridge.cpp
)
target_include_directories(hyperion_grabber PRIVATE
    ${ROOT_DIR}/core/include
    ${ROOT_DIR}/core/include/generated
    ${FLATBUFFERS_INCLUDE_DIR}
)
target_link_libraries(hyperion_grabber PRIVATE hyperion_core log)
