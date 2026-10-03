cmake_minimum_required(VERSION 3.16)
project(ft8core LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# 核心静态库（纯 C，可被 CLI / JNI 复用）
# ---------------------------------------------------------------------------
set(FT8_CORE_SOURCES
    src/constants.c
    src/crc.c
    src/decode.c
    src/encode.c
    src/ldpc.c
    src/message.c
    src/text.c
    src/monitor.c
    src/wave.c
    src/fft/kiss_fft.c
    src/fft/kiss_fftr.c
    api/ft8_thread.c
    api/ft8_hash.c
    api/ft8_align.c
    api/ft8_gfsk.c
    api/ft8_subtract.c
    api/ft8_osd.c
    api/ft8_spectrum.c
    api/ft8api.c
)

add_library(ft8core STATIC ${FT8_CORE_SOURCES})

target_include_directories(ft8core PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/include/ft8
    ${CMAKE_CURRENT_SOURCE_DIR}/include/common
    ${CMAKE_CURRENT_SOURCE_DIR}/include/fft
    ${CMAKE_CURRENT_SOURCE_DIR}/api
)

if(NOT MSVC)
    target_compile_options(ft8core PRIVATE -Wall -Wextra -Wno-unused-parameter)
    # 强制优化：Android 的 Debug 构建默认不加 -O（等于 -O0），会让解码 DSP / LDPC
    # 慢一个数量级（真机实测：2 遍 SIC 约 2800 ms，远超 700 ms 目标）。
    # ft8_w 官方 Android 构建即用 -O3，这里对 ft8core 固定 -O3，
    # 使 Debug 包也能满足「单时隙解码 < 700 ms」。Release 本就是 -O3，重复无副作用。
    target_compile_options(ft8core PRIVATE -O3)
    # 上游 ft8_lib 的调试日志存在 %llx 格式告警，单独屏蔽以免噪音
    set_source_files_properties(${CMAKE_CURRENT_SOURCE_DIR}/src/message.c
        PROPERTIES COMPILE_OPTIONS "-Wno-format")
endif()

if(UNIX)
    target_link_libraries(ft8core PUBLIC m)
endif()

# ---------------------------------------------------------------------------
# Android：JNI 共享库 libft8.so（Kotlin 用 System.loadLibrary("ft8")）
# ---------------------------------------------------------------------------
if(ANDROID)
    add_library(ft8 SHARED jni/ft8_jni.cpp)
    target_link_libraries(ft8 PRIVATE ft8core)
    target_include_directories(ft8 PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
    # JNI 桥同样固定 -O3（虽然本身很薄，但与核心库保持一致的优化级别）
    if(NOT MSVC)
        target_compile_options(ft8 PRIVATE -O3)
    endif()
    # 让产物固定为 libft8.so
    set_target_properties(ft8 PROPERTIES OUTPUT_NAME ft8)
endif()

# ---------------------------------------------------------------------------
# 命令行测试 / 基准工具（主机默认构建；Android 需 -DFT8_BUILD_CLI=ON）
# ---------------------------------------------------------------------------
option(FT8_BUILD_CLI "Build the ft8_cli test/benchmark tool" OFF)

if(NOT ANDROID OR FT8_BUILD_CLI)
    add_executable(ft8_cli tools/ft8_cli.c)
    target_link_libraries(ft8_cli PRIVATE ft8core)
    if(UNIX)
        target_link_libraries(ft8_cli PRIVATE m)
    endif()
    if(NOT MSVC)
        target_compile_options(ft8_cli PRIVATE -Wall -Wextra)
    endif()
endif()
