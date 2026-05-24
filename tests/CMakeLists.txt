include(FetchContent)
FetchContent_Declare(
    googletest
    GIT_REPOSITORY https://github.com/google/googletest.git
    GIT_TAG        v1.14.0
    GIT_SHALLOW    TRUE
)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

add_executable(hyperion_tests
    test_frame_processor.cpp
    test_flatbuffers.cpp
    test_black_bars.cpp
    test_hyperion_client.cpp
)

target_link_libraries(hyperion_tests PRIVATE hyperion_core GTest::gtest_main)

include(GoogleTest)
gtest_discover_tests(hyperion_tests)
