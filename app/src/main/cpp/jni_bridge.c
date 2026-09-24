#include <jni.h>

// 先占位，第 5 步替换
JNIEXPORT jstring JNICALL
Java_com_example_ft8vox_Ft8Engine_test(JNIEnv *env, jobject thiz)
{
    return (*env)->NewStringUTF(env, "FT8 JNI ready");
}