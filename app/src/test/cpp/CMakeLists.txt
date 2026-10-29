# Host build of the native parser test (B20). CI runs it in the "Build, lint, unit tests" job.
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
