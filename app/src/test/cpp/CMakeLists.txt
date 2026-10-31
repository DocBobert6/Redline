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
