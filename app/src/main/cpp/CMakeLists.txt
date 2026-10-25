cmake_minimum_required(VERSION 3.22)
project(fnl_app CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# The engine and its prebuilt TFLite are tuned for Release; a debuggable APK must not slow them down.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3")

if(NOT FNL_ENGINE_DIR OR NOT TFLITE_SRC_DIR OR NOT TFLITE_BUILD_DIR)
  message(FATAL_ERROR "set FNL_ENGINE_DIR, TFLITE_SRC_DIR, TFLITE_BUILD_DIR (see app/build.gradle)")
endif()

# fnl_engine = glue + the pipeline against the TFLite build in TFLITE_BUILD_DIR (defined by the engine's own CMakeLists)
add_subdirectory(${FNL_ENGINE_DIR} fnl_engine_build EXCLUDE_FROM_ALL)

add_library(fnl_overlay STATIC overlay.cc)
target_include_directories(fnl_overlay PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(fnl_overlay PUBLIC fnl_glue)

add_library(fnl_jni SHARED jni.cc)
target_link_libraries(fnl_jni fnl_overlay fnl_engine jnigraphics log)
# Only the JNI entry points are exported: the static TFLite/engine archives stay hidden, so --gc-sections can drop the parts of
# TFLite that nothing reaches (some of them reference logging internals that this TFLite build does not provide, which is also
# why fnl_run links).
target_link_options(fnl_jni PRIVATE -s -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
