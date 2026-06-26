cmake_minimum_required(VERSION 3.22.1)
project(hermesaec C CXX)

# Vendored WebRTC AECM (BSD-3, see webrtc/LICENSE). Generic-C path only: the
# NEON/MIPS core variants are intentionally not vendored, and WEBRTC_HAS_NEON /
# MIPS32_LE are left undefined, so the portable function pointers are used on
# every ABI. This is plenty fast for 10 ms / 160-sample frames.
file(GLOB AECM_SRC
    "${CMAKE_CURRENT_LIST_DIR}/webrtc/aecm/*.c"
    "${CMAKE_CURRENT_LIST_DIR}/webrtc/aecm/*.cc")

add_library(hermesaec SHARED
    aec_jni.cpp
    ${AECM_SRC})

target_include_directories(hermesaec PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/webrtc/aecm")

# The vendored sources are third-party; silence their (harmless) warnings so the
# build log stays useful, but keep -O2 for the DSP hot loops.
target_compile_options(hermesaec PRIVATE
    -O2
    -Wno-unused-variable
    -Wno-unused-but-set-variable
    -Wno-unused-function)

find_library(log-lib log)
target_link_libraries(hermesaec ${log-lib})
