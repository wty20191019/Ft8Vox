#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <math.h>
#include <pthread.h>
#include <time.h>
#include <stdatomic.h>
#include <unistd.h>

#include <aaudio/AAudio.h>
#include <android/log.h>

#include <common/monitor.h>
#include <ft8/constants.h>

#include "ftx_session.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define LOG_TAG "Ft8VoxAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 内部工作采样率：FT8/FT4 协议标准
#define WORK_RATE 12000

// 采集环形缓冲（2 的幂），48 kHz 下约 0.68 s
#define CAP_RING_CAP (1u << 15)
// DSP 每次从环形缓冲读取的原始采样数
#define DSP_CHUNK 1024
// 重采样输出缓冲（应 >= DSP_CHUNK * 最大降采样倍率）
#define RS_CHUNK 8192
// 每时隙最多缓存的解码结果条数
#define RESULT_CAP 64
#define DECODE_BATCH 16

// 允许在时隙起点后多久开始采集（毫秒）；超过则丢弃等下一个时隙
#define ALIGN_TOLERANCE_MS 200

// -----------------------------------------------------------------------------
// 线程安全的 SPSC 环形缓冲（float）
// -----------------------------------------------------------------------------
typedef struct
{
    float* data;
    uint32_t cap; ///< 2 的幂
    _Atomic uint32_t head;
    _Atomic uint32_t tail;
} ring_t;

static void ring_init(ring_t* r, uint32_t cap)
{
    r->data = (float*)calloc(cap, sizeof(float));
    r->cap = cap;
    atomic_store(&r->head, 0);
    atomic_store(&r->tail, 0);
}

static void ring_free(ring_t* r)
{
    free(r->data);
    r->data = NULL;
}

/// 写入 n 个样本，返回实际写入数（空间不足时丢弃多余部分）。
static uint32_t ring_write(ring_t* r, const float* src, uint32_t n)
{
    if (r->data == NULL)
        return 0;
    uint32_t head = atomic_load_explicit(&r->head, memory_order_relaxed);
    uint32_t tail = atomic_load_explicit(&r->tail, memory_order_acquire);
    uint32_t space = r->cap - (head - tail);
    if (n > space)
        n = space;
    for (uint32_t i = 0; i < n; ++i)
        r->data[(head + i) & (r->cap - 1)] = src[i];
    atomic_store_explicit(&r->head, head + n, memory_order_release);
    return n;
}

/// 读取至多 n 个样本，返回实际读取数。
static uint32_t ring_read(ring_t* r, float* dst, uint32_t n)
{
    if (r->data == NULL)
        return 0;
    uint32_t tail = atomic_load_explicit(&r->tail, memory_order_relaxed);
    uint32_t head = atomic_load_explicit(&r->head, memory_order_acquire);
    uint32_t avail = head - tail;
    if (n > avail)
        n = avail;
    for (uint32_t i = 0; i < n; ++i)
        dst[i] = r->data[(tail + i) & (r->cap - 1)];
    atomic_store_explicit(&r->tail, tail + n, memory_order_release);
    return n;
}

// -----------------------------------------------------------------------------
// 重采样器：降采样时先过 4 阶 Butterworth 低通（抗混叠），再线性插值。
// 升采样时直接线性插值。
// -----------------------------------------------------------------------------
typedef struct
{
    bool bypass;
    bool filter;
    double step; ///< in_rate / out_rate
    double phase;
    bool has_prev;
    float prev;
    float b0[2], b1[2], b2[2], a1[2], a2[2];
    float z1[2], z2[2];
} resampler_t;

static void biquad_lp(float fs, float fc, float q,
                      float* b0, float* b1, float* b2, float* a1, float* a2)
{
    float w0 = 2.0f * (float)M_PI * fc / fs;
    float cw = cosf(w0);
    float sw = sinf(w0);
    float alpha = sw / (2.0f * q);
    float B0 = (1.0f - cw) / 2.0f;
    float B1 = 1.0f - cw;
    float B2 = (1.0f - cw) / 2.0f;
    float A0 = 1.0f + alpha;
    float A1 = -2.0f * cw;
    float A2 = 1.0f - alpha;
    *b0 = B0 / A0;
    *b1 = B1 / A0;
    *b2 = B2 / A0;
    *a1 = A1 / A0;
    *a2 = A2 / A0;
}

static void resampler_init(resampler_t* rs, int in_rate, int out_rate)
{
    memset(rs, 0, sizeof(*rs));
    if (in_rate <= 0 || out_rate <= 0)
    {
        rs->bypass = true;
        return;
    }
    rs->bypass = (in_rate == out_rate);
    if (rs->bypass)
        return;

    rs->step = (double)in_rate / (double)out_rate;
    rs->phase = 0.0;
    rs->has_prev = false;

    // 仅降采样需要抗混叠；截止频率取 0.35*out_rate（FT8/FT4 分析上限约 3 kHz）
    rs->filter = (out_rate < in_rate);
    if (rs->filter)
    {
        float fc = 0.35f * (float)out_rate;
        float limit = 0.45f * (float)in_rate;
        if (fc > limit)
            fc = limit;
        // 4 阶 Butterworth = 两级 Q 值固定的二阶节
        biquad_lp((float)in_rate, fc, 0.54119610f,
                  &rs->b0[0], &rs->b1[0], &rs->b2[0], &rs->a1[0], &rs->a2[0]);
        biquad_lp((float)in_rate, fc, 1.30656296f,
                  &rs->b0[1], &rs->b1[1], &rs->b2[1], &rs->a1[1], &rs->a2[1]);
    }
}

static inline float biquad(float x, float b0, float b1, float b2, float a1, float a2,
                           float* z1, float* z2)
{
    float y = b0 * x + *z1;
    *z1 = b1 * x - a1 * y + *z2;
    *z2 = b2 * x - a2 * y;
    return y;
}

/// 处理 n 个输入样本，输出到 out；返回输出样本数。
static int resampler_process(resampler_t* rs, const float* in, int n, float* out, int out_cap)
{
    if (n <= 0)
        return 0;

    int o = 0;
    for (int i = 0; i < n; ++i)
    {
        float x = in[i];
        if (rs->filter)
        {
            x = biquad(x, rs->b0[0], rs->b1[0], rs->b2[0], rs->a1[0], rs->a2[0],
                       &rs->z1[0], &rs->z2[0]);
            x = biquad(x, rs->b0[1], rs->b1[1], rs->b2[1], rs->a1[1], rs->a2[1],
                       &rs->z1[1], &rs->z2[1]);
        }

        if (rs->bypass)
        {
            if (o < out_cap)
                out[o++] = x;
            continue;
        }

        if (!rs->has_prev)
        {
            rs->prev = x;
            rs->has_prev = true;
        }
        while (rs->phase < 1.0)
        {
            if (o < out_cap)
                out[o++] = rs->prev + (x - rs->prev) * (float)rs->phase;
            rs->phase += rs->step;
        }
        rs->phase -= 1.0;
        rs->prev = x;
    }
    return o;
}

// -----------------------------------------------------------------------------
// 实时音频引擎
// -----------------------------------------------------------------------------
typedef struct
{
    ftx_session_t* session;
    int slot_ms;
    int slot_samples;

    // 采集
    AAudioStream* in_stream;
    int in_rate;
    ring_t cap_ring;
    _Atomic bool running;
    _Atomic bool capturing;
    pthread_t dsp_thread;
    bool dsp_started;
    resampler_t cap_rs;

    // 播放
    AAudioStream* out_stream;
    int out_rate;

    // 解码结果（等待 Kotlin 轮询取走）
    pthread_mutex_t res_mutex;
    char results[RESULT_CAP][FTX_MAX_MESSAGE_LENGTH];
    int result_count;

    // 统计
    _Atomic int64_t dropped;
    _Atomic int64_t slots_decoded;
    _Atomic int64_t fed_samples;
    _Atomic int64_t last_slot;
} audio_engine_t;

static int64_t utc_now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000 + (int64_t)ts.tv_nsec / 1000000;
}

// 采集回调：只做环形缓冲写入，绝不分配内存或加锁
static aaudio_data_callback_result_t capture_callback(
    AAudioStream* stream, void* user_data, void* audio_data, int32_t num_frames)
{
    audio_engine_t* e = (audio_engine_t*)user_data;
    uint32_t n = (uint32_t)num_frames;
    uint32_t written = ring_write(&e->cap_ring, (const float*)audio_data, n);
    if (written < n)
        atomic_fetch_add_explicit(&e->dropped, (int64_t)(n - written), memory_order_relaxed);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

/// 解码当前时隙并把结果追加到待轮询队列。
static void decode_and_store(audio_engine_t* e)
{
    char texts[DECODE_BATCH][FTX_MAX_MESSAGE_LENGTH];
    int n = ftx_session_decode(e->session, texts, DECODE_BATCH);
    atomic_fetch_add(&e->slots_decoded, 1);
    if (n <= 0)
        return;

    pthread_mutex_lock(&e->res_mutex);
    for (int i = 0; i < n && e->result_count < RESULT_CAP; ++i)
    {
        strncpy(e->results[e->result_count], texts[i], FTX_MAX_MESSAGE_LENGTH - 1);
        e->results[e->result_count][FTX_MAX_MESSAGE_LENGTH - 1] = '\0';
        e->result_count++;
    }
    pthread_mutex_unlock(&e->res_mutex);
}

/// 把重采样后的 12 kHz 样本按时隙窗口喂给解码会话。
static void feed_slot(audio_engine_t* e, const float* samples, int count)
{
    if (count <= 0)
        return;

    int64_t now = utc_now_ms();
    int64_t slot = now / e->slot_ms;
    int pos = (int)(now % e->slot_ms);

    if (!atomic_load(&e->capturing))
    {
        // 只在时隙起点附近开始采集，保证采集窗口与 UTC 时隙对齐
        if (pos <= ALIGN_TOLERANCE_MS)
        {
            ftx_session_reset(e->session);
            atomic_store(&e->fed_samples, 0);
            atomic_store(&e->last_slot, slot);
            atomic_store(&e->capturing, true);
        }
        else
        {
            return; // 丢弃，等待下一个时隙
        }
    }

    // 已跨入新时隙：解码当前（可能不完整）后重新对齐
    if (slot != atomic_load(&e->last_slot))
    {
        if (atomic_load(&e->fed_samples) > 0)
            decode_and_store(e);
        atomic_store(&e->capturing, false);
        return;
    }

    ftx_session_process(e->session, samples, count);
    int64_t fed = atomic_fetch_add(&e->fed_samples, count) + count;
    if (fed >= e->slot_samples)
    {
        decode_and_store(e);
        atomic_store(&e->capturing, false);
    }
}

static void* dsp_thread_fn(void* arg)
{
    audio_engine_t* e = (audio_engine_t*)arg;
    float raw[DSP_CHUNK];
    float rs[RS_CHUNK];

    while (atomic_load(&e->running))
    {
        uint32_t n = ring_read(&e->cap_ring, raw, DSP_CHUNK);
        if (n == 0)
        {
            usleep(2000);
            continue;
        }
        int rn = resampler_process(&e->cap_rs, raw, (int)n, rs, RS_CHUNK);
        feed_slot(e, rs, rn);
    }
    return NULL;
}

// -----------------------------------------------------------------------------
// 生命周期
// -----------------------------------------------------------------------------
JNIEXPORT jlong JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeCreate(
    JNIEnv* env, jobject thiz,
    jint protocol, jfloat f_min, jfloat f_max, jint time_osr, jint freq_osr)
{
    audio_engine_t* e = (audio_engine_t*)calloc(1, sizeof(audio_engine_t));
    if (e == NULL)
        return 0;

    monitor_config_t cfg = {
        .f_min = f_min,
        .f_max = f_max,
        .sample_rate = WORK_RATE,
        .time_osr = time_osr,
        .freq_osr = freq_osr,
        .protocol = (protocol == 1) ? FTX_PROTOCOL_FT4 : FTX_PROTOCOL_FT8,
    };

    e->session = ftx_session_create(&cfg);
    if (e->session == NULL)
    {
        free(e);
        return 0;
    }
    e->slot_ms = (protocol == 1) ? (int)(FT4_SLOT_TIME * 1000) : (int)(FT8_SLOT_TIME * 1000);
    e->slot_samples = ftx_session_slot_samples(e->session);
    pthread_mutex_init(&e->res_mutex, NULL);
    return (jlong)(intptr_t)e;
}

JNIEXPORT void JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeDestroy(JNIEnv* env, jobject thiz, jlong handle)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    if (e == NULL)
        return;
    pthread_mutex_destroy(&e->res_mutex);
    ftx_session_free(e->session);
    free(e);
}

// -----------------------------------------------------------------------------
// 采集
// -----------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeStartCapture(
    JNIEnv* env, jobject thiz, jlong handle, jint preferred_rate)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    if (e == NULL)
        return -1;
    if (atomic_load(&e->running))
        return e->in_rate; // 已在运行

    ring_init(&e->cap_ring, CAP_RING_CAP);
    resampler_init(&e->cap_rs, preferred_rate, WORK_RATE);

    AAudioStreamBuilder* builder = NULL;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK)
    {
        ring_free(&e->cap_ring);
        return -2;
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setSampleRate(builder, preferred_rate);
    AAudioStreamBuilder_setDataCallback(builder, capture_callback, e);

    aaudio_result_t r = AAudioStreamBuilder_openStream(builder, &e->in_stream);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK)
    {
        LOGE("open input stream failed: %s", AAudio_convertResultToText(r));
        e->in_stream = NULL;
        ring_free(&e->cap_ring);
        return -3;
    }

    e->in_rate = AAudioStream_getSampleRate(e->in_stream);
    // 设备实际采样率可能与请求不同，按其重新配置重采样器
    resampler_init(&e->cap_rs, e->in_rate, WORK_RATE);

    atomic_store(&e->running, true);
    atomic_store(&e->capturing, false);
    if (pthread_create(&e->dsp_thread, NULL, dsp_thread_fn, e) != 0)
    {
        atomic_store(&e->running, false);
        AAudioStream_close(e->in_stream);
        e->in_stream = NULL;
        ring_free(&e->cap_ring);
        return -4;
    }
    e->dsp_started = true;

    r = AAudioStream_requestStart(e->in_stream);
    if (r != AAUDIO_OK)
    {
        LOGE("start input stream failed: %s", AAudio_convertResultToText(r));
        atomic_store(&e->running, false);
        pthread_join(e->dsp_thread, NULL);
        e->dsp_started = false;
        AAudioStream_close(e->in_stream);
        e->in_stream = NULL;
        ring_free(&e->cap_ring);
        return -5;
    }

    LOGI("capture started: requested=%d actual=%d", preferred_rate, e->in_rate);
    return e->in_rate;
}

JNIEXPORT void JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeStopCapture(JNIEnv* env, jobject thiz, jlong handle)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    if (e == NULL || !atomic_load(&e->running))
        return;

    atomic_store(&e->running, false);
    if (e->dsp_started)
    {
        pthread_join(e->dsp_thread, NULL);
        e->dsp_started = false;
    }
    if (e->in_stream != NULL)
    {
        AAudioStream_requestStop(e->in_stream);
        AAudioStream_close(e->in_stream);
        e->in_stream = NULL;
    }
    atomic_store(&e->capturing, false);
    ring_free(&e->cap_ring);
    LOGI("capture stopped");
}

// -----------------------------------------------------------------------------
// 解码结果轮询（取走并清空）
// -----------------------------------------------------------------------------
JNIEXPORT jobjectArray JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativePollDecoded(
    JNIEnv* env, jobject thiz, jlong handle)
{
    jclass str_cls = (*env)->FindClass(env, "java/lang/String");
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    if (e == NULL || str_cls == NULL)
        return (*env)->NewObjectArray(env, 0, str_cls, NULL);

    pthread_mutex_lock(&e->res_mutex);
    int n = e->result_count;
    jobjectArray result = (*env)->NewObjectArray(env, n, str_cls, NULL);
    for (int i = 0; i < n; ++i)
    {
        jstring s = (*env)->NewStringUTF(env, e->results[i]);
        if (s != NULL)
        {
            (*env)->SetObjectArrayElement(env, result, i, s);
            (*env)->DeleteLocalRef(env, s);
        }
    }
    e->result_count = 0;
    pthread_mutex_unlock(&e->res_mutex);
    return result;
}

// -----------------------------------------------------------------------------
// 播放（阻塞写：无回调，由调用线程直接 write）
// -----------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeStartPlayback(
    JNIEnv* env, jobject thiz, jlong handle, jint preferred_rate)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    if (e == NULL)
        return -1;
    if (e->out_stream != NULL)
        return e->out_rate;

    AAudioStreamBuilder* builder = NULL;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK)
        return -2;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setSampleRate(builder, preferred_rate);
    // 不设置数据回调：使用阻塞式 AAudioStream_write
    aaudio_result_t r = AAudioStreamBuilder_openStream(builder, &e->out_stream);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK)
    {
        LOGE("open output stream failed: %s", AAudio_convertResultToText(r));
        e->out_stream = NULL;
        return -3;
    }

    e->out_rate = AAudioStream_getSampleRate(e->out_stream);
    r = AAudioStream_requestStart(e->out_stream);
    if (r != AAUDIO_OK)
    {
        LOGE("start output stream failed: %s", AAudio_convertResultToText(r));
        AAudioStream_close(e->out_stream);
        e->out_stream = NULL;
        return -4;
    }
    LOGI("playback started: requested=%d actual=%d", preferred_rate, e->out_rate);
    return e->out_rate;
}

JNIEXPORT void JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeStopPlayback(JNIEnv* env, jobject thiz, jlong handle)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    if (e == NULL || e->out_stream == NULL)
        return;
    AAudioStream_requestStop(e->out_stream);
    AAudioStream_close(e->out_stream);
    e->out_stream = NULL;
}

/// 播放一段 12 kHz PCM（内部重采样到输出采样率），返回写入的帧数。
JNIEXPORT jint JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativePlay(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray pcm)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    if (e == NULL || e->out_stream == NULL || pcm == NULL)
        return -1;

    jsize len = (*env)->GetArrayLength(env, pcm);
    if (len <= 0)
        return 0;

    jboolean is_copy = JNI_FALSE;
    jfloat* data = (*env)->GetFloatArrayElements(env, pcm, &is_copy);
    if (data == NULL)
        return -1;

    resampler_t rs;
    resampler_init(&rs, WORK_RATE, e->out_rate);
    int out_cap = (int)((double)len * (double)e->out_rate / (double)WORK_RATE) + 8;
    float* out = (float*)malloc((size_t)out_cap * sizeof(float));
    int out_n = 0;
    if (out != NULL)
        out_n = resampler_process(&rs, data, len, out, out_cap);

    (*env)->ReleaseFloatArrayElements(env, pcm, data, JNI_ABORT);

    int written = 0;
    if (out != NULL)
    {
        while (written < out_n)
        {
            int32_t w = AAudioStream_write(e->out_stream, out + written, out_n - written, 1000000000LL);
            if (w < 0)
            {
                LOGE("write failed: %s", AAudio_convertResultToText(w));
                break;
            }
            written += w;
        }
        free(out);
    }
    return written;
}

// -----------------------------------------------------------------------------
// 状态查询 & 工具
// -----------------------------------------------------------------------------
JNIEXPORT jlongArray JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeGetState(JNIEnv* env, jobject thiz, jlong handle)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    jlong values[10] = { 0 };
    if (e != NULL)
    {
        values[0] = atomic_load(&e->running) ? 1 : 0;
        values[1] = atomic_load(&e->capturing) ? 1 : 0;
        values[2] = e->in_rate;
        values[3] = e->out_rate;
        values[4] = atomic_load(&e->fed_samples);
        values[5] = e->slot_samples;
        values[6] = e->slot_ms;
        values[7] = utc_now_ms();
        values[8] = atomic_load(&e->dropped);
        values[9] = atomic_load(&e->slots_decoded);
    }
    jlongArray result = (*env)->NewLongArray(env, 10);
    if (result != NULL)
        (*env)->SetLongArrayRegion(env, result, 0, 10, values);
    return result;
}

/// 当前 UTC 时间（毫秒）。
JNIEXPORT jlong JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeUtcNowMs(JNIEnv* env, jobject thiz)
{
    return (jlong)utc_now_ms();
}

/// 测试用：把 input 从 inRate 重采样到 outRate。
JNIEXPORT jfloatArray JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeResample(
    JNIEnv* env, jobject thiz, jfloatArray input, jint in_rate, jint out_rate)
{
    if (input == NULL || in_rate <= 0 || out_rate <= 0)
        return NULL;

    jsize len = (*env)->GetArrayLength(env, input);
    jfloat* data = (*env)->GetFloatArrayElements(env, input, NULL);
    if (data == NULL)
        return NULL;

    int out_cap = (int)((double)len * (double)out_rate / (double)in_rate) + 8;
    float* out = (float*)malloc((size_t)out_cap * sizeof(float));
    int out_n = 0;
    if (out != NULL)
    {
        resampler_t rs;
        resampler_init(&rs, in_rate, out_rate);
        out_n = resampler_process(&rs, data, len, out, out_cap);
    }

    (*env)->ReleaseFloatArrayElements(env, input, data, JNI_ABORT);

    jfloatArray result = (*env)->NewFloatArray(env, out_n);
    if (result != NULL && out_n > 0)
        (*env)->SetFloatArrayRegion(env, result, 0, out_n, out);
    free(out);
    return result;
}
