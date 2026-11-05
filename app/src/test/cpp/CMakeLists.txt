# Host builds of the native tests (B20 parser, B21 RAW pipeline). CI runs them in the "Build, lint, unit tests" job;
# the calibration job builds raw_pipeline_tool for the Python parity test.
cmake_minimum_required(VERSION 3.20)
project(redline_native_host_test LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(NATIVE_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)
add_executable(cube_parser_test cube_parser_test.cpp ${NATIVE_SRC}/cube_parser.cpp)
target_include_directories(cube_parser_test PRIVATE ${NATIVE_SRC})
target_compile_options(cube_parser_test PRIVATE -Wall -Wextra -Werror)

enable_testing()
add_test(NAME cube_parser COMMAND cube_parser_test
    ${CMAKE_CURRENT_SOURCE_DIR}/../../main/assets/luts/redline_standard_preview_33.cube)

# B21 RAW colour pipeline: invariants (CTest) and the host tool the Python parity test drives.
add_executable(raw_pipeline_test raw_pipeline_test.cpp ${NATIVE_SRC}/raw_pipeline.cpp)
target_include_directories(raw_pipeline_test PRIVATE ${NATIVE_SRC})
target_compile_options(raw_pipeline_test PRIVATE -Wall -Wextra -Werror)
find_package(Threads REQUIRED)
target_link_libraries(raw_pipeline_test PRIVATE Threads::Threads)
add_test(NAME raw_pipeline COMMAND raw_pipeline_test)

add_executable(raw_pipeline_tool raw_pipeline_tool.cpp ${NATIVE_SRC}/raw_pipeline.cpp ${NATIVE_SRC}/cube_parser.cpp)
target_include_directories(raw_pipeline_tool PRIVATE ${NATIVE_SRC})
target_compile_options(raw_pipeline_tool PRIVATE -Wall -Wextra -Werror)
target_link_libraries(raw_pipeline_tool PRIVATE Threads::Threads)

# B22 burst align + merge: invariants and the self-check (CTest), and the host tool the Python parity test drives.
add_executable(burst_merge_test burst_merge_test.cpp ${NATIVE_SRC}/burst_merge.cpp)
target_include_directories(burst_merge_test PRIVATE ${NATIVE_SRC})
target_compile_options(burst_merge_test PRIVATE -Wall -Wextra -Werror)
target_link_libraries(burst_merge_test PRIVATE Threads::Threads)
add_test(NAME burst_merge COMMAND burst_merge_test)

add_executable(burst_merge_tool burst_merge_tool.cpp ${NATIVE_SRC}/burst_merge.cpp)
target_include_directories(burst_merge_tool PRIVATE ${NATIVE_SRC})
target_compile_options(burst_merge_tool PRIVATE -Wall -Wextra -Werror -O3)
target_link_libraries(burst_merge_tool PRIVATE Threads::Threads)

# B23 super-resolution merge: invariants and the self-check (CTest), and the host tool the Python parity test drives.
add_executable(superres_test superres_test.cpp ${NATIVE_SRC}/superres.cpp ${NATIVE_SRC}/burst_merge.cpp ${NATIVE_SRC}/raw_pipeline.cpp)
target_include_directories(superres_test PRIVATE ${NATIVE_SRC})
target_compile_options(superres_test PRIVATE -Wall -Wextra -Werror -O3)
target_link_libraries(superres_test PRIVATE Threads::Threads)
add_test(NAME superres COMMAND superres_test)

add_executable(superres_tool superres_tool.cpp ${NATIVE_SRC}/superres.cpp ${NATIVE_SRC}/burst_merge.cpp ${NATIVE_SRC}/raw_pipeline.cpp)
target_include_directories(superres_tool PRIVATE ${NATIVE_SRC})
target_compile_options(superres_tool PRIVATE -Wall -Wextra -Werror -O3)
target_link_libraries(superres_tool PRIVATE Threads::Threads)

# B24 detail post-pass (distortion, sharpening, grain): invariants (CTest) and the host tool the Python parity test drives.
add_executable(detail_test detail_test.cpp ${NATIVE_SRC}/detail.cpp)
target_include_directories(detail_test PRIVATE ${NATIVE_SRC})
# B24.1: strict per-statement IEEE evaluation, so the strip stages can be compared bit for bit with the full-copy
# reference in the test (GCC contracts a*b+c across statements by default where FMA is available; the app's NDK clang
# contracts only within an expression, and there the strip stages match B24 exactly).
target_compile_options(detail_test PRIVATE -Wall -Wextra -Werror -O3 -ffp-contract=off)
target_link_libraries(detail_test PRIVATE Threads::Threads)
add_test(NAME detail COMMAND detail_test)

add_executable(detail_tool detail_tool.cpp ${NATIVE_SRC}/detail.cpp)
target_include_directories(detail_tool PRIVATE ${NATIVE_SRC})
target_compile_options(detail_tool PRIVATE -Wall -Wextra -Werror -O3)
target_link_libraries(detail_tool PRIVATE Threads::Threads)

# B25 Ultra HDR gain map: SDR bit-identical with the gain map, cell sums independent of the thread count, headroom and
# skip rules, the linear (super-resolution) render, the distortion warp. Strict IEEE as for detail_test.
add_executable(gainmap_test gainmap_test.cpp ${NATIVE_SRC}/raw_pipeline.cpp ${NATIVE_SRC}/gainmap.cpp ${NATIVE_SRC}/detail.cpp)
target_include_directories(gainmap_test PRIVATE ${NATIVE_SRC})
target_compile_options(gainmap_test PRIVATE -Wall -Wextra -Werror -O3 -ffp-contract=off)
target_link_libraries(gainmap_test PRIVATE Threads::Threads)
add_test(NAME gainmap COMMAND gainmap_test)

# B30 HEIF input: RGBA -> YUV 4:2:0 tiles (rounding, edge repeat, I420 == NV12, round trip).
add_executable(yuv_test yuv_test.cpp ${NATIVE_SRC}/yuv.cpp)
target_include_directories(yuv_test PRIVATE ${NATIVE_SRC})
target_compile_options(yuv_test PRIVATE -Wall -Wextra -Werror -O2)
add_test(NAME yuv COMMAND yuv_test)

# B30 depth pre/post-processing: invariants (CTest) and the host tool the Python parity test drives.
add_executable(depth_post_test depth_post_test.cpp ${NATIVE_SRC}/depth_post.cpp ${NATIVE_SRC}/detail.cpp)
target_include_directories(depth_post_test PRIVATE ${NATIVE_SRC})
target_compile_options(depth_post_test PRIVATE -Wall -Wextra -Werror -O2)
target_link_libraries(depth_post_test PRIVATE Threads::Threads)
add_test(NAME depth_post COMMAND depth_post_test)

add_executable(depth_post_tool depth_post_tool.cpp ${NATIVE_SRC}/depth_post.cpp ${NATIVE_SRC}/detail.cpp)
target_include_directories(depth_post_tool PRIVATE ${NATIVE_SRC})
target_compile_options(depth_post_tool PRIVATE -Wall -Wextra -Werror -O2)
target_link_libraries(depth_post_tool PRIVATE Threads::Threads)

# B31 simulated depth of field: PLAN 19.5 blur table, kernel shape, synthetic scene, guided-filter edges, gating,
# band/thread independence and the 4000x2667 / 3000x2000 timings (CTest), and the host tool the Python parity test drives.
add_executable(bokeh_test bokeh_test.cpp ${NATIVE_SRC}/bokeh.cpp)
target_include_directories(bokeh_test PRIVATE ${NATIVE_SRC})
target_compile_options(bokeh_test PRIVATE -Wall -Wextra -Werror -O3)
target_link_libraries(bokeh_test PRIVATE Threads::Threads)
add_test(NAME bokeh COMMAND bokeh_test)

add_executable(bokeh_tool bokeh_tool.cpp ${NATIVE_SRC}/bokeh.cpp)
target_include_directories(bokeh_tool PRIVATE ${NATIVE_SRC})
target_compile_options(bokeh_tool PRIVATE -Wall -Wextra -Werror -O3)
target_link_libraries(bokeh_tool PRIVATE Threads::Threads)
