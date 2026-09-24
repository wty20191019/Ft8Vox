#ifndef FT8VOX_JNI_COMMON_H
#define FT8VOX_JNI_COMMON_H

#include <jni.h>

#include "ftx_session.h"

// 与 Kotlin data class com.example.ft8vox.engine.DecodeResult 对应：
//   DecodeResult(text: String, snr: Int, dt: Float, df: Int, score: Int, slotUtcMs: Long)
// 构造器签名 (Ljava/lang/String;IFIIJ)V。
// 修改 Kotlin 侧字段/顺序时，务必同步更新此处。
#define FT8VOX_DECODE_RESULT_CLASS "com/example/ft8vox/engine/DecodeResult"
#define FT8VOX_DECODE_RESULT_CTOR  "(Ljava/lang/String;IFIIJ)V"

static inline jclass ft8vox_decode_result_class(JNIEnv* env)
{
    return (*env)->FindClass(env, FT8VOX_DECODE_RESULT_CLASS);
}

static inline jobject ft8vox_make_decode_result(JNIEnv* env, jclass cls, jmethodID ctor,
                                                const ftx_decode_result_t* r, jlong slot_utc_ms)
{
    jstring text = (*env)->NewStringUTF(env, r->text);
    jobject obj = (*env)->NewObject(env, cls, ctor, text, (jint)r->snr, (jfloat)r->dt,
                                    (jint)r->df, (jint)r->score, slot_utc_ms);
    if (text != NULL)
        (*env)->DeleteLocalRef(env, text);
    return obj;
}

#endif // FT8VOX_JNI_COMMON_H
