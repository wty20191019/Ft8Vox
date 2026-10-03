/**
 * @file ft8api.c
 * @brief FT8 编解码 C 层公共 API 实现。
 *
 * 结构：
 *   - 编码：文本 -> 77bit -> 79 音调 -> GFSK 波形
 *   - 解码：音频 -> STFT 瀑布 -> Costas 候选 -> LLR -> LDPC(BP) -> CRC -> 文本
 *   - 候选解码支持多线程并行（按候选区间切分，主线程合并去重）
 *
 * 底层复用了 ft8_lib（MIT）的核心模块，见工程根目录 NOTICE。
 */
#include "ft8api.h"
#include "ft8_thread.h"
#include "ft8_hash.h"
#include "ft8_gfsk.h"
#include "ft8_subtract.h"
#include "ft8_spectrum.h"

#include <ft8/constants.h>
#include <ft8/crc.h>
#include <ft8/decode.h>
#include <ft8/encode.h>
#include <ft8/message.h>
#include <ft8/text.h>
#include <common/monitor.h>

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <math.h>
#include <stdbool.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define FT8_ENCODE_SYMBOL_BT 2.0f

/* SNR 标定：把 ft8_spectrum_snr() 的原始指标映射到 WSJT-X 参考量纲。
 *
 * P2.2 收尾口径为「频谱噪声底」：ft8_spectrum_snr() 返回
 *     z = 10*log10(Σ正确音调功率) - sbase[f]。
 * 先在 z 域做斜率=1 的偏移标定得到 x = z - 94.6；再用 test/ 全量命中对
 * WSJT-X 参考的实测回归 ref = a*x + b（a=0.835526, b=-0.812361）做**直接回归修正**：
 *     上报 = a*x + b = a*z + (a*(-94.6) + b)
 * 使「参考 vs 上报」回归斜率=1、MAE 最小（3.51→3.35 dB，r1 方案）。
 * 注：该修正会将强信号略向均值收缩，属最小误差意义下的最优标定。 */
#define FT8_SNR_CALIB_SLOPE       0.835526f
#define FT8_SNR_CALIB_INTERCEPT   (-79.8531f)

/* 最近一次错误信息（简单全局，解码通常单调用者） */
static char s_last_error[256];

static void set_error(const char* msg)
{
    snprintf(s_last_error, sizeof(s_last_error), "%s", msg ? msg : "");
}

const char* ft8_last_error(void)
{
    return s_last_error;
}

const char* ft8_version(void)
{
    return "0.1.0";
}

/* ========================================================================= */
/*  默认配置                                                                  */
/* ========================================================================= */

void ft8_decode_config_default(ft8_decode_config_t* cfg)
{
    if (!cfg)
        return;
    memset(cfg, 0, sizeof(*cfg));
    cfg->f_min_hz = 200.0f;
    cfg->f_max_hz = 3000.0f;
    cfg->sample_rate = 12000;
    cfg->time_osr = 2;
    cfg->freq_osr = 2;
    cfg->max_candidates = 140;
    cfg->min_sync_score = 10;
    cfg->ldpc_iterations = 25;
    cfg->decode_depth = 0;
    cfg->ap_mode = 0;
    cfg->my_call = NULL;
    cfg->his_call = NULL;
    cfg->his_grid = NULL;
    cfg->enable_subtract = false;
    cfg->num_threads = 1;
    cfg->return_duplicates = false;
    cfg->osd_depth = 2; /**< 默认启用 OSD 2 阶（历史数据：+14 命中 / extra 3.3%） */
    cfg->enable_llr_refine = false; /**< P2.3 实验项，默认关闭 */
    cfg->num_bands = 1; /**< P3：默认不分段，保持既有单段行为 */
}

void ft8_encode_config_default(ft8_encode_config_t* cfg)
{
    if (!cfg)
        return;
    memset(cfg, 0, sizeof(*cfg));
    cfg->sample_rate = 12000;
    cfg->base_freq_hz = 1000.0f;
    cfg->amplitude = 0.5f;
    cfg->symbol_bt = FT8_ENCODE_SYMBOL_BT;
    cfg->lead_in_sec = 0.0f;
    cfg->tail_sec = 0.0f;
}

/* ========================================================================= */
/*  编码：GFSK 波形合成                                                       */
/* ========================================================================= */

/// 使用 GFSK 相位整形合成波形，输出 n_sym 个符号（n_sym * n_spsym 个采样）。
static bool synth_gfsk(const uint8_t* symbols, int n_sym, float f0, float symbol_bt,
                       float symbol_period, int signal_rate, float* signal)
{
    int n_spsym = (int)(0.5f + signal_rate * symbol_period);
    int n_wave = n_sym * n_spsym;
    float hmod = 1.0f;
    float dphi_peak = 2 * (float)M_PI * hmod / n_spsym;

    float* dphi = (float*)malloc((size_t)(n_wave + 2 * n_spsym) * sizeof(float));
    float* pulse = (float*)malloc((size_t)(3 * n_spsym) * sizeof(float));
    if (!dphi || !pulse)
    {
        free(dphi);
        free(pulse);
        return false;
    }

    for (int i = 0; i < n_wave + 2 * n_spsym; ++i)
    {
        dphi[i] = 2 * (float)M_PI * f0 / signal_rate;
    }

    ft8_gfsk_pulse(n_spsym, symbol_bt, pulse);

    for (int i = 0; i < n_sym; ++i)
    {
        int ib = i * n_spsym;
        for (int j = 0; j < 3 * n_spsym; ++j)
        {
            dphi[j + ib] += dphi_peak * symbols[i] * pulse[j];
        }
    }

    /* 首尾补两个假符号，使相位平滑 */
    for (int j = 0; j < 2 * n_spsym; ++j)
    {
        dphi[j] += dphi_peak * pulse[j + n_spsym] * symbols[0];
        dphi[j + n_sym * n_spsym] += dphi_peak * pulse[j] * symbols[n_sym - 1];
    }

    float phi = 0;
    for (int k = 0; k < n_wave; ++k)
    {
        signal[k] = sinf(phi);
        phi = fmodf(phi + dphi[k + n_spsym], 2 * (float)M_PI);
    }

    /* 首尾包络平滑，降低带外与 VOX 起控冲击 */
    int n_ramp = n_spsym / 8;
    for (int i = 0; i < n_ramp; ++i)
    {
        float env = (1 - cosf(2 * (float)M_PI * i / (2 * n_ramp))) / 2;
        signal[i] *= env;
        signal[n_wave - 1 - i] *= env;
    }

    free(dphi);
    free(pulse);
    return true;
}

int ft8_encode_tones(const char* message, uint8_t* tones)
{
    if (!message || !tones)
    {
        set_error("message/tones 为空");
        return FT8_ERR_ARG;
    }

    ftx_message_t msg;
    ftx_message_init(&msg);

    /* 编码时无需呼号哈希（标准呼号会顺便被缓存进全局哈希表） */
    ftx_message_rc_t rc = ftx_message_encode(&msg, &ft8_hash_if, message);
    if (rc != FTX_MESSAGE_RC_OK)
    {
        set_error("消息无法打包为 FT8 payload");
        return FT8_ERR_ENCODE;
    }

    ft8_encode(msg.payload, tones);
    return FT8_OK;
}

int ft8_encode_message(const char* message, const ft8_encode_config_t* cfg,
                       float* out, int max_samples)
{
    if (!message || !cfg)
    {
        set_error("message/cfg 为空");
        return FT8_ERR_ARG;
    }

    int sample_rate = (cfg->sample_rate > 0) ? cfg->sample_rate : 12000;
    float amplitude = cfg->amplitude;
    if (!(amplitude > 0.0f))
        amplitude = 0.5f;
    if (amplitude > 1.0f)
        amplitude = 1.0f;
    float symbol_bt = (cfg->symbol_bt > 0.0f) ? cfg->symbol_bt : FT8_ENCODE_SYMBOL_BT;
    float base_freq = (cfg->base_freq_hz > 0.0f) ? cfg->base_freq_hz : 1000.0f;
    float symbol_period = FT8_SYMBOL_PERIOD;

    uint8_t tones[FT8_NN];
    int rc = ft8_encode_tones(message, tones);
    if (rc != FT8_OK)
        return rc;

    int n_spsym = (int)(0.5f + sample_rate * symbol_period);
    int num_signal = FT8_NN * n_spsym;
    int num_lead = (cfg->lead_in_sec > 0.0f) ? (int)(cfg->lead_in_sec * sample_rate) : 0;
    int num_tail = (cfg->tail_sec > 0.0f) ? (int)(cfg->tail_sec * sample_rate) : 0;
    int total = num_lead + num_signal + num_tail;

    if (!out)
        return total; /* 查询所需长度 */

    if (max_samples < total)
    {
        set_error("输出缓冲不足");
        return FT8_ERR_NO_OUT;
    }

    if (num_lead > 0)
        memset(out, 0, (size_t)num_lead * sizeof(float));
    if (num_tail > 0)
        memset(out + num_lead + num_signal, 0, (size_t)num_tail * sizeof(float));

    if (!synth_gfsk(tones, FT8_NN, base_freq, symbol_bt, symbol_period, sample_rate, out + num_lead))
    {
        set_error("波形合成内存不足");
        return FT8_ERR_NOMEM;
    }

    for (int i = 0; i < num_signal; ++i)
    {
        out[num_lead + i] *= amplitude;
    }

    return total;
}

/* ========================================================================= */
/*  解码                                                                      */
/* ========================================================================= */

typedef struct
{
    int cand_index;                 /**< 原始候选序号，用于恢复排序 */
    ftx_message_t message;          /**< 解出的消息 */
    ftx_decode_status_t status;     /**< 解码状态（CRC 等） */
    int score;                      /**< Costas 同步分 */
    float snr_raw;                  /**< 原始 SNR 指标 (dB)，未标定（P2.2 逐符号频谱） */
    float snr_order;                /**< 用于信号消除排序的 SNR（旧瀑布域口径，保持解码行为稳定） */
} raw_decode_t;

/** 单遍解码命中（含频率/时间，供多遍信号消除使用）。 */
typedef struct
{
    ftx_message_t message;
    ftx_decode_status_t status;
    int score;
    float snr_raw;
    float snr_order;
    float freq; /**< 音调 0 频率 (Hz) */
    float time; /**< 消息起点偏移 (s) */
} pass_hit_t;

typedef struct
{
    const monitor_t* mon;
    const ftx_waterfall_t* wf;
    const ftx_candidate_t* cands;
    const float* samples; /**< 本遍时域样本（用于逐符号频谱 SNR），可为 NULL */
    int num_samples;
    int sample_rate;
    int start;
    int end;
    int ldpc_iterations;
    int osd_depth;
    int enable_llr_refine; /**< P2.3：BP 失败候选用时域精化 LLR 重解 */
    raw_decode_t* out;
    int count;
} decode_worker_arg_t;

/* 判断消息是否已在 seen 中出现（按哈希 + 载荷去重）。 */
static bool hit_seen_before(const ftx_message_t* m, const ftx_message_t* seen, int n);

/* 对单条保留命中计算时域逐符号 SNR；失败时退回瀑布域 snr_order。 */
static void snr_batch_one(pass_hit_t* h, const float* samples, int num_samples, int sample_rate,
                          const float* sbase, int nbin)
{
    uint8_t tones[FT8_NN];
    ft8_encode(h->message.payload, tones);
    float s = ft8_spectrum_snr(samples, num_samples, sample_rate, tones, h->freq, h->time,
                               sbase, nbin);
    h->snr_raw = (s != 0.0f) ? s : h->snr_order;
}

typedef struct
{
    pass_hit_t* hits;
    const int* idx; /**< 待计算命中在 hits 中的下标 */
    int n;          /**< 待计算条数 */
    const float* samples;
    int num_samples;
    int sample_rate;
    const float* sbase; /**< 整时隙频谱噪声底（可为 NULL） */
    int nbin;
    int next; /**< 共享任务游标，受 lock 保护 */
    ft8_mutex_t* lock;
} snr_batch_ctx_t;

static void snr_batch_worker(void* arg)
{
    snr_batch_ctx_t* c = (snr_batch_ctx_t*)arg;
    for (;;)
    {
        ft8_mutex_lock(c->lock);
        const int k = c->next++;
        ft8_mutex_unlock(c->lock);
        if (k >= c->n)
            break;
        snr_batch_one(&c->hits[c->idx[k]], c->samples, c->num_samples, c->sample_rate,
                      c->sbase, c->nbin);
    }
}

/* 并行计算去重后命中的时域 SNR：每条约数十毫秒，串行会超预算，故按多线程拆分。 */
static void compute_snr_batch(pass_hit_t* hits, const int* idx, int n,
                              const float* samples, int num_samples, int sample_rate,
                              int num_threads, const float* sbase, int nbin)
{
    if (n <= 0 || !samples || num_samples <= 0 || sample_rate <= 0)
        return;
    if (num_threads < 1)
        num_threads = 1;
    if (num_threads > n)
        num_threads = n;

    snr_batch_ctx_t ctx;
    ctx.hits = hits;
    ctx.idx = idx;
    ctx.n = n;
    ctx.samples = samples;
    ctx.num_samples = num_samples;
    ctx.sample_rate = sample_rate;
    ctx.sbase = sbase;
    ctx.nbin = nbin;
    ctx.next = 0;
    ctx.lock = ft8_mutex_create();
    if (!ctx.lock)
    {
        for (int k = 0; k < n; ++k)
            snr_batch_one(&hits[idx[k]], samples, num_samples, sample_rate, sbase, nbin);
        return;
    }

    if (num_threads == 1)
    {
        snr_batch_worker(&ctx);
    }
    else
    {
        ft8_thread_t** threads = (ft8_thread_t**)calloc((size_t)num_threads, sizeof(ft8_thread_t*));
        if (!threads)
        {
            snr_batch_worker(&ctx);
        }
        else
        {
            for (int t = 0; t < num_threads; ++t)
            {
                threads[t] = ft8_thread_create(snr_batch_worker, &ctx);
                if (!threads[t])
                    snr_batch_worker(&ctx); /* 创建失败则当前线程补跑 */
            }
            for (int t = 0; t < num_threads; ++t)
            {
                if (threads[t])
                {
                    ft8_thread_join(threads[t]);
                    ft8_thread_free(threads[t]);
                }
            }
            free(threads);
        }
    }
    ft8_mutex_free(ctx.lock);
}

/* P2.3：选择性 LLR 重解 */
#define FT8_LLR_REFINE_MIN_ERRORS 12 /**< BP 残余错误数门限（与 OSD 一致） */
#define FT8_LLR_REFINE_BUDGET 12     /**< 每个 worker 每次 decode_pass 的精化次数上限 */

/* 精化回调上下文：候选的物理 freq/dt 由 monitor 参数与瀑布 osr 换算。 */
typedef struct
{
    const monitor_t* mon;
    const ftx_waterfall_t* wf;
    const float* samples;
    int num_samples;
    int sample_rate;
    int budget; /**< 本 worker 剩余精化次数（限制总成本） */
} llr_refine_ctx_t;

static int llr_refine_cb(void* vctx, const ftx_candidate_t* cand, float* log174)
{
    llr_refine_ctx_t* c = (llr_refine_ctx_t*)vctx;
    if (c->budget <= 0)
        return -1; /* 超出预算：放弃精化，上层退回瀑布域结果 */
    --c->budget;
    const float freq0 = (c->mon->min_bin + cand->freq_offset +
                         (float)cand->freq_sub / c->wf->freq_osr) / c->mon->symbol_period;
    const float dt = (cand->time_offset +
                      (float)cand->time_sub / c->wf->time_osr) * c->mon->symbol_period;
    return ft8_spectrum_llr(c->samples, c->num_samples, c->sample_rate, freq0, dt, log174);
}

static void decode_worker(void* arg)
{
    decode_worker_arg_t* w = (decode_worker_arg_t*)arg;

    /* P2.3：选择性 LLR 重解上下文（候选物理 freq/dt 由 monitor 换算） */
    llr_refine_ctx_t rctx;
    rctx.mon = w->mon;
    rctx.wf = w->wf;
    rctx.samples = w->samples;
    rctx.num_samples = w->num_samples;
    rctx.sample_rate = w->sample_rate;
    rctx.budget = FT8_LLR_REFINE_BUDGET;
    const bool use_refine = w->enable_llr_refine && w->samples && w->num_samples > 0 &&
                            w->sample_rate > 0;

    for (int i = w->start; i < w->end; ++i)
    {
        const ftx_candidate_t* cand = &w->cands[i];
        ftx_message_t message;
        ftx_decode_status_t status;
        ftx_message_init(&message);
        memset(&status, 0, sizeof(status));
        if (!ftx_decode_candidate_ex(w->wf, cand, w->ldpc_iterations, w->osd_depth,
                                     use_refine ? llr_refine_cb : NULL,
                                     use_refine ? &rctx : NULL,
                                     FT8_LLR_REFINE_MIN_ERRORS,
                                     &message, &status))
            continue;

        raw_decode_t* r = &w->out[w->count++];
        r->cand_index = i;
        r->message = message;
        r->status = status;
        r->score = cand->score;
        r->snr_order = ftx_compute_snr(w->wf, cand, message.payload);
        /* 时域逐符号 SNR 成本较高：统一在 decode_pass 合并去重后并行计算，
         * 只对最终保留的命中算一次（见 compute_snr_batch）。 */
        r->snr_raw = 0.0f;
    }
}

static int raw_compare(const void* a, const void* b)
{
    const raw_decode_t* ra = (const raw_decode_t*)a;
    const raw_decode_t* rb = (const raw_decode_t*)b;
    return (ra->cand_index > rb->cand_index) - (ra->cand_index < rb->cand_index);
}

/* 单遍解码：在瀑布上搜索候选 + 多线程解码，返回按候选顺序排列的命中
 * （不做去重，由调用方处理）。返回命中条数，负数见 ft8_error_t。 */
static int decode_pass(const monitor_t* mon, const float* samples, int num_samples, int sample_rate,
                       const ft8_decode_config_t* cfg,
                       const ftx_message_t* seen, int num_seen,
                       pass_hit_t* out, int max_out)
{
    const ftx_waterfall_t* wf = &mon->wf;

    int max_cand = cfg->max_candidates;
    if (max_cand < 1)
        max_cand = 1;
    if (max_cand > 2048)
        max_cand = 2048;

    ftx_candidate_t* cands = (ftx_candidate_t*)malloc((size_t)max_cand * sizeof(ftx_candidate_t));
    if (!cands)
    {
        set_error("候选缓冲分配失败");
        return FT8_ERR_NOMEM;
    }

    int num_cand = ftx_find_candidates(wf, max_cand, cands, cfg->min_sync_score);
    if (num_cand <= 0)
    {
        free(cands);
        return 0;
    }

    int iters = (cfg->ldpc_iterations > 0) ? cfg->ldpc_iterations : 25;

    int num_threads = cfg->num_threads;
    if (num_threads < 1)
        num_threads = 1;
    if (num_threads > num_cand)
        num_threads = num_cand;
    int cpus = ft8_cpu_count();
    if (num_threads > cpus)
        num_threads = cpus;

    /* 每个线程一段独立的原始结果区 */
    raw_decode_t* regions = (raw_decode_t*)malloc((size_t)num_cand * num_threads * sizeof(raw_decode_t));
    decode_worker_arg_t* args = (decode_worker_arg_t*)malloc((size_t)num_threads * sizeof(decode_worker_arg_t));
    ft8_thread_t** threads = (ft8_thread_t**)calloc((size_t)num_threads, sizeof(ft8_thread_t*));
    if (!regions || !args || !threads)
    {
        free(cands);
        free(regions);
        free(args);
        free(threads);
        set_error("解码线程资源分配失败");
        return FT8_ERR_NOMEM;
    }

    for (int t = 0; t < num_threads; ++t)
    {
        args[t].mon = mon;
        args[t].wf = wf;
        args[t].cands = cands;
        args[t].samples = samples;
        args[t].num_samples = num_samples;
        args[t].sample_rate = sample_rate;
        args[t].start = (num_cand * t) / num_threads;
        args[t].end = (num_cand * (t + 1)) / num_threads;
        args[t].ldpc_iterations = iters;
        args[t].osd_depth = cfg->osd_depth;
        args[t].enable_llr_refine = cfg->enable_llr_refine;
        args[t].out = regions + (size_t)t * num_cand;
        args[t].count = 0;
    }

    if (num_threads == 1)
    {
        decode_worker(&args[0]);
    }
    else
    {
        for (int t = 0; t < num_threads; ++t)
        {
            threads[t] = ft8_thread_create(decode_worker, &args[t]);
            if (!threads[t])
            {
                /* 线程创建失败则在当前线程补跑该段，保证结果正确 */
                decode_worker(&args[t]);
            }
        }
        for (int t = 0; t < num_threads; ++t)
        {
            if (threads[t])
            {
                ft8_thread_join(threads[t]);
                ft8_thread_free(threads[t]);
            }
        }
    }

    /* 汇总所有原始结果并按候选序号排序，保证输出顺序与单线程一致 */
    int total_raw = 0;
    for (int t = 0; t < num_threads; ++t)
        total_raw += args[t].count;

    int num_hits = 0;
    if (total_raw > 0)
    {
        raw_decode_t* merged = (raw_decode_t*)malloc((size_t)total_raw * sizeof(raw_decode_t));
        if (merged)
        {
            int pos = 0;
            for (int t = 0; t < num_threads; ++t)
            {
                memcpy(merged + pos, regions + (size_t)t * num_cand,
                       (size_t)args[t].count * sizeof(raw_decode_t));
                pos += args[t].count;
            }
            qsort(merged, (size_t)total_raw, sizeof(raw_decode_t), raw_compare);

            /* uniq 记录「首次出现」的保留命中下标；pass_seen 用于本遍内去重。
             * 这些命中才需要昂贵的时域 SNR；重复项由调用方丢弃，无需计算。 */
            int* uniq = (int*)malloc((size_t)total_raw * sizeof(int));
            ftx_message_t* pass_seen = (ftx_message_t*)malloc((size_t)total_raw * sizeof(ftx_message_t));
            int nuniq = 0;
            int npass = 0;

            for (int i = 0; i < total_raw; ++i)
            {
                raw_decode_t* r = &merged[i];

                /* 丢弃 CRC 通过但无法解包的虚假码字（含 OSD 的 CRC-14 碰撞）。
                 * 这些码字若被用于信号消除会污染残差，故在此统一过滤。 */
                {
                    char vtext[FTX_MAX_MESSAGE_LENGTH];
                    ftx_message_offsets_t voffsets;
                    if (ftx_message_decode(&r->message, &ft8_hash_if, vtext, &voffsets) != FTX_MESSAGE_RC_OK)
                        continue;
                    const char* vp = vtext;
                    while (*vp == ' ' || *vp == '\t')
                        ++vp;
                    if (*vp == '\0')
                        continue;
                }

                const bool dup = (seen && hit_seen_before(&r->message, seen, num_seen)) ||
                                 (pass_seen && hit_seen_before(&r->message, pass_seen, npass));

                if (num_hits < max_out)
                {
                    const ftx_candidate_t* cand = &cands[r->cand_index];
                    pass_hit_t* h = &out[num_hits];
                    h->message = r->message;
                    h->status = r->status;
                    h->score = r->score;
                    h->snr_raw = 0.0f; /* 稍后对保留命中并行计算 */
                    h->snr_order = r->snr_order;
                    h->freq = (mon->min_bin + cand->freq_offset +
                               (float)cand->freq_sub / wf->freq_osr) / mon->symbol_period;
                    h->time = (cand->time_offset +
                               (float)cand->time_sub / wf->time_osr) * mon->symbol_period;
                    if (!dup && pass_seen && uniq)
                    {
                        pass_seen[npass++] = r->message;
                        uniq[nuniq++] = num_hits;
                    }
                }
                num_hits++;
            }

            /* P2.2 收尾：整时隙频谱噪声底只算一次，供本遍所有命中复用 */
            float* sbase = NULL;
            if (samples && num_samples > 0 && sample_rate > 0)
            {
                sbase = (float*)malloc((size_t)FT8_SP_BASE_NBIN * sizeof(float));
                if (sbase && ft8_spectrum_baseline(samples, num_samples, sample_rate,
                                                   sbase, FT8_SP_BASE_NBIN) != 0)
                {
                    free(sbase);
                    sbase = NULL; /* 失败则退回 JTDX 7 音调口径 */
                }
            }

            if (pass_seen && uniq)
                compute_snr_batch(out, uniq, nuniq, samples, num_samples, sample_rate,
                                  num_threads, sbase, FT8_SP_BASE_NBIN);
            else
                for (int k = 0; k < num_hits; ++k) /* 内存不足：退化为串行且不去重 */
                    snr_batch_one(&out[k], samples, num_samples, sample_rate, sbase,
                                  FT8_SP_BASE_NBIN);
            free(sbase);

            free(uniq);
            free(pass_seen);
            free(merged);
        }
    }

    free(threads);
    free(args);
    free(regions);
    free(cands);
    return num_hits;
}

/** 判断消息是否已在 seen 中出现（按哈希 + 载荷去重）。 */
static bool hit_seen_before(const ftx_message_t* m, const ftx_message_t* seen, int n)
{
    for (int k = 0; k < n; ++k)
    {
        if ((seen[k].hash == m->hash) &&
            0 == memcmp(seen[k].payload, m->payload, sizeof(m->payload)))
            return true;
    }
    return false;
}

/** 把单遍命中格式化为对外结果。 */
static void emit_hit(const pass_hit_t* h, int pass, ft8_message_result_t* res)
{
    memset(res, 0, sizeof(*res));

    char text[FTX_MAX_MESSAGE_LENGTH];
    ftx_message_offsets_t offsets;
    ftx_message_rc_t urc = ftx_message_decode(&h->message, &ft8_hash_if, text, &offsets);
    if (urc != FTX_MESSAGE_RC_OK)
        snprintf(text, sizeof(text), "Error [%d] while unpacking!", (int)urc);
    snprintf(res->text, sizeof(res->text), "%s", text);

    float snr = FT8_SNR_CALIB_SLOPE * h->snr_raw + FT8_SNR_CALIB_INTERCEPT;
    if (snr < -24.0f)
        snr = -24.0f; /* 与 JTDX 一致的显示下限 */
    if (snr > 49.0f)
        snr = 49.0f;
    res->snr = snr;
    res->dt = h->time;
    res->freq = h->freq;
    res->score = h->score;
    res->ldpc_errors = h->status.ldpc_errors;
    res->pass = pass;
    res->ap_type = 0;
    res->crc = h->status.crc_calculated;
}

/// 在瀑布数据上执行候选搜索 + 并行解码 + 去重解包（单遍路径）。
static int decode_from_monitor(monitor_t* mon, const float* samples, int num_samples, int sample_rate,
                               const ft8_decode_config_t* cfg,
                               ft8_message_result_t* out, int max_out)
{
    int cap = cfg->max_candidates;
    if (cap < 1)
        cap = 1;
    if (cap > 2048)
        cap = 2048;

    pass_hit_t* hits = (pass_hit_t*)malloc((size_t)cap * sizeof(pass_hit_t));
    if (!hits)
    {
        set_error("解码结果缓冲分配失败");
        return FT8_ERR_NOMEM;
    }

    int total = decode_pass(mon, samples, num_samples, sample_rate, cfg, NULL, 0, hits, cap);
    if (total < 0)
    {
        free(hits);
        return total;
    }

    int num_results = 0;
    ftx_message_t* seen = NULL;
    int num_seen = 0;
    if (total > 0)
        seen = (ftx_message_t*)malloc((size_t)total * sizeof(ftx_message_t));

    for (int i = 0; i < total; ++i)
    {
        bool dup = seen && hit_seen_before(&hits[i].message, seen, num_seen);
        if (dup && !cfg->return_duplicates)
            continue;
        if (!dup && seen)
            seen[num_seen++] = hits[i].message;

        if (out && num_results < max_out)
            emit_hit(&hits[i], 0, &out[num_results]);
        num_results++;
    }

    free(seen);
    free(hits);
    return num_results;
}

/// 复位瀑布并用 resid 重新填充（多遍解码每遍开始调用）。
static void monitor_feed_slot(monitor_t* mon, const float* samples, int num_samples)
{
    monitor_reset(mon);
    if (mon->last_frame)
        memset(mon->last_frame, 0, (size_t)mon->nfft * sizeof(float));
    mon->max_mag = -120.0f;

    int block = mon->block_size;
    for (int pos = 0; pos + block <= num_samples; pos += block)
        monitor_process(mon, samples + pos);
}

/// 多遍信号消除解码（P1）。返回命中条数，负数见 ft8_error_t。
static int decode_slot_multipass(monitor_t* mon, const float* samples, int num_samples,
                                 const ft8_decode_config_t* cfg,
                                 ft8_message_result_t* out, int max_out)
{
    int passes = cfg->decode_depth;
    if (passes <= 0)
        passes = 3;
    if (passes > 8)
        passes = 8;

    int cap = cfg->max_candidates;
    if (cap < 1)
        cap = 1;
    if (cap > 2048)
        cap = 2048;

    const int sample_rate = (cfg->sample_rate > 0) ? cfg->sample_rate : 12000;

    ft8_subtract_t* sub = ft8_subtract_create(sample_rate, num_samples);
    if (!sub)
    {
        /* 消除器创建失败：退化为单遍，保证可用性 */
        return decode_from_monitor(mon, samples, num_samples, sample_rate, cfg, out, max_out);
    }

    float* resid = (float*)malloc((size_t)num_samples * sizeof(float));
    pass_hit_t* hits = (pass_hit_t*)malloc((size_t)cap * sizeof(pass_hit_t));
    ftx_message_t* seen = (ftx_message_t*)malloc((size_t)cap * (size_t)(passes + 1) * sizeof(ftx_message_t));
    int* new_idx = (int*)malloc((size_t)cap * sizeof(int));
    if (!resid || !hits || !seen || !new_idx)
    {
        free(resid);
        free(hits);
        free(seen);
        free(new_idx);
        ft8_subtract_free(sub);
        set_error("多遍解码资源分配失败");
        return FT8_ERR_NOMEM;
    }
    memcpy(resid, samples, (size_t)num_samples * sizeof(float));

    uint8_t tones[FT8_NN];
    int num_seen = 0;
    int num_results = 0;
    int rc = FT8_OK;

    for (int pass = 0; pass < passes; ++pass)
    {
        monitor_feed_slot(mon, resid, num_samples);

        int total = decode_pass(mon, resid, num_samples, sample_rate, cfg, seen, num_seen, hits, cap);
        if (total < 0)
        {
            rc = total;
            break;
        }

        int nnew = 0;
        for (int i = 0; i < total; ++i)
        {
            if (hit_seen_before(&hits[i].message, seen, num_seen))
            {
                if (cfg->return_duplicates && out && num_results < max_out)
                {
                    emit_hit(&hits[i], pass, &out[num_results]);
                    num_results++;
                }
                continue;
            }
            seen[num_seen++] = hits[i].message;
            new_idx[nnew++] = i;
            if (out && num_results < max_out)
                emit_hit(&hits[i], pass, &out[num_results]);
            num_results++;
        }

        if (pass == passes - 1)
            break;
        if (pass > 0 && nnew == 0)
            break;

        /* 新增消息按原始 SNR 由强到弱排序后逐条消除 */
        for (int a = 0; a < nnew; ++a)
        {
            for (int b = a + 1; b < nnew; ++b)
            {
                if (hits[new_idx[b]].snr_order > hits[new_idx[a]].snr_order)
                {
                    int tmp = new_idx[a];
                    new_idx[a] = new_idx[b];
                    new_idx[b] = tmp;
                }
            }
        }
        for (int a = 0; a < nnew; ++a)
        {
            const pass_hit_t* h = &hits[new_idx[a]];
            ft8_encode(h->message.payload, tones);
            ft8_subtract_signal(sub, resid, num_samples, tones, h->freq, h->time);
        }
    }

    free(resid);
    free(hits);
    free(seen);
    free(new_idx);
    ft8_subtract_free(sub);
    return (rc != FT8_OK) ? rc : num_results;
}

/* ========================================================================= */
/*  一次性解码                                                                */
/* ========================================================================= */

/* 单频段解码：现行整段逻辑（原 ft8_decode_slot 主体）。
 * 注意：此函数不再调用 ft8_hash_cleanup —— 分段模式下多段并发会重复老化哈希表，
 * 统一由外层 ft8_decode_slot() 每时隙清理一次。 */
static int decode_slot_single(const float* samples, int num_samples,
                              const ft8_decode_config_t* cfg,
                              ft8_message_result_t* out, int max_out)
{
    if (!samples || !cfg || num_samples <= 0)
    {
        set_error("解码输入参数非法");
        return FT8_ERR_ARG;
    }

    monitor_config_t mc;
    memset(&mc, 0, sizeof(mc));
    mc.f_min = cfg->f_min_hz;
    mc.f_max = cfg->f_max_hz;
    mc.sample_rate = (cfg->sample_rate > 0) ? cfg->sample_rate : 12000;
    mc.time_osr = (cfg->time_osr >= 1) ? cfg->time_osr : 1;
    mc.freq_osr = (cfg->freq_osr >= 1) ? cfg->freq_osr : 1;
    mc.protocol = FTX_PROTOCOL_FT8;

    monitor_t mon;
    monitor_init(&mon, &mc);

    int result;
    if (cfg->enable_subtract)
    {
        result = decode_slot_multipass(&mon, samples, num_samples, cfg, out, max_out);
    }
    else
    {
        int block = mon.block_size;
        for (int pos = 0; pos + block <= num_samples; pos += block)
        {
            monitor_process(&mon, samples + pos);
        }
        result = decode_from_monitor(&mon, samples, num_samples, mc.sample_rate, cfg, out, max_out);
    }

    monitor_free(&mon);
    return result;
}

/* ========================================================================= */
/*  P3：按频率分段并行解码                                                     */
/* ========================================================================= */

#define FT8_MAX_BANDS 8             /**< 最大分段数（受总 FFT 开销约束） */
#define FT8_BAND_OVERLAP_HZ 100.0f  /**< 相邻频段重叠带宽：> FT8 信号占用 50 Hz，
                                     *   保证落在边界上的信号至少被一段完整覆盖。 */
#define FT8_BAND_MIN_WIDTH_HZ 200.0f /**< 单段最小带宽：再窄则 FFT 重复开销得不偿失。 */

typedef struct
{
    const float* samples;
    int num_samples;
    ft8_decode_config_t cfg; /**< 本段配置（f_min/f_max/num_threads 已改写） */
    ft8_message_result_t* out;
    int max_out;
    int count; /**< 本段输出条数；<0 为 FT8 错误码 */
} band_arg_t;

static void band_worker(void* arg)
{
    band_arg_t* b = (band_arg_t*)arg;
    b->count = decode_slot_single(b->samples, b->num_samples, &b->cfg, b->out, b->max_out);
}

/* 把 [f_min, f_max] 拆成 num_bands 个带宽近似、带重叠的频段并行解码，再合并去重。 */
static int decode_slot_bands(const float* samples, int num_samples,
                             const ft8_decode_config_t* cfg, int num_bands,
                             ft8_message_result_t* out, int max_out)
{
    if (num_bands < 2)
        num_bands = 2;
    if (num_bands > FT8_MAX_BANDS)
        num_bands = FT8_MAX_BANDS;

    const float fmin = cfg->f_min_hz;
    const float fmax = cfg->f_max_hz;
    if (!(fmax > fmin + 1.0f))
        return decode_slot_single(samples, num_samples, cfg, out, max_out);

    /* 段数受总带宽限制，避免出现极窄段导致 FFT 重复开销过大 */
    int max_by_span = (int)((fmax - fmin) / FT8_BAND_MIN_WIDTH_HZ);
    if (max_by_span < 1)
        max_by_span = 1;
    if (num_bands > max_by_span)
        num_bands = max_by_span;
    if (num_bands < 2)
        return decode_slot_single(samples, num_samples, cfg, out, max_out);

    /* 线程预算按段数均分：每段至少 1 个线程 */
    int per_band_threads = cfg->num_threads / num_bands;
    if (per_band_threads < 1)
        per_band_threads = 1;

    band_arg_t* args = (band_arg_t*)calloc((size_t)num_bands, sizeof(band_arg_t));
    ft8_thread_t** threads = (ft8_thread_t**)calloc((size_t)num_bands, sizeof(ft8_thread_t*));
    if (!args || !threads)
    {
        free(args);
        free(threads);
        set_error("分段解码资源分配失败");
        return FT8_ERR_NOMEM;
    }

    const float span = fmax - fmin;
    const float base = span / (float)num_bands;
    const float half = FT8_BAND_OVERLAP_HZ * 0.5f;

    bool alloc_ok = true;
    for (int i = 0; i < num_bands; ++i)
    {
        args[i].cfg = *cfg;
        float lo = fmin + base * (float)i - ((i > 0) ? half : 0.0f);
        float hi = fmin + base * (float)(i + 1) + ((i < num_bands - 1) ? half : 0.0f);
        if (lo < 0.0f)
            lo = 0.0f;
        args[i].cfg.f_min_hz = lo;
        args[i].cfg.f_max_hz = hi;
        args[i].cfg.num_threads = per_band_threads;
        args[i].samples = samples;
        args[i].num_samples = num_samples;
        args[i].max_out = max_out;
        args[i].count = 0;
        args[i].out = (ft8_message_result_t*)malloc(
            (size_t)(max_out > 0 ? max_out : 1) * sizeof(ft8_message_result_t));
        if (!args[i].out)
            alloc_ok = false;
    }

    int rc = FT8_OK;
    if (!alloc_ok)
    {
        rc = FT8_ERR_NOMEM;
    }
    else
    {
        for (int i = 0; i < num_bands; ++i)
        {
            threads[i] = ft8_thread_create(band_worker, &args[i]);
            if (!threads[i])
                band_worker(&args[i]); /* 线程创建失败：当前线程补跑该段 */
        }
        for (int i = 0; i < num_bands; ++i)
        {
            if (threads[i])
            {
                ft8_thread_join(threads[i]);
                ft8_thread_free(threads[i]);
            }
        }
        for (int i = 0; i < num_bands; ++i)
        {
            if (args[i].count < 0)
            {
                rc = args[i].count; /* 任一段失败则整体失败，保留其错误码 */
                break;
            }
        }
    }

    int total = 0;
    if (rc == FT8_OK)
    {
        if (!out || max_out <= 0)
        {
            /* 仅查询条数：累加（重叠段可能重复计数，调用方一般不用此路径） */
            for (int i = 0; i < num_bands; ++i)
                total += args[i].count;
        }
        else
        {
            /* 合并去重：按解码文本去重，保留首次出现（段序即频段从低到高） */
            for (int i = 0; i < num_bands && total < max_out; ++i)
            {
                for (int k = 0; k < args[i].count && total < max_out; ++k)
                {
                    const ft8_message_result_t* r = &args[i].out[k];
                    bool dup = false;
                    for (int j = 0; j < total; ++j)
                    {
                        if (strcmp(out[j].text, r->text) == 0)
                        {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup)
                        out[total++] = *r;
                }
            }
        }
    }

    for (int i = 0; i < num_bands; ++i)
        free(args[i].out);
    free(args);
    free(threads);
    return (rc == FT8_OK) ? total : rc;
}

int ft8_decode_slot(const float* samples, int num_samples,
                    const ft8_decode_config_t* cfg,
                    ft8_message_result_t* out, int max_out)
{
    if (!samples || !cfg || num_samples <= 0)
    {
        set_error("解码输入参数非法");
        return FT8_ERR_ARG;
    }

    int result = (cfg->num_bands > 1)
                     ? decode_slot_bands(samples, num_samples, cfg, cfg->num_bands, out, max_out)
                     : decode_slot_single(samples, num_samples, cfg, out, max_out);

    ft8_hash_cleanup(10); /* 每个时隙只老化一次，避免分段并发重复老化 */
    return result;
}

/* ========================================================================= */
/*  流式会话                                                                  */
/* ========================================================================= */

struct ft8_decode_session
{
    ft8_decode_config_t cfg;
    char my_call[16];
    char his_call[16];
    char his_grid[16];
    monitor_t mon;
    float* pending;
    int pending_len;
    int total;
    float* slot;      /**< 本时隙原始音频（多遍消除需要） */
    int slot_len;
    int slot_cap;
};

static void session_copy_call(char* dst, size_t cap, const char* src)
{
    dst[0] = '\0';
    if (src)
    {
        strncpy(dst, src, cap - 1);
        dst[cap - 1] = '\0';
    }
}

ft8_decode_session_t* ft8_decode_session_create(const ft8_decode_config_t* cfg)
{
    ft8_decode_session_t* s = (ft8_decode_session_t*)calloc(1, sizeof(ft8_decode_session_t));
    if (!s)
    {
        set_error("会话分配失败");
        return NULL;
    }

    if (cfg)
        s->cfg = *cfg;
    else
        ft8_decode_config_default(&s->cfg);

    session_copy_call(s->my_call, sizeof(s->my_call), cfg ? cfg->my_call : NULL);
    session_copy_call(s->his_call, sizeof(s->his_call), cfg ? cfg->his_call : NULL);
    session_copy_call(s->his_grid, sizeof(s->his_grid), cfg ? cfg->his_grid : NULL);
    s->cfg.my_call = s->my_call[0] ? s->my_call : NULL;
    s->cfg.his_call = s->his_call[0] ? s->his_call : NULL;
    s->cfg.his_grid = s->his_grid[0] ? s->his_grid : NULL;

    monitor_config_t mc;
    memset(&mc, 0, sizeof(mc));
    mc.f_min = s->cfg.f_min_hz;
    mc.f_max = s->cfg.f_max_hz;
    mc.sample_rate = (s->cfg.sample_rate > 0) ? s->cfg.sample_rate : 12000;
    mc.time_osr = (s->cfg.time_osr >= 1) ? s->cfg.time_osr : 1;
    mc.freq_osr = (s->cfg.freq_osr >= 1) ? s->cfg.freq_osr : 1;
    mc.protocol = FTX_PROTOCOL_FT8;

    monitor_init(&s->mon, &mc);
    s->pending = (float*)malloc((size_t)s->mon.block_size * sizeof(float));
    s->slot_cap = s->mon.wf.max_blocks * s->mon.block_size;
    s->slot = (float*)malloc((size_t)s->slot_cap * sizeof(float));
    if (!s->pending || !s->slot)
    {
        monitor_free(&s->mon);
        free(s->pending);
        free(s->slot);
        free(s);
        set_error("会话缓冲分配失败");
        return NULL;
    }
    s->pending_len = 0;
    s->total = 0;
    s->slot_len = 0;
    return s;
}

void ft8_decode_session_reset(ft8_decode_session_t* session)
{
    if (!session)
        return;
    monitor_reset(&session->mon);
    session->pending_len = 0;
    session->total = 0;
    session->slot_len = 0;
}

int ft8_decode_session_feed(ft8_decode_session_t* session,
                            const float* samples, int num_samples)
{
    if (!session || !samples || num_samples < 0)
    {
        set_error("会话输入参数非法");
        return FT8_ERR_ARG;
    }

    int block = session->mon.block_size;
    int consumed = 0;

    while (consumed < num_samples)
    {
        if (session->pending_len == 0 && (num_samples - consumed) >= block)
        {
            /* 缓冲区空且剩余足够：直接从输入处理，避免拷贝 */
            monitor_process(&session->mon, samples + consumed);
            consumed += block;
            session->total += block;
        }
        else
        {
            int need = block - session->pending_len;
            int take = (num_samples - consumed < need) ? (num_samples - consumed) : need;
            memcpy(session->pending + session->pending_len, samples + consumed,
                   (size_t)take * sizeof(float));
            session->pending_len += take;
            consumed += take;
            session->total += take;

            if (session->pending_len == block)
            {
                monitor_process(&session->mon, session->pending);
                session->pending_len = 0;
            }
        }
    }

    /* 保存本时隙原始音频，供多遍信号消除复用 */
    if (session->slot_len < session->slot_cap)
    {
        int space = session->slot_cap - session->slot_len;
        int copy = (num_samples < space) ? num_samples : space;
        if (copy > 0)
        {
            memcpy(session->slot + session->slot_len, samples, (size_t)copy * sizeof(float));
            session->slot_len += copy;
        }
    }

    return session->total;
}

int ft8_decode_session_finalize(ft8_decode_session_t* session,
                                ft8_message_result_t* out, int max_out)
{
    if (!session)
    {
        set_error("会话为空");
        return FT8_ERR_ARG;
    }

    if (session->pending_len > 0)
    {
        int block = session->mon.block_size;
        memset(session->pending + session->pending_len, 0,
               (size_t)(block - session->pending_len) * sizeof(float));
        monitor_process(&session->mon, session->pending);
        session->pending_len = 0;
    }

    int result;
    if (session->cfg.enable_subtract && session->slot_len > 0)
        result = decode_slot_multipass(&session->mon, session->slot, session->slot_len,
                                       &session->cfg, out, max_out);
    else
    {
        const int srate = (session->cfg.sample_rate > 0) ? session->cfg.sample_rate : 12000;
        const float* slot = (session->slot_len > 0) ? session->slot : NULL;
        result = decode_from_monitor(&session->mon, slot, session->slot_len, srate,
                                     &session->cfg, out, max_out);
    }
    ft8_hash_cleanup(10);
    return result;
}

void ft8_decode_session_free(ft8_decode_session_t* session)
{
    if (!session)
        return;
    monitor_free(&session->mon);
    free(session->pending);
    free(session->slot);
    free(session);
}
