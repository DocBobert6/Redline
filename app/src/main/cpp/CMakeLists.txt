# Redline native module (B20, B21, B22): libredline_native.so. Built by AGP's externalNativeBuild with the NDK pinned in
# app/build.gradle.kts (ndkVersion) and the CI workflows. Halide is deliberately not added yet.
cmake_minimum_required(VERSION 3.22.1)
project(redline_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(redline_native SHARED
    cube_parser.cpp
    raw_pipeline.cpp
    burst_merge.cpp
    redline_jni.cpp)

# B21 renders straight into an android.graphics.Bitmap.
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(redline_native PRIVATE ${jnigraphics-lib})

target_compile_options(redline_native PRIVATE -Wall -Wextra -Werror -fvisibility=hidden -O2)
# B22: the burst merge's inner loops (tile distances, quad blending) vectorise at -O3.
set_source_files_properties(burst_merge.cpp PROPERTIES COMPILE_OPTIONS "-O3")
# 16 KB page alignment (Android 15+ devices with 16 KB pages).
target_link_options(redline_native PRIVATE "-Wl,-z,max-page-size=16384")
