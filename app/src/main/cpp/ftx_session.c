#include "ftx_session.h"

#include <stdlib.h>
#include <string.h>
#include <math.h>

#include <ft8/decode.h>
#include <ft8/encode.h>
#include <ft8/constants.h>

// -----------------------------------------------------------------------------
// 临时诊断：逐条打印 SNR 估计的内部量（sig/noi 功率、分位、低位裁剪计数）。
// 用于排查「与 FT8CN/WSJT-X 的 dB 差」究竟来自音频还是估计器。排查完即删。
// -----------------------------------------------------------------------------
#ifdef __ANDROID__
#include <android/log.h>
#define SNR_DIAG(...) __android_log_print(ANDROID_LOG_INFO, "Ft8VoxSnr", __VA_ARGS__)
#else
#define SNR_DIAG(...) ((void)0)
#endif

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
// SNR 估计（JTDX 口径）
//
// 对每个符号 i：
//   信号功率 P_sig = 该符号**实际发送音调**所在 bin 的功率（含信号 + 本底噪声）；
//   噪声功率 P_noi = **同一符号内、紧邻信号但排除音调区间**那一段 bin 的**算术平均**
//                    （只含噪声，且与信号同时、同带）。
//   噪声参考取算术均值、不使用任何分布假设，故不像旧的「低分位 ÷ 指数分布系数」
//   那样，在真实电台音频常见的脉冲/结构化噪声下严重低估噪声底、让 SNR 系统性虚高；
//   同时「只取同一符号、紧邻频段」使其天然抗带内强台与时间起伏。
// 逐符号取比 r_i = P_sig / P_noi，在**功率域（线性域）** 对全部符号求平均
// （等价于先逐符号求信噪比再求平均），转 dB 后减去 26.5 dB 折算到 2500 Hz 参考
// 带宽，最后做经验修正与上下限钳位。
//
// 为什么不直接用「同一符号内其余 7 个音调」做噪声参考（JTDX 的原式）：
//   JTDX 的分析窗是 **1 个符号 + 矩形加权**，8 个音调在窗内严格正交，其余音调 bin
//   只含噪声。本项目瀑布窗是 **周期 Hann、窗长 = 1 符号周期 × freq_osr**（默认
//   freq_osr=2，即约 2 个符号），音调之间不再正交，其余音调 bin 会被**信号自身的
//   跨符号泄漏**污染。该污染与信号幅度成正比，会在强台上把噪声底抬高：
//   harness（合成白噪声，同一份数据）实测 +10 dB 真值时照搬原式会读到 +2.4 dB
//   （偏低约 6 dB），而改成本窗口均值后回到 +8.9 dB。故这里是 JTDX 的「无分布假设
//   的算术均值」+ 本项目窗所允许的噪声窗口位置。
//
// 经验修正（两项）：
//   ① 去偏：噪声参考是 n 个指数分布 bin 的**平均**，E[1/mean_n] = n/((n-1)·N)，
//      比真值大 n/(n-1) 倍（弱台会因此整体偏高），故逐符号乘回 (n-1)/n。
//   ② 窗函数折算：本项目瀑布用周期 Hann 窗，RBW = 1.5·(1/符号周期)/freq_osr，
//      与 JTDX 的 1 符号矩形窗（≈6.25 Hz）不同，故在 JTDX_SNR_CAL_DB 之外再补
//      (rbw_db + 26.5)（freq_osr=2 时 = +0.77 dB，「快」/「深」预设各自不同），
//      避免把窗函数差异算成信号。后续若按实测得到按 SNR 分段的经验曲线，也在此叠加。
// -----------------------------------------------------------------------------
#define SNR_NOISE_WIN 16          // 噪声窗口相对音调区间的额外半宽（bin）；FT8 下 1 bin = 6.25 Hz
#define JTDX_SNR_CAL_DB 26.5f     // JTDX 的 2500 Hz 折算常数
#define JTDX_SNR_MIN_DB (-24.0f)  // 下限：对齐 WSJT-X 的 -24 dB
#define JTDX_SNR_MAX_DB 40.0f     // 上限：防强台读数发散（各版本不同，可按实测调整）

static float measure_snr(const ftx_waterfall_t* wf, const ftx_candidate_t* cand,
                         const ftx_message_t* msg)
{
    int num_symbols = (wf->protocol == FTX_PROTOCOL_FT4) ? FT4_NN : FT8_NN;
    uint8_t tones[FT4_NN];
    if (wf->protocol == FTX_PROTOCOL_FT4)
        ft4_encode(msg->payload, tones);
    else
        ft8_encode(msg->payload, tones);

    const int tstride = wf->freq_osr * wf->num_bins;
    int ts = (cand->time_sub < wf->time_osr) ? cand->time_sub : 0;
    int fs = (cand->freq_sub < wf->freq_osr) ? cand->freq_sub : 0;

    double ratio_sum = 0.0;
    int ratio_n = 0;

    for (int i = 0; i < num_symbols; ++i)
    {
        int block = cand->time_offset + i;
        if (block < 0 || block >= wf->num_blocks)
            continue;

        const WF_ELEM_T* row = wf->mag + (size_t)block * wf->block_stride +
                               (size_t)ts * tstride + (size_t)fs * wf->num_bins;

        int sig_bin = cand->freq_offset + tones[i];
        if (sig_bin < 0 || sig_bin >= wf->num_bins)
            continue;
        double p_sig = (double)kMagPowerLut[row[sig_bin]];

        // 噪声参考：同一符号内、紧邻信号但排除音调区间的一段 bin 的算术平均。
        // 排除 [freq_offset-2, freq_offset+9] 是为了躲开信号自身的主瓣/跨符号泄漏，
        // 否则强台的噪声底会被自己抬高（见文件头说明）。
        int b0 = cand->freq_offset - 2 - SNR_NOISE_WIN;
        int b1 = cand->freq_offset + 9 + SNR_NOISE_WIN;
        if (b0 < 0)
            b0 = 0;
        if (b1 > wf->num_bins - 1)
            b1 = wf->num_bins - 1;
        double p_noi_sum = 0.0;
        int p_noi_n = 0;
        for (int b = b0; b <= b1; ++b)
        {
            if (b >= cand->freq_offset - 2 && b <= cand->freq_offset + 9)
                continue;
            p_noi_sum += (double)kMagPowerLut[row[b]];
            p_noi_n++;
        }
        if (p_noi_n < 2)
            continue;
        double p_noi = p_noi_sum / p_noi_n;

        // 经验修正①：E[1/mean_n] 偏大 n/(n-1)，乘回 (n-1)/n 去偏
        double debias = (double)(p_noi_n - 1) / (double)p_noi_n;
        ratio_sum += (p_sig / p_noi) * debias;
        ratio_n++;
    }

    if (ratio_n <= 0)
        return JTDX_SNR_MIN_DB;

    // 在功率域对全部符号求平均（r_i 的算术平均 ≡ 逐符号信噪比的平均）
    double mean_ratio = ratio_sum / ratio_n;
    // 信号 bin 内本身含噪声，须先扣除单 bin 本底再取值
    double excess = mean_ratio - 1.0;
    if (excess < 1.0e-3)
        excess = 1.0e-3; // 与 WSJT-X 相同的下限，最终会被 JTDX_SNR_MIN_DB 夹住

    // 经验修正②：把 JTDX 的固定折算常数换到本项目窗函数的等效噪声带宽
    //   RBW = 1.5 * (1/symbol_period) / freq_osr，与采样率无关；
    //   freq_osr=2（FT8「标准」）→ RBW = 4.6875 Hz → 10·log10(4.6875/2500) = -27.27 dB。
    float symbol_period = (wf->protocol == FTX_PROTOCOL_FT4) ? FT4_SYMBOL_PERIOD : FT8_SYMBOL_PERIOD;
    float osr = (float)(wf->freq_osr > 0 ? wf->freq_osr : 1);
    float rbw_hz = 1.5f * (1.0f / symbol_period) / osr;
    float window_db = 10.0f * log10f(rbw_hz / 2500.0f) + JTDX_SNR_CAL_DB;

    float snr = 10.0f * log10f((float)excess) - JTDX_SNR_CAL_DB + window_db;
    if (snr > JTDX_SNR_MAX_DB)
        snr = JTDX_SNR_MAX_DB;
    if (snr < JTDX_SNR_MIN_DB)
        snr = JTDX_SNR_MIN_DB;
    // 临时诊断（排查完删）：ratio_db 为未折算的比值，excess_db 为去噪后的单 bin 信噪比。
    SNR_DIAG("fbin=%d fsub=%d ts=%d score=%d n=%d snr=%.1f ratio_db=%.1f excess_db=%.1f win_db=%.2f",
             cand->freq_offset, fs, ts, cand->score, ratio_n, snr,
             10.0f * log10f((float)(mean_ratio > 1.0e-30 ? mean_ratio : 1.0e-30)),
             10.0f * log10f((float)excess), window_db);
    return snr;
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
