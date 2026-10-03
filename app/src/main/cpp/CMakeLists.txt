cmake_minimum_required(VERSION 3.22.1)
project(ft8vox LANGUAGES C CXX)

# -----------------------------------------------------------------------------
# FT8 编解码核心：来自 ft8_w/native（编解码 / LDPC / OSD / 多遍信号消除）
#
# `ft8w/CMakeLists.txt` 在 Android 下生成：
#   - ft8core：纯 C 静态库
#   - ft8：JNI 共享库 => libft8.so（Kotlin 侧 com.ft8.nativecore.Ft8Native）
#
# 音频采集/播放/VOX 已改由 Kotlin 实现（engine/AudioEngine.kt），
# 本 CMake 不再包含旧的 audio_engine.c / ftx_session.c / ft8_lib。
# -----------------------------------------------------------------------------
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/ft8w ft8w)
