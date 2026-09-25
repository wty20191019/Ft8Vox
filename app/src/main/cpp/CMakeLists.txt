cmake_minimum_required(VERSION 3.22.1)
project(ft8vox)

# FT8 核心：编码 / 解码 / LDPC / CRC / 消息
file(GLOB FT8_SOURCES
    ${CMAKE_SOURCE_DIR}/ft8_lib/ft8/*.c
)

# 通用 DSP：monitor（STFT / 瀑布）、wave（WAV 读写，调试用）
# 注意：不要包含 common/audio.c，它依赖 PortAudio，Android 无法编译
set(COMMON_SOURCES
    ${CMAKE_SOURCE_DIR}/ft8_lib/common/monitor.c
    ${CMAKE_SOURCE_DIR}/ft8_lib/common/wave.c
)

# kissfft
file(GLOB FFT_SOURCES
    ${CMAKE_SOURCE_DIR}/ft8_lib/fft/*.c
)

add_library(ft8 SHARED
    jni_bridge.c
    audio_engine.c
    ftx_session.c
    ${FT8_SOURCES}
    ${COMMON_SOURCES}
    ${FFT_SOURCES}
)

target_include_directories(ft8 PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/ft8_lib
    ${CMAKE_SOURCE_DIR}/ft8_lib/ft8
)

find_library(AAUDIO_LIB aaudio)

target_link_libraries(ft8
    android
    log
    dl
    m
    ${AAUDIO_LIB}
)
