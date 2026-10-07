# hgjni built for the development machine, so the JVM unit tests can drive the
# real device core through the same JNI bridge the app uses.
cmake_minimum_required(VERSION 3.16)
project(hgjni_host CXX)

set(CMAKE_CXX_STANDARD 17)
set(HG_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../firmware ${CMAKE_BINARY_DIR}/firmware)

if(NOT JNI_INCLUDE)
  message(FATAL_ERROR "Set JNI_INCLUDE to a JDK's include directory")
endif()

add_library(hgjni SHARED ../../main/cpp/hgjni.cpp)
# android/log.h comes from the shim beside this file.
target_include_directories(hgjni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${JNI_INCLUDE} ${JNI_INCLUDE}/linux ${JNI_INCLUDE}/darwin)
target_link_libraries(hgjni PRIVATE hgsim)
target_compile_options(hgjni PRIVATE -Wall -Wextra -fno-exceptions -fno-rtti)
