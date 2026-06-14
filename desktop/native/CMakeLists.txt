# Standalone build for the JVM desktop app's native DXGI capture helper.
# Kept separate from the root CMake (no flatbuffers/core dependency) so CI can
# build just this one Windows DLL and drop it into the app's resources before
# jpackage runs. Linux/macOS desktop builds don't need it — they use Robot.
cmake_minimum_required(VERSION 3.22)
project(hyperion_capture_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)

add_library(hyperion_capture_jni SHARED dxgi_jni.cpp)
target_include_directories(hyperion_capture_jni PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(hyperion_capture_jni PRIVATE d3d11 dxgi)
