package com.example.ft8vox.engine

/**
 * FT8 / FT4 引擎的 Kotlin 入口。
 *
 * 负责加载 native 库 libft8.so，并向上层暴露解码、编码等接口。
 * 当前仅有占位接口 [test]，用于验证 JNI 加载链路；
 * 真正的接口见 docs/JNI-CONTRACT.md。
 *
 * 注意：类名与包名决定 native 函数符号（Java_<包>_<类>_<方法>），
 * 重命名此类或包时必须同步修改 app/src/main/cpp/jni_bridge.c。
 */
object Ft8Engine {

    init {
        System.loadLibrary("ft8")
    }

    /** 占位接口：返回 native 侧的握手字符串。 */
    external fun test(): String
}
