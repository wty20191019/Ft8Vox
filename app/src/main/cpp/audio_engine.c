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
#include <dlfcn.h>

#include <common/monitor.h>
#include <ft8/constants.h>

#include "ftx_session.h"
#include "jni_common.h"

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
// 每时隙最多缓存的解码结果条数（≥ DecodeSettings.MAX_DECODED_RANGE 上界）
#define RESULT_CAP 128
#define DECODE_BATCH 128

// 允许在时隙起点后多久开始采集（毫秒）；超过则丢弃等下一个时隙
#define ALIGN_TOLERANCE_MS 200

// 发射前导音：键控 VOX 用的单音（落在 SSB 通带内）与幅度
#define LEAD_TONE_HZ 1000
#define LEAD_TONE_AMP 0.6f

// VOX 电平下限（dBFS），低于视为静音
#define VOX_LEVEL_FLOOR_DB (-100.0f)

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
    ftx_decode_result_t results[RESULT_CAP];
    int64_t result_slot_ms[RESULT_CAP];
    int result_count;

    // 统计
    _Atomic int64_t dropped;
    _Atomic int64_t slots_decoded;
    _Atomic int64_t fed_samples;
    _Atomic int64_t last_slot;

    // ---- VOX / PTT 配置（Kotlin 热设置，见 nativeSetVox） ----
    _Atomic int vox_trigger;       ///< 0=音频检测（有声触发） 1=静音检测（无声触发）
    _Atomic int vox_threshold_db;  ///< 触发门限（dBFS）
    _Atomic int vox_delay_ms;      ///< 状态翻转去抖时长（ms）
    _Atomic int ptt_delay_ms;      ///< 前导静音（ms，为声卡路由切换留时间）
    _Atomic int lead_tone_ms;      ///< 发射前导音时长（ms，0=关）
    _Atomic int watchdog_ms;       ///< 单次发射写入看门狗（ms，native 会抬高到发射时长以上）

    // ---- 音频路由 / 增益（U7c） ----
    _Atomic int in_gain_db;        ///< 采集增益（dB），在 DSP 线程对原始样本生效

    // ---- VOX 运行状态（dsp 线程写，任意线程读） ----
    _Atomic int vox_level_db_x10;  ///< 平滑后的输入电平（dBFS × 10）
    _Atomic int vox_open;          ///< 1=VOX 判定为已触发
    _Atomic int vox_candidate;     ///< 去抖中的候选状态
    _Atomic int64_t vox_change_ms; ///< 候选状态最近一次变化时刻
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
    ftx_decode_result_t results[DECODE_BATCH];
    int n = ftx_session_decode(e->session, results, DECODE_BATCH);
    atomic_fetch_add(&e->slots_decoded, 1);
    if (n <= 0)
        return;

    // 本次解码对应的时隙起点（用于 UI 显示时间）
    int64_t slot_start_ms = atomic_load(&e->last_slot) * e->slot_ms;

    pthread_mutex_lock(&e->res_mutex);
    for (int i = 0; i < n && e->result_count < RESULT_CAP; ++i)
    {
        e->results[e->result_count] = results[i];
        e->result_slot_ms[e->result_count] = slot_start_ms;
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

/// 计算一块样本的 RMS 电平（dBFS，低于下限返回 [VOX_LEVEL_FLOOR_DB]）。
static float block_level_db(const float* x, int n)
{
    if (n <= 0)
        return VOX_LEVEL_FLOOR_DB;
    double sum = 0.0;
    for (int i = 0; i < n; ++i)
        sum += (double)x[i] * (double)x[i];
    double rms = sqrt(sum / (double)n);
    if (rms < 1e-5)
        return VOX_LEVEL_FLOOR_DB;
    float db = 20.0f * log10f((float)rms);
    return db < VOX_LEVEL_FLOOR_DB ? VOX_LEVEL_FLOOR_DB : db;
}

/**
 * 用最新输入块更新 VOX 电平与状态（仅 dsp 线程调用）。
 *
 * 无 CAT 时 App 无法读取电台真实的 PTT/VOX 键控状态，这里用**输入音频电平**
 * 近似判定信道是否活动，仅供状态栏/音频速览显示，不参与发射门控：
 *  - 音频检测：电平 ≥ 阈值 视为「触发/有信号」；
 *  - 静音检测：电平 < 阈值 视为「静音/空闲」；
 * 候选状态需持续 [vox_delay_ms] 才翻转，避免抖动。
 */
static void update_vox(audio_engine_t* e, const float* x, int n)
{
    float db = block_level_db(x, n);
    float prev = (float)atomic_load(&e->vox_level_db_x10) / 10.0f;
    // 快攻击、慢释放，避免电平条抖动
    float a = (db > prev) ? 0.6f : 0.15f;
    float sm = prev + a * (db - prev);
    if (sm < VOX_LEVEL_FLOOR_DB)
        sm = VOX_LEVEL_FLOOR_DB;
    atomic_store(&e->vox_level_db_x10, (int)lroundf(sm * 10.0f));

    int trigger = atomic_load(&e->vox_trigger);
    int thr = atomic_load(&e->vox_threshold_db);
    int delay = atomic_load(&e->vox_delay_ms);
    int desired = (trigger == 1) ? (sm < (float)thr) : (sm >= (float)thr);

    int64_t now = utc_now_ms();
    if (desired != atomic_load(&e->vox_candidate))
    {
        atomic_store(&e->vox_candidate, desired);
        atomic_store(&e->vox_change_ms, now);
        return;
    }
    if (atomic_load(&e->vox_open) != desired &&
        (now - atomic_load(&e->vox_change_ms)) >= delay)
    {
        atomic_store(&e->vox_open, desired);
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
        // U7c：采集增益（dB→线性），限幅避免超过满幅回绕
        int gdb = atomic_load(&e->in_gain_db);
        if (gdb != 0)
        {
            float g = powf(10.0f, (float)gdb / 20.0f);
            for (uint32_t i = 0; i < n; i++)
            {
                float v = raw[i] * g;
                raw[i] = v > 1.0f ? 1.0f : (v < -1.0f ? -1.0f : v);
            }
        }
        int rn = resampler_process(&e->cap_rs, raw, (int)n, rs, RS_CHUNK);
        update_vox(e, raw, (int)n);
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

    // VOX / PTT 默认值（随后由 Kotlin 下发覆盖）
    atomic_store(&e->vox_trigger, 0);
    atomic_store(&e->vox_threshold_db, -40);
    atomic_store(&e->vox_delay_ms, 300);
    atomic_store(&e->ptt_delay_ms, 0);
    atomic_store(&e->lead_tone_ms, 0);
    atomic_store(&e->watchdog_ms, 10000);
    atomic_store(&e->vox_level_db_x10, (int)(VOX_LEVEL_FLOOR_DB * 10.0f));
    atomic_store(&e->vox_open, 0);
    atomic_store(&e->vox_candidate, 0);
    atomic_store(&e->vox_change_ms, 0);
    atomic_store(&e->in_gain_db, 0);
    return (jlong)(intptr_t)e;
}

JNIEXPORT void JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeSetDecodeParams(
    JNIEnv* env, jobject thiz, jlong handle,
    jint min_score, jint max_candidates, jint ldpc_iterations, jint max_decoded)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    if (e == NULL || e->session == NULL)
        return;
    ftx_decode_params_t p = {
        .min_score = min_score,
        .max_candidates = max_candidates,
        .ldpc_iterations = ldpc_iterations,
        .max_decoded = max_decoded,
    };
    ftx_session_set_decode_params(e->session, &p);
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

/// AAudioStreamBuilder_setInputPreset 自 API 28 起提供；为了在 minSdk 26/27 上
/// 也能安全调用，这里用 dlopen 解析符号（老系统上返回 NULL，则退回系统默认预设）。
typedef void (*ft8vox_set_input_preset_fn)(AAudioStreamBuilder*, aaudio_input_preset_t);

static ft8vox_set_input_preset_fn resolve_set_input_preset(void)
{
    static ft8vox_set_input_preset_fn fn = NULL;
    static int tried = 0;
    if (!tried)
    {
        tried = 1;
        void* lib = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
        if (lib != NULL)
            fn = (ft8vox_set_input_preset_fn)dlsym(lib, "AAudioStreamBuilder_setInputPreset");
    }
    return fn;
}

JNIEXPORT jint JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeStartCapture(
    JNIEnv* env, jobject thiz, jlong handle, jint preferred_rate, jint device_id)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    if (e == NULL)
        return -1;
    if (atomic_load(&e->running))
        return e->in_rate; // 已在运行

    ring_init(&e->cap_ring, CAP_RING_CAP);
    resampler_init(&e->cap_rs, preferred_rate, WORK_RATE);

    // FT8/FT4 是纯数据信号，必须绕开系统的语音处理链路（降噪 / AGC / 高通）。
    // 否则：噪声底被压掉 → 解出的 SNR 虚高；弱信号被当作噪声抑制掉 → 瀑布上看不见。
    // UNPROCESSED 并非所有机型都支持，按 UNPROCESSED → VOICE_RECOGNITION → GENERIC
    // 依次回退（VOICE_RECOGNITION 在各平台上通常不做 AGC）。
    static const aaudio_input_preset_t kInputPresets[] = {
        AAUDIO_INPUT_PRESET_UNPROCESSED,
        AAUDIO_INPUT_PRESET_VOICE_RECOGNITION,
        AAUDIO_INPUT_PRESET_GENERIC,
    };
    const size_t kInputPresetCount = sizeof(kInputPresets) / sizeof(kInputPresets[0]);

    aaudio_result_t r = AAUDIO_ERROR_INTERNAL;
    for (size_t pi = 0; pi < kInputPresetCount; ++pi)
    {
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
        // setInputPreset 自 API 28 起提供；API 26/27 上解析不到则退回系统默认预设
        ft8vox_set_input_preset_fn set_preset = resolve_set_input_preset();
        if (set_preset != NULL)
            set_preset(builder, kInputPresets[pi]);
        // U7c：指定输入设备（AudioManager 的设备 id）；<=0 表示系统默认
        if (device_id > 0)
            AAudioStreamBuilder_setDeviceId(builder, device_id);
        AAudioStreamBuilder_setDataCallback(builder, capture_callback, e);

        r = AAudioStreamBuilder_openStream(builder, &e->in_stream);
        AAudioStreamBuilder_delete(builder);
        if (r == AAUDIO_OK)
        {
            LOGI("capture input preset = %d", (int)kInputPresets[pi]);
            break;
        }
        LOGW("open input stream failed (preset %d): %s", (int)kInputPresets[pi],
             AAudio_convertResultToText(r));
        e->in_stream = NULL;
    }
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
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    jclass cls = ft8vox_decode_result_class(env);
    jmethodID ctor = (cls != NULL)
        ? (*env)->GetMethodID(env, cls, "<init>", FT8VOX_DECODE_RESULT_CTOR)
        : NULL;
    if (e == NULL || cls == NULL || ctor == NULL)
        return (*env)->NewObjectArray(env, 0,
                                      (cls != NULL) ? cls : (*env)->FindClass(env, "java/lang/Object"), NULL);

    pthread_mutex_lock(&e->res_mutex);
    int n = e->result_count;
    jobjectArray result = (*env)->NewObjectArray(env, n, cls, NULL);
    if (result != NULL)
    {
        for (int i = 0; i < n; ++i)
        {
            jobject obj = ft8vox_make_decode_result(env, cls, ctor, &e->results[i],
                                                    (jlong)e->result_slot_ms[i]);
            if (obj != NULL)
            {
                (*env)->SetObjectArrayElement(env, result, i, obj);
                (*env)->DeleteLocalRef(env, obj);
            }
        }
    }
    e->result_count = 0;
    pthread_mutex_unlock(&e->res_mutex);
    return result;
}

// waterfall 静态信息：返回 [bins, binHz*1000, fMin*1000]。
JNIEXPORT jintArray JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeWaterfallInfo(
    JNIEnv* env, jobject thiz, jlong handle)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    int bins = 0;
    float bin_hz = 0.0f;
    float f_min = 0.0f;
    if (e != NULL)
        ftx_session_waterfall_info(e->session, &bins, &bin_hz, &f_min);

    jint values[3] = { (jint)bins, (jint)lroundf(bin_hz * 1000.0f), (jint)lroundf(f_min * 1000.0f) };
    jintArray result = (*env)->NewIntArray(env, 3);
    if (result != NULL)
        (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    return result;
}

// 取走新产生的 waterfall 行（每行 bins 字节），供瀑布 UI 滚动显示。
JNIEXPORT jbyteArray JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativePollWaterfall(
    JNIEnv* env, jobject thiz, jlong handle, jint max_rows)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    if (e == NULL || max_rows <= 0)
        return (*env)->NewByteArray(env, 0);

    int bins = 0;
    ftx_session_waterfall_info(e->session, &bins, NULL, NULL);
    if (bins <= 0)
        return (*env)->NewByteArray(env, 0);

    uint8_t* buf = (uint8_t*)malloc((size_t)bins * (size_t)max_rows);
    if (buf == NULL)
        return (*env)->NewByteArray(env, 0);

    int rows = ftx_session_read_waterfall(e->session, buf, max_rows);
    jbyteArray result = (*env)->NewByteArray(env, rows * bins);
    if (result != NULL && rows > 0)
        (*env)->SetByteArrayRegion(env, result, 0, rows * bins, (const jbyte*)buf);

    free(buf);
    return result;
}

// -----------------------------------------------------------------------------
// 播放（阻塞写：无回调，由调用线程直接 write）
// -----------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeStartPlayback(
    JNIEnv* env, jobject thiz, jlong handle, jint preferred_rate, jint device_id)
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
    // U7c：指定输出设备（AudioManager 的设备 id）；<=0 表示系统默认
    if (device_id > 0)
        AAudioStreamBuilder_setDeviceId(builder, device_id);
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

/// 阻塞写入浮点样本，带看门狗；返回写入的帧数。
///
/// 看门狗只作「卡死保护」：若音频设备异常导致写入远超本段音频的播放时长
/// （预期时长 + 3 s），则中止写循环，避免发射协程永久阻塞。合法发射
/// （FT8 整时隙约 15 s）不会被截断，因此实际生效阈值会按需抬高。
static int write_blocking(audio_engine_t* e, const float* buf, int n)
{
    int64_t expected_ms =
        (e->out_rate > 0) ? (int64_t)((double)n * 1000.0 / (double)e->out_rate) : 0;
    int64_t wd = (int64_t)atomic_load(&e->watchdog_ms);
    if (wd < expected_ms + 3000)
        wd = expected_ms + 3000;
    int64_t deadline = utc_now_ms() + wd;

    int written = 0;
    while (written < n)
    {
        int32_t w = AAudioStream_write(e->out_stream, buf + written, n - written, 1000000000LL);
        if (w < 0)
        {
            LOGE("write failed: %s", AAudio_convertResultToText(w));
            break;
        }
        written += w;
        if (utc_now_ms() > deadline)
        {
            LOGW("tx watchdog abort: %d/%d frames (wd=%lld ms)", written, n, (long long)wd);
            break;
        }
    }
    return written;
}

/**
 * 播放一段 12 kHz 发射 PCM（内部重采样到输出采样率）。
 *
 * 在数据前依次插入 [ptt_silence_ms] 的静音与 [lead_tone_ms] 的单音，
 * 用于在 VOX-only 场景下让电台在 FT8 数据到达前完成键控。调用方需把
 * 播放入口提前 `(ptt_silence_ms + lead_tone_ms)`，数据才落在时隙起点。
 *
 * 注意：发射波形遵循 WSJT-X 约定，时隙起点后仍有 0.5 s 保护静音，因此
 * 前导音结束后到实际 FT8 波形之间会有一段静音；电台 VOX 的释放延时
 * （hang time）需覆盖该 0.5 s，否则可能中途掉键。
 * 返回写入的帧数（<0 表示失败）。
 */
static int play_pcm(audio_engine_t* e, const float* data, int len,
                    int ptt_silence_ms, int lead_tone_ms)
{
    resampler_t rs;
    resampler_init(&rs, WORK_RATE, e->out_rate);

    if (ptt_silence_ms < 0)
        ptt_silence_ms = 0;
    if (lead_tone_ms < 0)
        lead_tone_ms = 0;
    int pre = (int)((double)(ptt_silence_ms + lead_tone_ms) * (double)e->out_rate / 1000.0);
    if (pre < 0)
        pre = 0;
    int ptt_n = (int)((double)ptt_silence_ms * (double)e->out_rate / 1000.0);
    if (ptt_n > pre)
        ptt_n = pre;

    int out_cap = (int)((double)len * (double)e->out_rate / (double)WORK_RATE) + pre + 8;
    float* out = (float*)calloc((size_t)out_cap, sizeof(float));
    if (out == NULL)
        return -1;

    // 前导音：紧接在前导静音之后，直到数据开始
    double step = 2.0 * M_PI * (double)LEAD_TONE_HZ / (double)e->out_rate;
    double phase = 0.0;
    for (int i = ptt_n; i < pre; ++i)
    {
        out[i] = LEAD_TONE_AMP * (float)sin(phase);
        phase += step;
    }

    int out_n = pre;
    out_n += resampler_process(&rs, data, len, out + out_n, out_cap - out_n);

    int written = write_blocking(e, out, out_n);
    free(out);
    return written;
}

// 前置声明：nativePlay 委托给带前导的 nativePlayTx
JNIEXPORT jint JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativePlayTx(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray pcm,
    jint ptt_silence_ms, jint lead_tone_ms);

/// 播放一段 12 kHz PCM（无前导），返回写入的帧数。
JNIEXPORT jint JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativePlay(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray pcm)
{
    return Java_com_example_ft8vox_engine_AudioEngine_nativePlayTx(
        env, thiz, handle, pcm, 0, 0);
}

/**
 * 播放一段 12 kHz 发射 PCM，带 PTT 前导静音与发射前导音。
 * @param ptt_silence_ms 数据前的静音时长（ms）
 * @param lead_tone_ms   数据前的单音时长（ms，0=关）
 * @return 写入的帧数（<0 表示失败）
 */
JNIEXPORT jint JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativePlayTx(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray pcm,
    jint ptt_silence_ms, jint lead_tone_ms)
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

    int written = play_pcm(e, data, (int)len, ptt_silence_ms, lead_tone_ms);

    (*env)->ReleaseFloatArrayElements(env, pcm, data, JNI_ABORT);
    return written;
}

/**
 * 播放一段测试单音（用于 VOX 键控/音量联调）。
 * @param freq_hz     单音频率（Hz）
 * @param duration_ms 时长（ms）
 * @return 写入的帧数（<0 表示失败）
 */
JNIEXPORT jint JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativePlayTone(
    JNIEnv* env, jobject thiz, jlong handle, jint freq_hz, jint duration_ms)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    if (e == NULL || e->out_stream == NULL)
        return -1;
    if (freq_hz <= 0 || duration_ms <= 0 || e->out_rate <= 0)
        return 0;

    int n = (int)((double)e->out_rate * (double)duration_ms / 1000.0);
    if (n <= 0)
        return 0;
    float* buf = (float*)calloc((size_t)n, sizeof(float));
    if (buf == NULL)
        return -1;

    double step = 2.0 * M_PI * (double)freq_hz / (double)e->out_rate;
    double phase = 0.0;
    // 首尾各 10 ms 余弦包络，避免爆音
    int ramp = (int)(0.01 * e->out_rate);
    if (ramp > n / 2)
        ramp = n / 2;
    for (int i = 0; i < n; ++i)
    {
        double env = 1.0;
        if (ramp > 0)
        {
            if (i < ramp)
                env = (1.0 - cos(M_PI * (double)i / (double)ramp)) / 2.0;
            else if (i >= n - ramp)
                env = (1.0 - cos(M_PI * (double)(n - 1 - i) / (double)ramp)) / 2.0;
        }
        buf[i] = 0.5f * (float)(env * sin(phase));
        phase += step;
    }

    int written = write_blocking(e, buf, n);
    free(buf);
    return written;
}

/**
 * 下发 VOX / PTT 配置（热生效）。
 * @param trigger       0=音频检测 1=静音检测
 * @param threshold_db  触发门限（dBFS）
 * @param delay_ms      状态翻转去抖（ms）
 * @param ptt_delay_ms  发射前导静音（ms）
 * @param lead_tone_ms  发射前导音时长（ms，0=关）
 * @param watchdog_ms   发射写入看门狗（ms）
 */
JNIEXPORT void JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeSetVox(
    JNIEnv* env, jobject thiz, jlong handle,
    jint trigger, jint threshold_db, jint delay_ms,
    jint ptt_delay_ms, jint lead_tone_ms, jint watchdog_ms)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    if (e == NULL)
        return;
    atomic_store(&e->vox_trigger, trigger == 1 ? 1 : 0);
    atomic_store(&e->vox_threshold_db, threshold_db);
    atomic_store(&e->vox_delay_ms, delay_ms < 0 ? 0 : delay_ms);
    atomic_store(&e->ptt_delay_ms, ptt_delay_ms < 0 ? 0 : ptt_delay_ms);
    atomic_store(&e->lead_tone_ms, lead_tone_ms < 0 ? 0 : lead_tone_ms);
    atomic_store(&e->watchdog_ms, watchdog_ms < 0 ? 0 : watchdog_ms);
}

/**
 * 下发采集增益（热生效，U7c）。
 *
 * 增益在 DSP 线程对原始采集样本生效（含 VOX 电平判定），因此调大增益会同时
 * 抬高 VOX 读数。超过满幅的样本会被限幅到 [-1, 1]，避免回绕爆音。
 *
 * @param gain_db 增益（dB），合法范围 -12..30
 */
JNIEXPORT void JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeSetInputGain(
    JNIEnv* env, jobject thiz, jlong handle, jint gain_db)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    if (e == NULL)
        return;
    if (gain_db < -12)
        gain_db = -12;
    if (gain_db > 30)
        gain_db = 30;
    atomic_store(&e->in_gain_db, gain_db);
}

// -----------------------------------------------------------------------------
// 状态查询 & 工具
// -----------------------------------------------------------------------------
JNIEXPORT jlongArray JNICALL
Java_com_example_ft8vox_engine_AudioEngine_nativeGetState(JNIEnv* env, jobject thiz, jlong handle)
{
    audio_engine_t* e = (audio_engine_t*)(intptr_t)handle;
    jlong values[12] = { 0 };
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
        values[10] = atomic_load(&e->vox_open) ? 1 : 0;
        values[11] = atomic_load(&e->vox_level_db_x10);
    }
    jlongArray result = (*env)->NewLongArray(env, 12);
    if (result != NULL)
        (*env)->SetLongArrayRegion(env, result, 0, 12, values);
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
