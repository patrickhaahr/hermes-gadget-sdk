# The production device core and its C ABI (firmware/sim), plus the JNI bridge.
cmake_minimum_required(VERSION 3.16)
project(hermes_gadget_android CXX)

set(HG_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../firmware ${CMAKE_BINARY_DIR}/firmware)

add_library(hgjni SHARED hgjni.cpp)
target_link_libraries(hgjni PRIVATE hgsim log)
target_compile_options(hgjni PRIVATE -Wall -Wextra -fno-exceptions -fno-rtti)
