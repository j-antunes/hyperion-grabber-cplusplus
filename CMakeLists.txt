cmake_minimum_required(VERSION 3.22)
project(hyperion_grabber CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── flatbuffers: flatc compiler + C++ headers ────────────────────────────────
include(FetchContent)

FetchContent_Declare(
    flatbuffers
    GIT_REPOSITORY https://github.com/google/flatbuffers.git
    GIT_TAG        v24.3.25
    GIT_SHALLOW    TRUE
)

set(FLATBUFFERS_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(FLATBUFFERS_INSTALL        OFF CACHE BOOL "" FORCE)
set(FLATBUFFERS_BUILD_FLATLIB  OFF CACHE BOOL "" FORCE)
set(FLATBUFFERS_BUILD_FLATHASH OFF CACHE BOOL "" FORCE)
set(FLATBUFFERS_BUILD_GRPCTEST OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(flatbuffers)

set(FBS_REQUEST   ${CMAKE_SOURCE_DIR}/flatbuffers/hyperion_request.fbs)
set(FBS_REPLY     ${CMAKE_SOURCE_DIR}/flatbuffers/hyperion_reply.fbs)
set(GENERATED_DIR ${CMAKE_SOURCE_DIR}/core/include/generated)
set(GENERATED_REQUEST ${GENERATED_DIR}/hyperion_request_generated.h)
set(GENERATED_REPLY   ${GENERATED_DIR}/hyperion_reply_generated.h)

file(MAKE_DIRECTORY ${GENERATED_DIR})

if(CMAKE_CROSSCOMPILING)
    # Pre-generated headers are committed to the repo and used directly.
    # To regenerate: run a native (non-cross) build first, then cross-compile.
    message(STATUS "Cross-compile: using pre-generated headers in ${GENERATED_DIR}")
    add_custom_target(hyperion_fbs)
else()
    # Native build: use the flatc we built from FetchContent
    add_custom_command(
        OUTPUT  ${GENERATED_REQUEST} ${GENERATED_REPLY}
        COMMAND flatc --cpp --scoped-enums -o ${GENERATED_DIR} ${FBS_REQUEST} ${FBS_REPLY}
        DEPENDS ${FBS_REQUEST} ${FBS_REPLY} flatc
        COMMENT "Generating flatbuffers C++ headers"
        VERBATIM
    )
    add_custom_target(hyperion_fbs DEPENDS ${GENERATED_REQUEST} ${GENERATED_REPLY})
endif()

# ── Shared core library ──────────────────────────────────────────────────────
add_library(hyperion_core STATIC
    core/src/hyperion_client.cpp
    core/src/frame_processor.cpp
    core/src/grabber_base.cpp
)
add_dependencies(hyperion_core hyperion_fbs)
target_include_directories(hyperion_core PUBLIC
    core/include
    ${GENERATED_DIR}
    ${flatbuffers_SOURCE_DIR}/include
)

# ── PC targets ───────────────────────────────────────────────────────────────
if(NOT ANDROID)
    if(UNIX AND NOT APPLE)
        find_package(X11 REQUIRED)
        add_executable(hyperion_grabber_linux
            pc/linux/x11_grabber.cpp
            pc/main.cpp
        )
        target_include_directories(hyperion_grabber_linux PRIVATE pc/linux)
        target_link_libraries(hyperion_grabber_linux PRIVATE hyperion_core X11::X11)
    endif()

    if(WIN32)
        add_executable(hyperion_grabber_windows
            pc/windows/dxgi_grabber.cpp
            pc/main.cpp
        )
        target_include_directories(hyperion_grabber_windows PRIVATE pc/windows)
        target_link_libraries(hyperion_grabber_windows PRIVATE hyperion_core d3d11 dxgi)
    endif()
endif()

# ── Unit tests (host builds only) ────────────────────────────────────────────
if(NOT ANDROID AND NOT CMAKE_CROSSCOMPILING)
    enable_testing()
    add_subdirectory(tests)
endif()

# ── Android target (used by Gradle via externalNativeBuild) ──────────────────
if(ANDROID)
    add_library(hyperion_grabber SHARED
        android/app/src/main/cpp/jni_bridge.cpp
    )
    add_dependencies(hyperion_grabber hyperion_fbs)
    target_include_directories(hyperion_grabber PRIVATE core/include ${GENERATED_DIR})
    target_link_libraries(hyperion_grabber PRIVATE hyperion_core log)
endif()
