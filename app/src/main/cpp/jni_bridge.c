#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <math.h>

#include <common/monitor.h>
#include <common/common.h>
#include <ft8/message.h>
#include <ft8/encode.h>
#include <ft8/constants.h>

#include "ftx_session.h"
#include "jni_common.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// -----------------------------------------------------------------------------
// 发射波形参数（与 ft8_lib demo/gen_ft8.c 保持一致）
// -----------------------------------------------------------------------------
#define FT8_SYMBOL_BT  2.0f        ///< FT8 符号平滑滤波带宽因子
#define FT4_SYMBOL_BT  1.0f        ///< FT4 符号平滑滤波带宽因子
#define GFSK_CONST_K   5.336446f   ///< == pi * sqrt(2 / log(2))

// 一次性返回的最大解码条数（Kotlin 侧 nativeDecode 的结果数组上限）
// 需 ≥ DecodeSettings.MAX_DECODED_RANGE 的上界，否则「深」预设会被静默截断。
#define K_MAX_DECODED_MESSAGES 128

// =============================================================================
// 离线解码引擎
// =============================================================================

JNIEXPORT jlong JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_nativeInit(
    JNIEnv* env, jobject thiz,
    jint protocol, jint sample_rate, jfloat f_min, jfloat f_max,
    jint time_osr, jint freq_osr)
{
    monitor_config_t cfg = {
        .f_min = f_min,
        .f_max = f_max,
        .sample_rate = sample_rate,
        .time_osr = time_osr,
        .freq_osr = freq_osr,
        // Kotlin 枚举 Protocol：0=FT8, 1=FT4
        .protocol = (protocol == 1) ? FTX_PROTOCOL_FT4 : FTX_PROTOCOL_FT8,
    };
    ftx_session_t* session = ftx_session_create(&cfg);
    return (jlong)(intptr_t)session;
}

JNIEXPORT void JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_nativeReset(JNIEnv* env, jobject thiz, jlong handle)
{
    ftx_session_reset((ftx_session_t*)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_nativeRelease(JNIEnv* env, jobject thiz, jlong handle)
{
    ftx_session_free((ftx_session_t*)(intptr_t)handle);
}

// 热生效的解码参数（候选数/最低得分/LDPC 迭代/单时隙上限）。
JNIEXPORT void JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_nativeSetDecodeParams(
    JNIEnv* env, jobject thiz, jlong handle,
    jint min_score, jint max_candidates, jint ldpc_iterations, jint max_decoded)
{
    ftx_session_t* session = (ftx_session_t*)(intptr_t)handle;
    if (session == NULL)
        return;
    ftx_decode_params_t p = {
        .min_score = min_score,
        .max_candidates = max_candidates,
        .ldpc_iterations = ldpc_iterations,
        .max_decoded = max_decoded,
    };
    ftx_session_set_decode_params(session, &p);
}

// 喂入 12 kHz 单声道 PCM（float，[-1,1]）；内部按 monitor 块大小累积。
JNIEXPORT void JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_nativeProcess(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray samples, jint length)
{
    ftx_session_t* session = (ftx_session_t*)(intptr_t)handle;
    if (session == NULL || samples == NULL)
        return;

    jsize array_len = (*env)->GetArrayLength(env, samples);
    if (length <= 0 || length > array_len)
        length = array_len;

    jboolean is_copy = JNI_FALSE;
    jfloat* data = (*env)->GetFloatArrayElements(env, samples, &is_copy);
    if (data == NULL)
        return;

    ftx_session_process(session, data, length);

    (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);
}

// 对当前累积的 waterfall 解码，返回 DecodeResult[]（含 SNR/DT/DF/score）。
JNIEXPORT jobjectArray JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_nativeDecode(JNIEnv* env, jobject thiz, jlong handle)
{
    ftx_session_t* session = (ftx_session_t*)(intptr_t)handle;

    jclass cls = ft8vox_decode_result_class(env);
    jmethodID ctor = (cls != NULL)
        ? (*env)->GetMethodID(env, cls, "<init>", FT8VOX_DECODE_RESULT_CTOR)
        : NULL;
    if (cls == NULL || ctor == NULL)
        return NULL;

    ftx_decode_result_t results[K_MAX_DECODED_MESSAGES];
    int num_decoded = 0;
    if (session != NULL)
        num_decoded = ftx_session_decode(session, results, K_MAX_DECODED_MESSAGES);

    jobjectArray result = (*env)->NewObjectArray(env, num_decoded, cls, NULL);
    if (result == NULL)
        return NULL;
    for (int i = 0; i < num_decoded; ++i)
    {
        // 离线解码没有时隙概念，slotUtcMs 置 0
        jobject obj = ft8vox_make_decode_result(env, cls, ctor, &results[i], 0);
        if (obj != NULL)
        {
            (*env)->SetObjectArrayElement(env, result, i, obj);
            (*env)->DeleteLocalRef(env, obj);
        }
    }
    return result;
}

// waterfall 静态信息：返回 [bins, binHz*1000, fMin*1000]。
JNIEXPORT jintArray JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_nativeWaterfallInfo(JNIEnv* env, jobject thiz, jlong handle)
{
    ftx_session_t* session = (ftx_session_t*)(intptr_t)handle;
    int bins = 0;
    float bin_hz = 0.0f;
    float f_min = 0.0f;
    if (session != NULL)
        ftx_session_waterfall_info(session, &bins, &bin_hz, &f_min);

    jint values[3] = { (jint)bins, (jint)lroundf(bin_hz * 1000.0f), (jint)lroundf(f_min * 1000.0f) };
    jintArray result = (*env)->NewIntArray(env, 3);
    if (result != NULL)
        (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    return result;
}

// 取走新产生的 waterfall 行（每行 bins 字节）。
JNIEXPORT jbyteArray JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_nativePollWaterfall(
    JNIEnv* env, jobject thiz, jlong handle, jint max_rows)
{
    ftx_session_t* session = (ftx_session_t*)(intptr_t)handle;
    if (session == NULL || max_rows <= 0)
        return (*env)->NewByteArray(env, 0);

    int bins = 0;
    ftx_session_waterfall_info(session, &bins, NULL, NULL);
    if (bins <= 0)
        return (*env)->NewByteArray(env, 0);

    uint8_t* buf = (uint8_t*)malloc((size_t)bins * (size_t)max_rows);
    if (buf == NULL)
        return (*env)->NewByteArray(env, 0);

    int rows = ftx_session_read_waterfall(session, buf, max_rows);
    jbyteArray result = (*env)->NewByteArray(env, rows * bins);
    if (result != NULL && rows > 0)
        (*env)->SetByteArrayRegion(env, result, 0, rows * bins, (const jbyte*)buf);

    free(buf);
    return result;
}

// =============================================================================
// 编码：文本 → 77-bit 载荷 → FSK tones → GFSK 音频波形（含时隙填充）
// =============================================================================

/// 计算 GFSK 平滑脉冲（截断到 3 倍符号长度，pulse 需 3*n_spsym 个 float 空间）
static void gfsk_pulse(int n_spsym, float symbol_bt, float* pulse)
{
    for (int i = 0; i < 3 * n_spsym; ++i)
    {
        float t = i / (float)n_spsym - 1.5f;
        float arg1 = GFSK_CONST_K * symbol_bt * (t + 0.5f);
        float arg2 = GFSK_CONST_K * symbol_bt * (t - 0.5f);
        pulse[i] = (erff(arg1) - erff(arg2)) / 2.0f;
    }
}

/// 用 GFSK 相位整形合成 n_sym 个符号的音频波形，写入 signal（需 n_sym*n_spsym 个 float）
static void synth_gfsk(
    const uint8_t* symbols, int n_sym, float f0, float symbol_bt,
    float symbol_period, int signal_rate, float* signal)
{
    int n_spsym = (int)(0.5f + signal_rate * symbol_period); // 每符号采样数
    int n_wave = n_sym * n_spsym;                            // 输出采样数
    float hmod = 1.0f;

    float dphi_peak = 2.0f * (float)M_PI * hmod / n_spsym;

    // 大数组改为堆分配，避免在受限线程栈上使用 VLA
    float* dphi = (float*)malloc((size_t)(n_wave + 2 * n_spsym) * sizeof(float));
    float* pulse = (float*)malloc((size_t)(3 * n_spsym) * sizeof(float));
    if (dphi == NULL || pulse == NULL)
    {
        free(dphi);
        free(pulse);
        return;
    }

    // 初始化为载波 f0 的相位增量
    for (int i = 0; i < n_wave + 2 * n_spsym; ++i)
    {
        dphi[i] = 2.0f * (float)M_PI * f0 / signal_rate;
    }

    gfsk_pulse(n_spsym, symbol_bt, pulse);

    for (int i = 0; i < n_sym; ++i)
    {
        int ib = i * n_spsym;
        for (int j = 0; j < 3 * n_spsym; ++j)
        {
            dphi[j + ib] += dphi_peak * symbols[i] * pulse[j];
        }
    }

    // 首尾各补一个哑符号，使相位在边界连续
    for (int j = 0; j < 2 * n_spsym; ++j)
    {
        dphi[j] += dphi_peak * pulse[j + n_spsym] * symbols[0];
        dphi[j + n_sym * n_spsym] += dphi_peak * pulse[j] * symbols[n_sym - 1];
    }

    float phi = 0.0f;
    for (int k = 0; k < n_wave; ++k)
    {
        signal[k] = sinf(phi);
        phi = fmodf(phi + dphi[k + n_spsym], 2.0f * (float)M_PI);
    }

    // 首尾符号做余弦包络整形，抑制频谱边带
    int n_ramp = n_spsym / 8;
    for (int i = 0; i < n_ramp; ++i)
    {
        float env = (1.0f - cosf(2.0f * (float)M_PI * i / (2 * n_ramp))) / 2.0f;
        signal[i] *= env;
        signal[n_wave - 1 - i] *= env;
    }

    free(dphi);
    free(pulse);
}

/// 编码一个时隙的发射 PCM。
/// @return 采样率 sample_rate 的 float 数组，长度为一个完整时隙（含静音填充）；
///         文本无法编码时返回 NULL。
JNIEXPORT jfloatArray JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_nativeEncode(
    JNIEnv* env, jobject thiz, jint protocol, jstring text, jfloat frequency, jint sample_rate)
{
    if (text == NULL || sample_rate <= 0)
        return NULL;

    const char* message_text = (*env)->GetStringUTFChars(env, text, NULL);
    if (message_text == NULL)
        return NULL;

    ftx_message_t msg;
    ftx_message_rc_t rc = ftx_message_encode(&msg, NULL, message_text);
    (*env)->ReleaseStringUTFChars(env, text, message_text);
    if (rc != FTX_MESSAGE_RC_OK)
        return NULL;

    bool is_ft4 = (protocol == 1);
    int num_tones = is_ft4 ? FT4_NN : FT8_NN;
    float symbol_period = is_ft4 ? FT4_SYMBOL_PERIOD : FT8_SYMBOL_PERIOD;
    float symbol_bt = is_ft4 ? FT4_SYMBOL_BT : FT8_SYMBOL_BT;
    float slot_time = is_ft4 ? FT4_SLOT_TIME : FT8_SLOT_TIME;

    // tones 缓冲留足两种协议（FT4_NN=105 > FT8_NN=79）
    uint8_t tones[FT4_NN];
    if (is_ft4)
        ft4_encode(msg.payload, tones);
    else
        ft8_encode(msg.payload, tones);

    int num_samples = (int)(0.5f + num_tones * symbol_period * sample_rate);
    int slot_samples = (int)(slot_time * sample_rate);
    // 遵循 WSJT-X 约定：波形在时隙起点后 0.5 s 开始发射（0.5 s 保护间隔），
    // 末尾留白填满整个时隙。注意不能简单居中：FT4 时隙短、符号少，
    // 居中会让数据段起点超出解码器的候选搜索窗口，导致无法同步。
    int lead = (int)(0.5f * sample_rate);
    int num_total = slot_samples;
    if (num_total < lead + num_samples)
        num_total = lead + num_samples;

    jfloatArray result = (*env)->NewFloatArray(env, num_total);
    if (result == NULL)
        return NULL;

    jboolean is_copy = JNI_FALSE;
    jfloat* signal = (*env)->GetFloatArrayElements(env, result, &is_copy);
    if (signal == NULL)
        return result;

    for (int i = 0; i < num_total; ++i)
        signal[i] = 0.0f;

    synth_gfsk(tones, num_tones, frequency, symbol_bt, symbol_period, sample_rate,
               signal + lead);

    (*env)->ReleaseFloatArrayElements(env, result, signal, 0);
    return result;
}

// -----------------------------------------------------------------------------
// 调试：验证 JNI 与 libft8.so 的加载链路
// -----------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_test(JNIEnv* env, jobject thiz)
{
    return (*env)->NewStringUTF(env, "FT8 JNI ready");
}
