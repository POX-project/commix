cmake_minimum_required(VERSION 3.22.1)
project(commix_native CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Enforce maximum performance compiler flags: -O3, LTO, dead code elimination, disable exceptions & RTTI
add_compile_options(
    -O3
    -flto
    -ffunction-sections
    -fdata-sections
    -fno-exceptions
    -fno-rtti
    -fno-finite-math-only
    -funroll-loops
    -ftree-vectorize
    -Wall
    -Wextra
    -Wno-unused-parameter
)

add_compile_definitions($<$<CONFIG:Release>:NDEBUG>)

# 16KB page-size support for modern Android kernels (Android 15+)
add_link_options(
    -flto
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

# Source files
set(COMMIX_SOURCES
    commix_telemetry.cpp
    scopes_engine.cpp
    image_pipeline.cpp
    computational_photo.cpp
    stabilization_engine.cpp
    color_science.cpp
    keyframe_engine.cpp
    best_shot_engine.cpp
    jni_bridge.cpp
)

add_library(commix_native SHARED ${COMMIX_SOURCES})

target_include_directories(commix_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(commix_native
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
