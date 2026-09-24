#include <jni.h>

// 占位实现：验证 JNI 与 libft8.so 的加载链路。
// 后续阶段将在此替换为真正的 FT8 引擎接口（初始化、解码、编码）。
JNIEXPORT jstring JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_test(JNIEnv *env, jobject thiz)
{
    return (*env)->NewStringUTF(env, "FT8 JNI ready");
}
