cmake_minimum_required(VERSION 3.22.1)
project(ft8vox)


file(GLOB FT8_SOURCES
    ${CMAKE_SOURCE_DIR}/ft8_lib/ft8/*.c
)

add_library(ft8 SHARED
    jni_bridge.c
    ${FT8_SOURCES}
)

target_include_directories(ft8 PRIVATE
    ${CMAKE_SOURCE_DIR}/ft8_lib/ft8
)

target_link_libraries(ft8
    android
    log
    m
)