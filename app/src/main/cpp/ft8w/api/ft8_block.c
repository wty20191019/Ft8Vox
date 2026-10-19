/**
 * @file ft8_block.c
 * @brief 论文式 FT8 N=1/2/3 分组检测实现（移植 WSJT-X ft8_downsample/sync8d/ft8b）。
 *
 * 关键点（与参考实现对齐）：
 *   - 200 Hz 复基带、32 样本/符号；
 *   - Costas 同步：时间粗搜 → 频偏搜索 → 时间细搜；
 *   - 每符号 8 音调复相关，块内复数相加后取模；
 *   - 58 个数据符号按中间 Costas 分成两段各 29 符号，N=2/3 不跨段；
 *   - LLR 归一化：除以标准差再乘 2.83。
 *
 * 与参考实现的微调：
 *   - 长 FFT 长度仍为 192000（与 WSJT-X 相同），但只在每个段/遍算一次；
 *   - 时间粗搜扩大到 ±100 ms（我们的候选时间量化比 WSJT-X 粗）；
 *   - 频偏先 0.5 Hz 粗搜，再 0.1 Hz 细搜；
 *   - 不复制 Fortran 的 3200 点复数 FFT，直接用 kissfft。
 */
#include "ft8_block.h"

#include <ft8/constants.h>
#include <fft/kiss_fft.h>
#include <fft/kiss_fftr.h>

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define FT8_BLOCK_NFFT1 192000 /* 15 s @ 12 kHz，补零到 5-smooth */
#define FT8_BLOCK_NFFT2 3200   /* 16 s @ 200 Hz */
#define FT8_BLOCK_FS2 200
#define FT8_BLOCK_NSPS2 32
#define FT8_BLOCK_NSYM 79
#define FT8_BLOCK_NTONE 8
#define FT8_BLOCK_ND 58
#define FT8_BLOCK_TONE_HZ (1.0 / FT8_SYMBOL_PERIOD) /* 6.25 Hz */
#define FT8_BLOCK_TAPER 100

struct ft8_ddc
{
    int sample_rate;
    int nfft1;
    int nfft2;
    kiss_fftr_cfg fft1;
    kiss_fft_cfg ifft2;
    float* x;
    kiss_fft_cpx* spec;
    float taper[FT8_BLOCK_TAPER + 1];
    int ready;
};

struct ft8_block_ctx
{
    int ibest;
    int sync_count;
    float cd_re[FT8_BLOCK_NFFT2];
    float cd_im[FT8_BLOCK_NFFT2];
    float cs_re[FT8_BLOCK_NSYM * FT8_BLOCK_NTONE];
    float cs_im[FT8_BLOCK_NSYM * FT8_BLOCK_NTONE];
};

static float block_abs2(float re, float im)
{
    return re * re + im * im;
}

/* 32 点窗内单音复相关：Σ cd[base+j] * e^{-j2π f j/fs2}。越界按 0。 */
static void tone_corr32(const float* re, const float* im, int base, double freq,
                        double* out_re, double* out_im)
{
    const double w = -2.0 * M_PI * freq / (double)FT8_BLOCK_FS2;
    const double cw = cos(w);
    const double sw = sin(w);
    double cr = 1.0, ci = 0.0;
    double sr = 0.0, si = 0.0;

    for (int j = 0; j < FT8_BLOCK_NSPS2; ++j)
    {
        const int idx = base + j;
        if (idx >= 0 && idx < FT8_BLOCK_NFFT2)
        {
            const double vr = (double)re[idx];
            const double vi = (double)im[idx];
            sr += vr * cr - vi * ci;
            si += vr * ci + vi * cr;
        }

        const double nr = cr * cw - ci * sw;
        ci = cr * sw + ci * cw;
        cr = nr;
        if ((j & 15) == 15) /* 每 16 个样本归一化，避免递推漂移 */
        {
            const double m = sqrt(cr * cr + ci * ci);
            if (m > 0.0)
            {
                cr /= m;
                ci /= m;
            }
        }
    }

    *out_re = sr;
    *out_im = si;
}

/* Costas 同步功率：7 个音调 × 3 组，参考 sync8d.f90。 */
static double sync8d(const float* re, const float* im, int i0, double delf)
{
    double sync = 0.0;
    for (int t = 0; t < 7; ++t)
    {
        const int tone = kFT8_Costas_pattern[t];
        const double freq = (double)tone * FT8_BLOCK_TONE_HZ + delf;
        for (int g = 0; g < 3; ++g)
        {
            const int base = i0 + t * FT8_BLOCK_NSPS2 + g * 36 * FT8_BLOCK_NSPS2;
            double zr = 0.0, zi = 0.0;
            tone_corr32(re, im, base, freq, &zr, &zi);
            sync += zr * zr + zi * zi;
        }
    }
    return sync;
}

/* 对复基带做 -delf 的频移（把残余频偏搬到 0）。 */
static void shift_freq(float* re, float* im, double delf)
{
    const double w = -2.0 * M_PI * delf / (double)FT8_BLOCK_FS2;
    const double cw = cos(w);
    const double sw = sin(w);
    double cr = 1.0, ci = 0.0;

    for (int n = 0; n < FT8_BLOCK_NFFT2; ++n)
    {
        const double vr = (double)re[n];
        const double vi = (double)im[n];
        re[n] = (float)(vr * cr - vi * ci);
        im[n] = (float)(vr * ci + vi * cr);

        const double nr = cr * cw - ci * sw;
        ci = cr * sw + ci * cw;
        cr = nr;
        if ((n & 255) == 255)
        {
            const double m = sqrt(cr * cr + ci * ci);
            if (m > 0.0)
            {
                cr /= m;
                ci /= m;
            }
        }
    }
}

ft8_ddc_t* ft8_ddc_create(int sample_rate)
{
    /* 当前 DDC 的 192000/3200 与 12 kHz 绑定；其他采样率应在上层重采样。 */
    if (sample_rate != 12000)
        return NULL;

    ft8_ddc_t* ddc = (ft8_ddc_t*)calloc(1, sizeof(*ddc));
    if (!ddc)
        return NULL;

    ddc->sample_rate = sample_rate;
    ddc->nfft1 = FT8_BLOCK_NFFT1;
    ddc->nfft2 = FT8_BLOCK_NFFT2;
    ddc->x = (float*)calloc((size_t)ddc->nfft1, sizeof(float));
    ddc->spec = (kiss_fft_cpx*)malloc(((size_t)ddc->nfft1 / 2 + 1) * sizeof(kiss_fft_cpx));
    ddc->fft1 = kiss_fftr_alloc(ddc->nfft1, 0, NULL, NULL);
    ddc->ifft2 = kiss_fft_alloc(ddc->nfft2, 1, NULL, NULL);

    if (!ddc->x || !ddc->spec || !ddc->fft1 || !ddc->ifft2)
    {
        ft8_ddc_free(ddc);
        return NULL;
    }

    for (int i = 0; i <= FT8_BLOCK_TAPER; ++i)
        ddc->taper[i] = (float)(0.5 * (1.0 + cos((double)i * M_PI / FT8_BLOCK_TAPER)));

    ddc->ready = 0;
    return ddc;
}

void ft8_ddc_free(ft8_ddc_t* ddc)
{
    if (!ddc)
        return;
    if (ddc->fft1)
        kiss_fftr_free(ddc->fft1);
    if (ddc->ifft2)
        kiss_fft_free(ddc->ifft2);
    free(ddc->x);
    free(ddc->spec);
    free(ddc);
}

int ft8_ddc_set_signal(ft8_ddc_t* ddc, const float* samples, int num_samples)
{
    if (!ddc || !samples || num_samples <= 0 || !ddc->x || !ddc->spec || !ddc->fft1)
        return -1;

    memset(ddc->x, 0, (size_t)ddc->nfft1 * sizeof(float));
    const int n = (num_samples < ddc->nfft1) ? num_samples : ddc->nfft1;
    memcpy(ddc->x, samples, (size_t)n * sizeof(float));

    kiss_fftr(ddc->fft1, ddc->x, ddc->spec);
    ddc->ready = 1;
    return 0;
}

/* 从长频谱抽取候选附近的 62.5 Hz 窄带，IFFT 到 200 Hz 复基带。 */
static int ddc_extract(const ft8_ddc_t* ddc, float freq0, float* out_re, float* out_im)
{
    if (!ddc || !ddc->ready || !out_re || !out_im || freq0 <= 0.0f)
        return -1;

    const double df = (double)ddc->sample_rate / (double)ddc->nfft1;
    const double baud = FT8_BLOCK_TONE_HZ;
    int i0 = (int)lround((double)freq0 / df);
    int it = (int)lround(((double)freq0 + 8.5 * baud) / df);
    int ib = (int)lround(((double)freq0 - 1.5 * baud) / df);

    if (it > ddc->nfft1 / 2)
        it = ddc->nfft1 / 2;
    if (ib < 1)
        ib = 1;
    const int k = it - ib + 1;
    if (k <= 0 || i0 < 0)
        return -1;

    kiss_fft_cpx* band = (kiss_fft_cpx*)calloc((size_t)ddc->nfft2, sizeof(kiss_fft_cpx));
    kiss_fft_cpx* out = (kiss_fft_cpx*)malloc((size_t)ddc->nfft2 * sizeof(kiss_fft_cpx));
    if (!band || !out)
    {
        free(band);
        free(out);
        return -1;
    }

    for (int pos = 0; pos < k; ++pos)
    {
        const int i = ib + pos;
        float w = 1.0f;
        if (pos <= FT8_BLOCK_TAPER)
            w = ddc->taper[FT8_BLOCK_TAPER - pos];
        if (pos >= k - 1 - FT8_BLOCK_TAPER)
            w *= ddc->taper[pos - (k - 1 - FT8_BLOCK_TAPER)];

        int dest = i - i0;
        if (dest < 0)
            dest += ddc->nfft2;
        dest %= ddc->nfft2;

        band[dest].r = ddc->spec[i].r * w;
        band[dest].i = ddc->spec[i].i * w;
    }

    kiss_fft(ddc->ifft2, band, out);
    const double fac = 1.0 / sqrt((double)ddc->nfft1 * (double)ddc->nfft2);
    for (int n = 0; n < ddc->nfft2; ++n)
    {
        out_re[n] = (float)((double)out[n].r * fac);
        out_im[n] = (float)((double)out[n].i * fac);
    }

    free(band);
    free(out);
    return 0;
}

/* 79 个符号 × 8 音调的 32 点 DFT。 */
static void compute_symbol_tones(ft8_block_ctx_t* ctx)
{
    for (int k = 0; k < FT8_BLOCK_NSYM; ++k)
    {
        const int base = ctx->ibest + k * FT8_BLOCK_NSPS2;
        for (int tone = 0; tone < FT8_BLOCK_NTONE; ++tone)
        {
            double zr = 0.0, zi = 0.0;
            tone_corr32(ctx->cd_re, ctx->cd_im, base, (double)tone * FT8_BLOCK_TONE_HZ,
                        &zr, &zi);
            ctx->cs_re[k * FT8_BLOCK_NTONE + tone] = (float)zr;
            ctx->cs_im[k * FT8_BLOCK_NTONE + tone] = (float)zi;
        }
    }
}

/* 同步质量：21 个 Costas 符号中，最大音调与期望一致的数量。 */
static int compute_sync_count(const ft8_block_ctx_t* ctx)
{
    int count = 0;
    for (int g = 0; g < 3; ++g)
    {
        for (int t = 0; t < 7; ++t)
        {
            const int sym = g * 36 + t;
            int best_tone = 0;
            float best = -1.0f;
            for (int tone = 0; tone < FT8_BLOCK_NTONE; ++tone)
            {
                const int idx = sym * FT8_BLOCK_NTONE + tone;
                const float p = block_abs2(ctx->cs_re[idx], ctx->cs_im[idx]);
                if (p > best)
                {
                    best = p;
                    best_tone = tone;
                }
            }
            if (best_tone == kFT8_Costas_pattern[t])
                ++count;
        }
    }
    return count;
}

ft8_block_ctx_t* ft8_block_prepare(const ft8_ddc_t* ddc, float freq0, float dt)
{
    if (!ddc || !ddc->ready || freq0 <= 0.0f)
        return NULL;

    ft8_block_ctx_t* ctx = (ft8_block_ctx_t*)calloc(1, sizeof(*ctx));
    if (!ctx)
        return NULL;

    if (ddc_extract(ddc, freq0, ctx->cd_re, ctx->cd_im) != 0)
    {
        free(ctx);
        return NULL;
    }

    /* 1) 时间粗搜：±100 ms（20 个 200 Hz 样本），覆盖瀑布候选的 80 ms 量化 */
    const int i0 = (int)lround((double)dt * FT8_BLOCK_FS2);
    int best_i = i0;
    double best_sync = -1.0;
    for (int off = -20; off <= 20; ++off)
    {
        const double s = sync8d(ctx->cd_re, ctx->cd_im, i0 + off, 0.0);
        if (s > best_sync)
        {
            best_sync = s;
            best_i = i0 + off;
        }
    }

    /* 2) 频偏粗搜 ±3.5 Hz（0.5 Hz 步进），再 0.1 Hz 细搜 */
    double best_df = 0.0;
    best_sync = -1.0;
    for (int q = -7; q <= 7; ++q)
    {
        const double df = q * 0.5;
        const double s = sync8d(ctx->cd_re, ctx->cd_im, best_i, df);
        if (s > best_sync)
        {
            best_sync = s;
            best_df = df;
        }
    }
    {
        const double coarse = best_df;
        best_sync = -1.0;
        for (int q = -5; q <= 5; ++q)
        {
            const double df = coarse + q * 0.1;
            const double s = sync8d(ctx->cd_re, ctx->cd_im, best_i, df);
            if (s > best_sync)
            {
                best_sync = s;
                best_df = df;
            }
        }
    }
    shift_freq(ctx->cd_re, ctx->cd_im, best_df);

    /* 3) 时间细搜：±8 个 200 Hz 样本（±40 ms） */
    {
        int fine_i = best_i;
        best_sync = -1.0;
        for (int off = -8; off <= 8; ++off)
        {
            const double s = sync8d(ctx->cd_re, ctx->cd_im, best_i + off, 0.0);
            if (s > best_sync)
            {
                best_sync = s;
                fine_i = best_i + off;
            }
        }
        best_i = fine_i;
    }

    ctx->ibest = best_i;
    compute_symbol_tones(ctx);
    ctx->sync_count = compute_sync_count(ctx);

    /* 参考 ft8b：nsync <= 6 直接放弃。弱信号可放宽到 5，先用 6。 */
    if (ctx->sync_count <= 5)
    {
        free(ctx);
        return NULL;
    }
    return ctx;
}

void ft8_block_free(ft8_block_ctx_t* ctx)
{
    free(ctx);
}

/* 一段 29 符号内的 N 符号块 LLR。 */
static void block_llr_half(const ft8_block_ctx_t* ctx, int half, int n_syms,
                           int power_metric, float* log174)
{
    const int base = (half == 0) ? 7 : 43;
    const int bit_base = half * 87;
    const int n_bits = 3 * n_syms;
    const int n_seq = 1 << n_bits;
    int k = 0;

    while (k + n_syms <= 29)
    {
        float max_one[9];
        float max_zero[9];
        for (int i = 0; i < n_bits; ++i)
        {
            max_one[i] = -1.0f;
            max_zero[i] = -1.0f;
        }

        for (int seq = 0; seq < n_seq; ++seq)
        {
            float sr = 0.0f, si = 0.0f;
            for (int s = 0; s < n_syms; ++s)
            {
                const int triple = (seq >> (3 * (n_syms - 1 - s))) & 0x07;
                const int tone = kFT8_Gray_map[triple];
                const int sym = base + k + s;
                const int idx = sym * FT8_BLOCK_NTONE + tone;
                sr += ctx->cs_re[idx];
                si += ctx->cs_im[idx];
            }

            const float p = sr * sr + si * si;
            const float mag = power_metric ? p : sqrtf(p);
            unsigned int mask = 1u << (n_bits - 1);
            for (int i = 0; i < n_bits; ++i, mask >>= 1)
            {
                if (seq & mask)
                {
                    if (mag > max_one[i])
                        max_one[i] = mag;
                }
                else
                {
                    if (mag > max_zero[i])
                        max_zero[i] = mag;
                }
            }
        }

        for (int i = 0; i < n_bits; ++i)
            log174[bit_base + 3 * k + i] = max_one[i] - max_zero[i];
        k += n_syms;
    }

    /* 段内尾部不足 N：按 N=1 */
    while (k < 29)
    {
        float s2[FT8_BLOCK_NTONE];
        const int sym = base + k;
        for (int j = 0; j < FT8_BLOCK_NTONE; ++j)
        {
            const int tone = kFT8_Gray_map[j];
            const int idx = sym * FT8_BLOCK_NTONE + tone;
            const float p = block_abs2(ctx->cs_re[idx], ctx->cs_im[idx]);
            s2[j] = power_metric ? p : sqrtf(p);
        }
        float* ll = log174 + bit_base + 3 * k;
        ll[0] = fmaxf(fmaxf(s2[4], s2[5]), fmaxf(s2[6], s2[7])) -
                fmaxf(fmaxf(s2[0], s2[1]), fmaxf(s2[2], s2[3]));
        ll[1] = fmaxf(fmaxf(s2[2], s2[3]), fmaxf(s2[6], s2[7])) -
                fmaxf(fmaxf(s2[0], s2[1]), fmaxf(s2[4], s2[5]));
        ll[2] = fmaxf(fmaxf(s2[1], s2[3]), fmaxf(s2[5], s2[7])) -
                fmaxf(fmaxf(s2[0], s2[2]), fmaxf(s2[4], s2[6]));
        ++k;
    }
}

int ft8_block_llr(const ft8_block_ctx_t* ctx, int n_syms, int power_metric, float* log174)
{
    if (!ctx || !log174)
        return -1;
    if (n_syms < 1)
        n_syms = 1;
    if (n_syms > 3)
        n_syms = 3;

    memset(log174, 0, FTX_LDPC_N * sizeof(float));
    block_llr_half(ctx, 0, n_syms, power_metric, log174);
    block_llr_half(ctx, 1, n_syms, power_metric, log174);
    return 0;
}

void ft8_block_normalize(float* log174)
{
    if (!log174)
        return;

    double sum = 0.0, sum2 = 0.0;
    for (int i = 0; i < FTX_LDPC_N; ++i)
    {
        const double v = (double)log174[i];
        sum += v;
        sum2 += v * v;
    }
    const double inv_n = 1.0 / (double)FTX_LDPC_N;
    const double mean = sum * inv_n;
    double var = sum2 * inv_n - mean * mean;
    if (var <= 0.0)
        var = sum2 * inv_n;
    double sig = sqrt(var);
    if (sig < 1e-12)
        return;

    /* WSJT-X normalizebmet(): bmet /= std; llr = 2.83 * bmet */
    const double scale = 2.83 / sig;
    for (int i = 0; i < FTX_LDPC_N; ++i)
        log174[i] = (float)((double)log174[i] * scale);
}

int ft8_block_sync_count(const ft8_block_ctx_t* ctx)
{
    return ctx ? ctx->sync_count : 0;
}
