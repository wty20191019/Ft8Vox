#include "ftx_session.h"

#include <stdlib.h>
#include <string.h>
#include <math.h>

#include <ft8/decode.h>
#include <ft8/encode.h>
#include <ft8/constants.h>

// -----------------------------------------------------------------------------
// 解码参数
//
// 默认值与 ft8_lib 官方示例 demo/decode_ft8.c 保持一致；运行期可经
// ftx_session_set_decode_params() 调整。数组按「上限」静态分配，实际使用的
// 条数由 ftx_decode_params_t 控制，因此调参不会改变内存占用或引起分配失败。
// -----------------------------------------------------------------------------
#define K_DEFAULT_MIN_SCORE      10
#define K_DEFAULT_MAX_CANDIDATES 140
#define K_DEFAULT_LDPC_ITERATIONS 25
#define K_DEFAULT_MAX_DECODED    50

/// 候选/解码结果数组的静态上限，必须 ≥ 允许设置的最大值（见 Kotlin 侧 RANGE）。
#define K_MAX_CANDIDATES_LIMIT 512
#define K_MAX_DECODED_LIMIT    128

// waterfall 行流环形缓冲行数（约 600 * 0.08 s ≈ 48 s 的滚动窗口）
#define K_WF_RING_ROWS 600

// -----------------------------------------------------------------------------
// 呼号哈希表：用于 ftx_message_decode() 还原被哈希压缩的呼号。
// ftx_callsign_hash_interface_t 的回调没有上下文参数，故采用进程级全局表。
// -----------------------------------------------------------------------------
#define CALLSIGN_HASHTABLE_SIZE 256

static struct
{
    char callsign[12]; ///< 最多 11 个字符 + 结尾 0
    uint32_t hash;     ///< 高 8 位为年龄，低 22 位为哈希值
} callsign_hashtable[CALLSIGN_HASHTABLE_SIZE];

static int callsign_hashtable_size;

static void hashtable_init(void)
{
    callsign_hashtable_size = 0;
    memset(callsign_hashtable, 0, sizeof(callsign_hashtable));
}

static void hashtable_cleanup(uint8_t max_age)
{
    for (int idx_hash = 0; idx_hash < CALLSIGN_HASHTABLE_SIZE; ++idx_hash)
    {
        if (callsign_hashtable[idx_hash].callsign[0] != '\0')
        {
            uint8_t age = (uint8_t)(callsign_hashtable[idx_hash].hash >> 24);
            if (age > max_age)
            {
                callsign_hashtable[idx_hash].callsign[0] = '\0';
                callsign_hashtable[idx_hash].hash = 0;
                callsign_hashtable_size--;
            }
            else
            {
                callsign_hashtable[idx_hash].hash =
                    (((uint32_t)age + 1u) << 24) | (callsign_hashtable[idx_hash].hash & 0x3FFFFFu);
            }
        }
    }
}

static void hashtable_add(const char* callsign, uint32_t hash)
{
    uint16_t hash10 = (hash >> 12) & 0x3FFu;
    int idx_hash = (hash10 * 23) % CALLSIGN_HASHTABLE_SIZE;
    while (callsign_hashtable[idx_hash].callsign[0] != '\0')
    {
        if (((callsign_hashtable[idx_hash].hash & 0x3FFFFFu) == hash) &&
            (0 == strcmp(callsign_hashtable[idx_hash].callsign, callsign)))
        {
            callsign_hashtable[idx_hash].hash &= 0x3FFFFFu; // 重置年龄
            return;
        }
        idx_hash = (idx_hash + 1) % CALLSIGN_HASHTABLE_SIZE;
    }
    callsign_hashtable_size++;
    strncpy(callsign_hashtable[idx_hash].callsign, callsign, 11);
    callsign_hashtable[idx_hash].callsign[11] = '\0';
    callsign_hashtable[idx_hash].hash = hash;
}

static bool hashtable_lookup(ftx_callsign_hash_type_t hash_type, uint32_t hash, char* callsign)
{
    uint8_t hash_shift = (hash_type == FTX_CALLSIGN_HASH_10_BITS) ? 12
                       : (hash_type == FTX_CALLSIGN_HASH_12_BITS) ? 10
                                                                  : 0;
    uint16_t hash10 = (hash >> (12 - hash_shift)) & 0x3FFu;
    int idx_hash = (hash10 * 23) % CALLSIGN_HASHTABLE_SIZE;
    while (callsign_hashtable[idx_hash].callsign[0] != '\0')
    {
        if (((callsign_hashtable[idx_hash].hash & 0x3FFFFFu) >> hash_shift) == hash)
        {
            strcpy(callsign, callsign_hashtable[idx_hash].callsign);
            return true;
        }
        idx_hash = (idx_hash + 1) % CALLSIGN_HASHTABLE_SIZE;
    }
    callsign[0] = '\0';
    return false;
}

static ftx_callsign_hash_interface_t hash_if = {
    .lookup_hash = hashtable_lookup,
    .save_hash = hashtable_add,
};

// -----------------------------------------------------------------------------
// 幅度 → 线性功率查找表（uint8 幅度按 0.5 dB 步进，覆盖 -120..7.5 dB）
// -----------------------------------------------------------------------------
static float kMagPowerLut[256];
static bool kMagPowerLutReady = false;

static void ensure_mag_power_lut(void)
{
    if (kMagPowerLutReady)
        return;
    for (int i = 0; i < 256; ++i)
    {
        float db = (float)i * 0.5f - 120.0f;
        kMagPowerLut[i] = powf(10.0f, db / 10.0f);
    }
    kMagPowerLutReady = true;
}

// -----------------------------------------------------------------------------
// 会话
// -----------------------------------------------------------------------------
struct ftx_session
{
    monitor_t mon;
    float* pending; ///< 未满一个 block 的余数缓冲，容量 = mon.block_size
    int pending_len;

    // waterfall 行流（供 UI 滚动显示）：环形缓冲，每行 wf_bins 字节
    uint8_t* wf_ring;
    int wf_ring_rows;
    int wf_bins;
    int64_t wf_total;     ///< 累计产生的行数
    int64_t wf_read;      ///< 已读走的行数
    int wf_last_block;    ///< 已发射的最后 block 索引（跨 reset 用 -1 复位）

    ftx_decode_params_t decode; ///< 热生效的解码参数（每次 decode 读取）
};

static int clamp_int(int v, int lo, int hi)
{
    if (v < lo)
        return lo;
    if (v > hi)
        return hi;
    return v;
}

ftx_decode_params_t ftx_decode_params_default(void)
{
    ftx_decode_params_t p = {
        .min_score = K_DEFAULT_MIN_SCORE,
        .max_candidates = K_DEFAULT_MAX_CANDIDATES,
        .ldpc_iterations = K_DEFAULT_LDPC_ITERATIONS,
        .max_decoded = K_DEFAULT_MAX_DECODED,
    };
    return p;
}

/// 与 Kotlin `DecodeSettings` 的 RANGE 对应；越界值在此钳制，native 侧始终安全。
static ftx_decode_params_t sanitize_decode_params(const ftx_decode_params_t* in)
{
    ftx_decode_params_t p = ftx_decode_params_default();
    if (in == NULL)
        return p;
    p.min_score = clamp_int(in->min_score, 4, 40);
    p.max_candidates = clamp_int(in->max_candidates, 20, K_MAX_CANDIDATES_LIMIT);
    p.ldpc_iterations = clamp_int(in->ldpc_iterations, 5, 60);
    p.max_decoded = clamp_int(in->max_decoded, 5, K_MAX_DECODED_LIMIT);
    return p;
}

void ftx_session_set_decode_params(ftx_session_t* session, const ftx_decode_params_t* params)
{
    if (session == NULL)
        return;
    session->decode = sanitize_decode_params(params);
}

/// 把最新一次 monitor_process 产生的 block 转成 time_osr 行写入环形缓冲。
static void session_emit_waterfall(ftx_session_t* s)
{
    if (s->wf_ring == NULL)
        return;

    const ftx_waterfall_t* wf = &s->mon.wf;
    int block = wf->num_blocks - 1;
    if (block < 0 || block <= s->wf_last_block)
        return;
    s->wf_last_block = block;

    for (int ts = 0; ts < wf->time_osr; ++ts)
    {
        const uint8_t* src = wf->mag + (size_t)block * wf->block_stride +
                             (size_t)ts * wf->freq_osr * wf->num_bins;
        uint8_t* dst = s->wf_ring + (size_t)(s->wf_total % s->wf_ring_rows) * s->wf_bins;
        for (int b = 0; b < s->wf_bins; ++b)
            dst[b] = src[b]; // 取 freq_sub = 0（block 内布局为 [ts][freq_sub][bin]）
        s->wf_total++;
    }
}

ftx_session_t* ftx_session_create(const monitor_config_t* cfg)
{
    if (cfg == NULL)
        return NULL;

    ftx_session_t* s = (ftx_session_t*)calloc(1, sizeof(ftx_session_t));
    if (s == NULL)
        return NULL;

    ensure_mag_power_lut();
    monitor_init(&s->mon, cfg);

    s->pending = (float*)calloc((size_t)s->mon.block_size, sizeof(float));
    s->wf_bins = s->mon.wf.num_bins;
    s->wf_ring_rows = K_WF_RING_ROWS;
    s->wf_ring = (uint8_t*)calloc((size_t)s->wf_ring_rows * s->wf_bins, 1);
    s->wf_last_block = -1;
    s->pending_len = 0;

    if (s->pending == NULL || s->wf_ring == NULL)
    {
        free(s->pending);
        free(s->wf_ring);
        monitor_free(&s->mon);
        free(s);
        return NULL;
    }

    hashtable_init();
    s->decode = ftx_decode_params_default();
    return s;
}

void ftx_session_free(ftx_session_t* session)
{
    if (session == NULL)
        return;
    free(session->wf_ring);
    free(session->pending);
    monitor_free(&session->mon);
    free(session);
}

void ftx_session_reset(ftx_session_t* session)
{
    if (session == NULL)
        return;
    session->pending_len = 0;
    monitor_reset(&session->mon);
    session->wf_last_block = -1;
}

void ftx_session_process(ftx_session_t* session, const float* samples, int count)
{
    if (session == NULL || samples == NULL || count <= 0)
        return;

    monitor_t* mon = &session->mon;
    const int bs = mon->block_size;
    int pos = 0;

    // 先补齐上次的余数
    if (session->pending_len > 0)
    {
        int need = bs - session->pending_len;
        int take = (count < need) ? count : need;
        memcpy(session->pending + session->pending_len, samples, (size_t)take * sizeof(float));
        session->pending_len += take;
        pos += take;
        if (session->pending_len < bs)
            return;
        monitor_process(mon, session->pending);
        session_emit_waterfall(session);
        session->pending_len = 0;
    }

    // 处理完整的 block
    while (pos + bs <= count)
    {
        monitor_process(mon, samples + pos);
        session_emit_waterfall(session);
        pos += bs;
    }

    // 余数留存
    int rem = count - pos;
    if (rem > 0)
    {
        memcpy(session->pending, samples + pos, (size_t)rem * sizeof(float));
        session->pending_len = rem;
    }
}

int ftx_session_slot_samples(const ftx_session_t* session)
{
    if (session == NULL)
        return 0;
    return session->mon.wf.max_blocks * session->mon.block_size;
}

int ftx_session_read_waterfall(ftx_session_t* session, uint8_t* out, int max_rows)
{
    if (session == NULL || session->wf_ring == NULL || out == NULL || max_rows <= 0)
        return 0;

    // UI 落后时丢弃已被覆盖的旧行
    int64_t oldest = session->wf_total - session->wf_ring_rows;
    if (session->wf_read < oldest)
        session->wf_read = oldest;

    int64_t avail = session->wf_total - session->wf_read;
    if (avail <= 0)
        return 0;

    int rows = (int)((avail < max_rows) ? avail : max_rows);
    for (int i = 0; i < rows; ++i)
    {
        const uint8_t* src =
            session->wf_ring + (size_t)((session->wf_read + i) % session->wf_ring_rows) * session->wf_bins;
        memcpy(out + (size_t)i * session->wf_bins, src, (size_t)session->wf_bins);
    }
    session->wf_read += rows;
    return rows;
}

void ftx_session_waterfall_info(const ftx_session_t* session, int* bins, float* bin_hz, float* f_min)
{
    if (session == NULL)
        return;
    float sp = session->mon.symbol_period;
    if (bins != NULL)
        *bins = session->mon.wf.num_bins;
    if (bin_hz != NULL)
        *bin_hz = (sp > 0.0f) ? (1.0f / sp) : 0.0f;
    if (f_min != NULL)
        *f_min = (sp > 0.0f) ? ((float)session->mon.min_bin / sp) : 0.0f;
}

// -----------------------------------------------------------------------------
// SNR 估计：按已知音调序列取信号 bin 功率，与同时段的平均 bin 功率比较，
// 换算到 2500 Hz 参考带宽（与 WSJT-X 口径一致）。
// -----------------------------------------------------------------------------
static float measure_snr(const ftx_waterfall_t* wf, const ftx_candidate_t* cand,
                         const ftx_message_t* msg)
{
    int num_tones = (wf->protocol == FTX_PROTOCOL_FT4) ? FT4_NN : FT8_NN;
    uint8_t tones[FT4_NN];
    if (wf->protocol == FTX_PROTOCOL_FT4)
        ft4_encode(msg->payload, tones);
    else
        ft8_encode(msg->payload, tones);

    const int tstride = wf->freq_osr * wf->num_bins;
    int ts = (cand->time_sub < wf->time_osr) ? cand->time_sub : 0;
    int fs = (cand->freq_sub < wf->freq_osr) ? cand->freq_sub : 0;

    float signal_sum = 0.0f;
    int signal_n = 0;
    double noise_sum = 0.0;
    long noise_n = 0;

    for (int i = 0; i < num_tones; ++i)
    {
        int block = cand->time_offset + i;
        if (block < 0 || block >= wf->num_blocks)
            continue;

        const WF_ELEM_T* base =
            wf->mag + (size_t)block * wf->block_stride + (size_t)ts * tstride;

        int fbin = cand->freq_offset + tones[i];
        if (fbin >= 0 && fbin < wf->num_bins)
        {
            signal_sum += kMagPowerLut[base[(size_t)fs * wf->num_bins + fbin]];
            signal_n++;
        }
        // 噪声底：排除整段音调区间（freq_offset .. freq_offset+7）及其邻域，
        // 避免把信号自身的频谱泄漏算进噪声。
        for (int b = 0; b < wf->num_bins; ++b)
        {
            if (b >= cand->freq_offset - 2 && b <= cand->freq_offset + 9)
                continue;
            noise_sum += kMagPowerLut[base[(size_t)fs * wf->num_bins + b]];
            noise_n++;
        }
    }

    if (signal_n <= 0 || noise_n <= 0)
        return -24.0f;

    double ratio = (double)(signal_sum / signal_n) / (noise_sum / noise_n);
    if (ratio <= 0.0)
        return -24.0f;

    float symbol_period = (wf->protocol == FTX_PROTOCOL_FT4) ? FT4_SYMBOL_PERIOD : FT8_SYMBOL_PERIOD;
    float bin_hz = 1.0f / symbol_period;
    return 10.0f * log10f((float)ratio) + 10.0f * log10f(bin_hz / 2500.0f);
}

int ftx_session_decode(ftx_session_t* session, ftx_decode_result_t* results, int max_results)
{
    if (session == NULL || results == NULL || max_results <= 0)
        return 0;

    const ftx_waterfall_t* wf = &session->mon.wf;

    const ftx_decode_params_t p = session->decode;

    // 按 Costas 同步得分定位候选（候选数与最低得分可调）
    ftx_candidate_t candidates[K_MAX_CANDIDATES_LIMIT];
    int num_candidates = ftx_find_candidates(wf, p.max_candidates, candidates, p.min_score);

    // 本时隙已解码消息的哈希表，用于去重
    ftx_message_t decoded[K_MAX_DECODED_LIMIT];
    ftx_message_t* decoded_hashtable[K_MAX_DECODED_LIMIT];
    const int hash_size = p.max_decoded;
    for (int i = 0; i < hash_size; ++i)
        decoded_hashtable[i] = NULL;

    // 输出同时受调用方缓冲（max_results）与设置（max_decoded）限制
    const int out_cap = (p.max_decoded < max_results) ? p.max_decoded : max_results;

    int num_decoded = 0;
    for (int idx = 0; idx < num_candidates; ++idx)
    {
        const ftx_candidate_t* cand = &candidates[idx];

        ftx_message_t message;
        ftx_decode_status_t status;
        if (!ftx_decode_candidate(wf, cand, p.ldpc_iterations, &message, &status))
            continue;

        // 去重
        int idx_hash = message.hash % hash_size;
        bool found_empty = false;
        bool found_duplicate = false;
        do
        {
            if (decoded_hashtable[idx_hash] == NULL)
            {
                found_empty = true;
            }
            else if ((decoded_hashtable[idx_hash]->hash == message.hash) &&
                     (0 == memcmp(decoded_hashtable[idx_hash]->payload, message.payload,
                                  sizeof(message.payload))))
            {
                found_duplicate = true;
            }
            else
            {
                idx_hash = (idx_hash + 1) % hash_size;
            }
        } while (!found_empty && !found_duplicate);

        if (!found_empty)
            continue;

        memcpy(&decoded[idx_hash], &message, sizeof(message));
        decoded_hashtable[idx_hash] = &decoded[idx_hash];

        char text[FTX_MAX_MESSAGE_LENGTH];
        ftx_message_offsets_t offsets;
        if (ftx_message_decode(&message, &hash_if, text, &offsets) != FTX_MESSAGE_RC_OK)
            continue;

        if (num_decoded >= out_cap)
            continue;

        ftx_decode_result_t* out = &results[num_decoded];
        strncpy(out->text, text, FTX_MAX_MESSAGE_LENGTH - 1);
        out->text[FTX_MAX_MESSAGE_LENGTH - 1] = '\0';

        float freq_hz = (session->mon.min_bin + cand->freq_offset +
                         (float)cand->freq_sub / wf->freq_osr) / session->mon.symbol_period;
        float time_sec = (cand->time_offset + (float)cand->time_sub / wf->time_osr) *
                         session->mon.symbol_period;

        out->df = (int)lroundf(freq_hz);
        // 发射/接收的名义起点在时隙起点后 0.5 s；减去它以得到 WSJT-X 口径的 DT
        out->dt = time_sec - 0.5f;
        out->score = cand->score;
        out->snr = (int)lroundf(measure_snr(wf, cand, &message));

        num_decoded++;
    }

    // 呼号哈希表老化（跨时隙保留）
    hashtable_cleanup(10);

    return num_decoded;
}
