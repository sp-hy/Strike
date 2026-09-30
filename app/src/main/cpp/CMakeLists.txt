cmake_minimum_required(VERSION 3.22.1)
project(strike)

add_library(fast_cam_client SHARED IMPORTED)
set_target_properties(fast_cam_client PROPERTIES
    IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libfast_cam_client.so"
)

add_library(strike SHARED
    fast_cam_jni.cpp
    fast_cam_gl.cpp
)

# Debug builds default to -O0, which makes the per-frame pixel conversion several times slower.
target_compile_options(strike PRIVATE -O3)

target_include_directories(strike PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/fast_cam/include
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# ANativeWindow_fromSurface is in libandroid.
target_link_libraries(strike
    fast_cam_client
    EGL
    GLESv2
    android
    log
)
