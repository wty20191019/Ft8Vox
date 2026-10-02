/**
 * @file ft8_jni.cpp
 * @brief FT8 C 层与 Kotlin 之间的 JNI 桥。
 *
 * Kotlin 侧约定（包名 com.ft8.nativecore，类 Ft8Native）：
 *
 *   object Ft8Native {
 *       init { System.loadLibrary("ft8") }
 *       external fun version(): String
 *       external fun decodeConfigDefault(): FloatArray
 *       external fun encodeConfigDefault(): FloatArray
 *       external fun encodeLength(message: String, cfg: FloatArray): Int
 *       external fun encode(message: String, cfg: FloatArray, out: FloatArray): Int
 *       external fun encodeTones(message: String): ByteArray
 *       external fun decodeSlot(
 *           samples: FloatArray, cfg: FloatArray,
 *           myCall: String?, hisCall: String?, hisGrid: String?): Array<String>
 *       external fun decodeSlotDirect(
 *           samples: java.nio.FloatBuffer, length: Int, cfg: FloatArray,
 *           myCall: String?, hisCall: String?, hisGrid: String?): Array<String>
 *       external fun sessionCreate(
 *           cfg: FloatArray, myCall: String?, hisCall: String?, hisGrid: String?): Long
 *       external fun sessionFeed(handle: Long, samples: FloatArray): Int
 *       external fun sessionFinalize(handle: Long): Array<String>
 *       external fun sessionReset(handle: Long)
 *       external fun sessionDestroy(handle: Long)
 *       external fun hashClear()
 *   }
 *
 * 结果字符串格式（每条）：
 *   text|snr|dt|freq|score|ldpc_errors|pass|ap_type|crc
 */
#include <jni.h>

#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "ft8api.h"

#define FT8_MAX_RESULTS 256

/* 解码配置 float[] 下标 */
enum
{
    DC_FMIN = 0,
    DC_FMAX,
    DC_SAMPLE_RATE,
    DC_TIME_OSR,
    DC_FREQ_OSR,
    DC_MAX_CANDIDATES,
    DC_MIN_SYNC_SCORE,
    DC_LDPC_ITERATIONS,
    DC_DECODE_DEPTH,
    DC_AP_MODE,
    DC_ENABLE_SUBTRACT,
    DC_NUM_THREADS,
    DC_RETURN_DUPLICATES,
    DC_OSD_DEPTH,
    DC_ENABLE_LLR_REFINE,
    DC_COUNT
};

/* 编码配置 float[] 下标 */
enum
{
    EC_SAMPLE_RATE = 0,
    EC_BASE_FREQ,
    EC_AMPLITUDE,
    EC_SYMBOL_BT,
    EC_LEAD_IN,
    EC_TAIL,
    EC_COUNT
};

static jclass g_string_class = NULL;

/* ------------------------------------------------------------------------- */
/* 工具函数                                                                   */
/* ------------------------------------------------------------------------- */

static void throw_java(JNIEnv* env, const char* message)
{
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls)
        env->ThrowNew(cls, message ? message : "FT8 error");
}

static const char* jstr(JNIEnv* env, jstring s, const char** out_chars)
{
    *out_chars = NULL;
    if (!s)
        return NULL;
    const char* c = env->GetStringUTFChars(s, NULL);
    *out_chars = c;
    return c;
}

static void read_float_array(JNIEnv* env, jfloatArray arr, float* out, int count)
{
    if (!arr)
        return;
    jsize n = env->GetArrayLength(arr);
    if (n > count)
        n = count;
    env->GetFloatArrayRegion(arr, 0, n, out);
}

static void read_decode_cfg(JNIEnv* env, jfloatArray arr, ft8_decode_config_t* cfg)
{
    ft8_decode_config_default(cfg);
    if (!arr)
        return;
    float buf[DC_COUNT];
    for (int i = 0; i < DC_COUNT; ++i)
        buf[i] = 0.0f;
    read_float_array(env, arr, buf, DC_COUNT);
    jsize n = env->GetArrayLength(arr);

    if (n > DC_FMIN) cfg->f_min_hz = buf[DC_FMIN];
    if (n > DC_FMAX) cfg->f_max_hz = buf[DC_FMAX];
    if (n > DC_SAMPLE_RATE) cfg->sample_rate = (int)buf[DC_SAMPLE_RATE];
    if (n > DC_TIME_OSR) cfg->time_osr = (int)buf[DC_TIME_OSR];
    if (n > DC_FREQ_OSR) cfg->freq_osr = (int)buf[DC_FREQ_OSR];
    if (n > DC_MAX_CANDIDATES) cfg->max_candidates = (int)buf[DC_MAX_CANDIDATES];
    if (n > DC_MIN_SYNC_SCORE) cfg->min_sync_score = (int)buf[DC_MIN_SYNC_SCORE];
    if (n > DC_LDPC_ITERATIONS) cfg->ldpc_iterations = (int)buf[DC_LDPC_ITERATIONS];
    if (n > DC_DECODE_DEPTH) cfg->decode_depth = (int)buf[DC_DECODE_DEPTH];
    if (n > DC_AP_MODE) cfg->ap_mode = (int)buf[DC_AP_MODE];
    if (n > DC_ENABLE_SUBTRACT) cfg->enable_subtract = buf[DC_ENABLE_SUBTRACT] != 0.0f;
    if (n > DC_NUM_THREADS) cfg->num_threads = (int)buf[DC_NUM_THREADS];
    if (n > DC_RETURN_DUPLICATES) cfg->return_duplicates = buf[DC_RETURN_DUPLICATES] != 0.0f;
    if (n > DC_OSD_DEPTH) cfg->osd_depth = (int)buf[DC_OSD_DEPTH];
    if (n > DC_ENABLE_LLR_REFINE) cfg->enable_llr_refine = buf[DC_ENABLE_LLR_REFINE] != 0.0f;
}

static void read_encode_cfg(JNIEnv* env, jfloatArray arr, ft8_encode_config_t* cfg)
{
    ft8_encode_config_default(cfg);
    if (!arr)
        return;
    float buf[EC_COUNT];
    for (int i = 0; i < EC_COUNT; ++i)
        buf[i] = 0.0f;
    read_float_array(env, arr, buf, EC_COUNT);
    jsize n = env->GetArrayLength(arr);

    if (n > EC_SAMPLE_RATE) cfg->sample_rate = (int)buf[EC_SAMPLE_RATE];
    if (n > EC_BASE_FREQ) cfg->base_freq_hz = buf[EC_BASE_FREQ];
    if (n > EC_AMPLITUDE) cfg->amplitude = buf[EC_AMPLITUDE];
    if (n > EC_SYMBOL_BT) cfg->symbol_bt = buf[EC_SYMBOL_BT];
    if (n > EC_LEAD_IN) cfg->lead_in_sec = buf[EC_LEAD_IN];
    if (n > EC_TAIL) cfg->tail_sec = buf[EC_TAIL];
}

/// 把解码结果打包为 Array<String>
static jobjectArray make_result_array(JNIEnv* env, const ft8_message_result_t* results, int count)
{
    if (!g_string_class)
    {
        jclass cls = env->FindClass("java/lang/String");
        g_string_class = (jclass)env->NewGlobalRef(cls);
    }
    if (!g_string_class || count < 0)
        return NULL;

    jobjectArray arr = env->NewObjectArray(count, g_string_class, NULL);
    if (!arr)
        return NULL;

    for (int i = 0; i < count; ++i)
    {
        const ft8_message_result_t* r = &results[i];
        char line[256];
        snprintf(line, sizeof(line), "%s|%.1f|%.2f|%.1f|%d|%d|%d|%d|%u",
                 r->text, r->snr, r->dt, r->freq, r->score,
                 r->ldpc_errors, r->pass, r->ap_type, (unsigned)r->crc);
        jstring s = env->NewStringUTF(line);
        env->SetObjectArrayElement(arr, i, s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

static void apply_callsigns(ft8_decode_config_t* cfg, const char* my, const char* his, const char* grid)
{
    cfg->my_call = (my && my[0]) ? my : NULL;
    cfg->his_call = (his && his[0]) ? his : NULL;
    cfg->his_grid = (grid && grid[0]) ? grid : NULL;
}

/* ------------------------------------------------------------------------- */
/* JNI 方法                                                                   */
/* ------------------------------------------------------------------------- */

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
    (void)reserved;
    JNIEnv* env = NULL;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;
    jclass cls = env->FindClass("java/lang/String");
    if (cls)
        g_string_class = (jclass)env->NewGlobalRef(cls);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ft8_nativecore_Ft8Native_version(JNIEnv* env, jclass)
{
    return env->NewStringUTF(ft8_version());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ft8_nativecore_Ft8Native_decodeConfigDefault(JNIEnv* env, jclass)
{
    ft8_decode_config_t cfg;
    ft8_decode_config_default(&cfg);
    float buf[DC_COUNT];
    buf[DC_FMIN] = cfg.f_min_hz;
    buf[DC_FMAX] = cfg.f_max_hz;
    buf[DC_SAMPLE_RATE] = (float)cfg.sample_rate;
    buf[DC_TIME_OSR] = (float)cfg.time_osr;
    buf[DC_FREQ_OSR] = (float)cfg.freq_osr;
    buf[DC_MAX_CANDIDATES] = (float)cfg.max_candidates;
    buf[DC_MIN_SYNC_SCORE] = (float)cfg.min_sync_score;
    buf[DC_LDPC_ITERATIONS] = (float)cfg.ldpc_iterations;
    buf[DC_DECODE_DEPTH] = (float)cfg.decode_depth;
    buf[DC_AP_MODE] = (float)cfg.ap_mode;
    buf[DC_ENABLE_SUBTRACT] = cfg.enable_subtract ? 1.0f : 0.0f;
    buf[DC_NUM_THREADS] = (float)cfg.num_threads;
    buf[DC_RETURN_DUPLICATES] = cfg.return_duplicates ? 1.0f : 0.0f;
    buf[DC_OSD_DEPTH] = (float)cfg.osd_depth;
    buf[DC_ENABLE_LLR_REFINE] = cfg.enable_llr_refine ? 1.0f : 0.0f;

    jfloatArray arr = env->NewFloatArray(DC_COUNT);
    if (arr)
        env->SetFloatArrayRegion(arr, 0, DC_COUNT, buf);
    return arr;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ft8_nativecore_Ft8Native_encodeConfigDefault(JNIEnv* env, jclass)
{
    ft8_encode_config_t cfg;
    ft8_encode_config_default(&cfg);
    float buf[EC_COUNT];
    buf[EC_SAMPLE_RATE] = (float)cfg.sample_rate;
    buf[EC_BASE_FREQ] = cfg.base_freq_hz;
    buf[EC_AMPLITUDE] = cfg.amplitude;
    buf[EC_SYMBOL_BT] = cfg.symbol_bt;
    buf[EC_LEAD_IN] = cfg.lead_in_sec;
    buf[EC_TAIL] = cfg.tail_sec;

    jfloatArray arr = env->NewFloatArray(EC_COUNT);
    if (arr)
        env->SetFloatArrayRegion(arr, 0, EC_COUNT, buf);
    return arr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ft8_nativecore_Ft8Native_encodeLength(JNIEnv* env, jclass, jstring message, jfloatArray cfg_arr)
{
    if (!message)
    {
        throw_java(env, "message 为空");
        return FT8_ERR_ARG;
    }
    const char* msg = env->GetStringUTFChars(message, NULL);
    ft8_encode_config_t cfg;
    read_encode_cfg(env, cfg_arr, &cfg);
    int len = ft8_encode_message(msg, &cfg, NULL, 0);
    env->ReleaseStringUTFChars(message, msg);
    return len;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ft8_nativecore_Ft8Native_encode(JNIEnv* env, jclass, jstring message, jfloatArray cfg_arr, jfloatArray out)
{
    if (!message || !out)
    {
        throw_java(env, "message/out 为空");
        return FT8_ERR_ARG;
    }
    const char* msg = env->GetStringUTFChars(message, NULL);
    ft8_encode_config_t cfg;
    read_encode_cfg(env, cfg_arr, &cfg);

    jsize cap = env->GetArrayLength(out);
    jfloat* buf = (jfloat*)env->GetPrimitiveArrayCritical(out, NULL);
    int written = -1;
    if (buf)
        written = ft8_encode_message(msg, &cfg, (float*)buf, (int)cap);
    if (buf)
        env->ReleasePrimitiveArrayCritical(out, buf, 0);

    env->ReleaseStringUTFChars(message, msg);

    if (written < 0)
    {
        throw_java(env, ft8_last_error());
        return written;
    }
    return written;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ft8_nativecore_Ft8Native_encodeTones(JNIEnv* env, jclass, jstring message)
{
    if (!message)
    {
        throw_java(env, "message 为空");
        return NULL;
    }
    const char* msg = env->GetStringUTFChars(message, NULL);
    uint8_t tones[FT8API_NUM_TONES];
    int rc = ft8_encode_tones(msg, tones);
    env->ReleaseStringUTFChars(message, msg);
    if (rc != FT8_OK)
    {
        throw_java(env, ft8_last_error());
        return NULL;
    }
    jbyteArray arr = env->NewByteArray(FT8API_NUM_TONES);
    if (arr)
        env->SetByteArrayRegion(arr, 0, FT8API_NUM_TONES, (const jbyte*)tones);
    return arr;
}

static jobjectArray decode_common(JNIEnv* env, const float* samples, int num_samples,
                                  jfloatArray cfg_arr,
                                  jstring my_call, jstring his_call, jstring his_grid)
{
    ft8_decode_config_t cfg;
    read_decode_cfg(env, cfg_arr, &cfg);

    const char* c_my = NULL;
    const char* c_his = NULL;
    const char* c_grid = NULL;
    const char* my = jstr(env, my_call, &c_my);
    const char* his = jstr(env, his_call, &c_his);
    const char* grid = jstr(env, his_grid, &c_grid);
    apply_callsigns(&cfg, my, his, grid);

    ft8_message_result_t* results =
        (ft8_message_result_t*)malloc(sizeof(ft8_message_result_t) * FT8_MAX_RESULTS);
    int count = FT8_ERR_NOMEM;
    if (results)
    {
        count = ft8_decode_slot(samples, num_samples, &cfg, results, FT8_MAX_RESULTS);
    }

    jobjectArray arr = NULL;
    if (count >= 0)
        arr = make_result_array(env, results, count);
    else
        throw_java(env, ft8_last_error());

    free(results);
    if (c_my) env->ReleaseStringUTFChars(my_call, c_my);
    if (c_his) env->ReleaseStringUTFChars(his_call, c_his);
    if (c_grid) env->ReleaseStringUTFChars(his_grid, c_grid);
    return arr;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ft8_nativecore_Ft8Native_decodeSlot(JNIEnv* env, jclass, jfloatArray samples, jfloatArray cfg_arr,
                                             jstring my_call, jstring his_call, jstring his_grid)
{
    if (!samples)
    {
        throw_java(env, "samples 为空");
        return NULL;
    }
    jsize n = env->GetArrayLength(samples);
    /* 先拷贝为原生缓冲，避免在数组临界区内调用 JNI 函数（构建结果数组） */
    float* buf = (float*)malloc((size_t)(n > 0 ? n : 1) * sizeof(float));
    if (!buf)
    {
        throw_java(env, "内存不足");
        return NULL;
    }
    if (n > 0)
        env->GetFloatArrayRegion(samples, 0, n, buf);

    jobjectArray arr = decode_common(env, buf, (int)n, cfg_arr, my_call, his_call, his_grid);
    free(buf);
    return arr;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ft8_nativecore_Ft8Native_decodeSlotDirect(JNIEnv* env, jclass, jobject samples, jint length,
                                                   jfloatArray cfg_arr,
                                                   jstring my_call, jstring his_call, jstring his_grid)
{
    if (!samples)
    {
        throw_java(env, "samples 为空");
        return NULL;
    }
    float* buf = (float*)env->GetDirectBufferAddress(samples);
    if (!buf)
    {
        throw_java(env, "samples 不是 DirectBuffer");
        return NULL;
    }
    return decode_common(env, (const float*)buf, (int)length, cfg_arr, my_call, his_call, his_grid);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ft8_nativecore_Ft8Native_sessionCreate(JNIEnv* env, jclass, jfloatArray cfg_arr,
                                                jstring my_call, jstring his_call, jstring his_grid)
{
    ft8_decode_config_t cfg;
    read_decode_cfg(env, cfg_arr, &cfg);

    const char* c_my = NULL;
    const char* c_his = NULL;
    const char* c_grid = NULL;
    const char* my = jstr(env, my_call, &c_my);
    const char* his = jstr(env, his_call, &c_his);
    const char* grid = jstr(env, his_grid, &c_grid);
    apply_callsigns(&cfg, my, his, grid);

    ft8_decode_session_t* session = ft8_decode_session_create(&cfg);

    if (c_my) env->ReleaseStringUTFChars(my_call, c_my);
    if (c_his) env->ReleaseStringUTFChars(his_call, c_his);
    if (c_grid) env->ReleaseStringUTFChars(his_grid, c_grid);

    if (!session)
    {
        throw_java(env, ft8_last_error());
        return 0;
    }
    return (jlong)(intptr_t)session;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ft8_nativecore_Ft8Native_sessionFeed(JNIEnv* env, jclass, jlong handle, jfloatArray samples)
{
    ft8_decode_session_t* session = (ft8_decode_session_t*)(intptr_t)handle;
    if (!session || !samples)
    {
        throw_java(env, "会话或 samples 为空");
        return FT8_ERR_ARG;
    }
    jsize n = env->GetArrayLength(samples);
    jfloat* buf = (jfloat*)env->GetPrimitiveArrayCritical(samples, NULL);
    int rc = FT8_ERR_NOMEM;
    if (buf)
        rc = ft8_decode_session_feed(session, (const float*)buf, (int)n);
    if (buf)
        env->ReleasePrimitiveArrayCritical(samples, buf, 0);
    if (rc < 0)
        throw_java(env, ft8_last_error());
    return rc;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ft8_nativecore_Ft8Native_sessionFinalize(JNIEnv* env, jclass, jlong handle)
{
    ft8_decode_session_t* session = (ft8_decode_session_t*)(intptr_t)handle;
    if (!session)
    {
        throw_java(env, "会话为空");
        return NULL;
    }
    ft8_message_result_t* results =
        (ft8_message_result_t*)malloc(sizeof(ft8_message_result_t) * FT8_MAX_RESULTS);
    if (!results)
    {
        throw_java(env, "内存不足");
        return NULL;
    }
    int count = ft8_decode_session_finalize(session, results, FT8_MAX_RESULTS);
    jobjectArray arr = NULL;
    if (count >= 0)
        arr = make_result_array(env, results, count);
    else
        throw_java(env, ft8_last_error());
    free(results);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ft8_nativecore_Ft8Native_sessionReset(JNIEnv* env, jclass, jlong handle)
{
    (void)env;
    ft8_decode_session_t* session = (ft8_decode_session_t*)(intptr_t)handle;
    if (session)
        ft8_decode_session_reset(session);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ft8_nativecore_Ft8Native_sessionDestroy(JNIEnv* env, jclass, jlong handle)
{
    (void)env;
    ft8_decode_session_t* session = (ft8_decode_session_t*)(intptr_t)handle;
    if (session)
        ft8_decode_session_free(session);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ft8_nativecore_Ft8Native_hashClear(JNIEnv* env, jclass)
{
    (void)env;
    ft8_hash_clear();
}
