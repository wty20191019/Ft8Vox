/**
 * @file ft8_subtract.c
 * @brief 时域重合成信号消除（多遍解码用）实现。
 *
 * 参考 JTDX `subtractft8.f90` 的数学思路（代码全部自研）：
 *   实测   x(t) = a·cos(2πf t + φ(t) + θ)
 *   参考   ref(t) = exp(j(2πf' t + φ(t)))
 *   基带   z(t) = x(t)·conj(ref(t))
 *   包络   env(t) = LPF[z] ≈ (a/2)·exp(j(2π(f-f')t+θ))
 *   相减   x(t) ← x(t) − 2·Re(env(t)·ref(t))
 *
 * 为提高对齐精度，本实现额外做三步：
 *   1) 粗对齐：用前 3 个符号做相干相关，对 ±1.56 Hz 频偏鲁棒；
 *   2) 频偏估计：逐符号相关的相位斜率做加权线性回归，得到 Δf 并调制参考；
 *   3) 细对齐：频偏校正后用 16 个符号相干相关，得到接近样本级的时移。
 *
 * 低通用长度 4000 的 cos² 窗（频域 FFT 相乘实现），并对信号首尾做
 * 端点增益校正（补偿滤波器在边界处只覆盖部分抽头造成的衰减）。
 */
#include "ft8_subtract.h"
#include "ft8_align.h"
#include "ft8_gfsk.h"

#include <ft8/constants.h>
#include <fft/kiss_fft.h>

#include <math.h>
#include <stdlib.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define SUB_FILT_LEN     4000  /* 低通 cos² 窗长度（样本），通带约 ±6 Hz */
#define SUB_SYMBOL_BT    2.0f  /* FT8 GFSK 带宽-时间积 */

/* 时间精化参数 */
#define SUB_COARSE_RANGE 2400  /* 粗搜范围 ±，样本（±0.2 s） */
#define SUB_COARSE_STEP  48    /* 粗搜步长，样本 */
#define SUB_COARSE_SYMS  3     /* 粗搜使用的符号数（≤3 以对频偏鲁棒） */
#define SUB_FINE_SYMS    16    /* 细搜相关窗口符号数 */
#define SUB_FINE_RANGE   48    /* 细搜范围 ±，样本 */

struct ft8_subtract
{
    int sample_rate;
    int n_spsym; /* 每符号采样数 */
    int L;       /* 79 * n_spsym */
    int N;       /* 复数 FFT 长度（≥ L + filt_len 的 5-smooth） */
    int nf;      /* 低通长度 */
    int nf_half;

    kiss_fft_cfg fwd;
    kiss_fft_cfg inv;

    kiss_fft_cpx* H;    /* FFT(h)/N，长度 N */
    float* endcorr;     /* 端点校正，长度 nf_half+1 */

    kiss_fft_cpx* zbuf; /* 长度 N，混频基带 */
    kiss_fft_cpx* fbuf; /* 长度 N，频域 */
    kiss_fft_cpx* ebuf; /* 长度 N，复包络 */

    float* ref_re;      /* 长度 L */
    float* ref_im;      /* 长度 L */
};

static float sub_wfun(int k, int nf)
{
    float x = cosf((float)M_PI * (float)k / (float)nf);
    return x * x;
}

ft8_subtract_t* ft8_subtract_create(int sample_rate, int max_samples)
{
    (void)max_samples;
    if (sample_rate <= 0)
        sample_rate = 12000;

    ft8_subtract_t* s = (ft8_subtract_t*)calloc(1, sizeof(ft8_subtract_t));
    if (!s)
        return NULL;

    s->sample_rate = sample_rate;
    s->n_spsym = (int)(0.5f + (float)sample_rate * FT8_SYMBOL_PERIOD);
    if (s->n_spsym <= 0)
    {
        free(s);
        return NULL;
    }
    s->L = FT8_NN * s->n_spsym;
    s->nf = SUB_FILT_LEN;
    if (s->nf > s->L)
        s->nf = s->L;
    s->nf_half = s->nf / 2;
    /* FFT 需为 L + 滤波器长度留出零填充，避免圆周卷积回卷污染 */
    s->N = kiss_fft_next_fast_size(s->L + s->nf + 8);

    s->fwd = kiss_fft_alloc(s->N, 0, NULL, NULL);
    s->inv = kiss_fft_alloc(s->N, 1, NULL, NULL);
    s->H = (kiss_fft_cpx*)malloc((size_t)s->N * sizeof(kiss_fft_cpx));
    s->endcorr = (float*)malloc((size_t)(s->nf_half + 1) * sizeof(float));
    s->zbuf = (kiss_fft_cpx*)calloc((size_t)s->N, sizeof(kiss_fft_cpx));
    s->fbuf = (kiss_fft_cpx*)malloc((size_t)s->N * sizeof(kiss_fft_cpx));
    s->ebuf = (kiss_fft_cpx*)malloc((size_t)s->N * sizeof(kiss_fft_cpx));
    s->ref_re = (float*)malloc((size_t)s->L * sizeof(float));
    s->ref_im = (float*)malloc((size_t)s->L * sizeof(float));

    if (!s->fwd || !s->inv || !s->H || !s->endcorr || !s->zbuf ||
        !s->fbuf || !s->ebuf || !s->ref_re || !s->ref_im)
    {
        ft8_subtract_free(s);
        return NULL;
    }

    /* ---- 构造低通 h（中心对齐）与频响 H = FFT(h)/N ---- */
    int N = s->N, nf = s->nf, nf_half = s->nf_half;
    float sumw = 0.0f;
    for (int k = -nf_half; k <= nf_half; ++k)
        sumw += sub_wfun(k, nf);

    memset(s->zbuf, 0, (size_t)N * sizeof(kiss_fft_cpx));
    s->zbuf[0].r = sub_wfun(0, nf) / sumw;
    for (int k = 1; k <= nf_half; ++k)
    {
        float v = sub_wfun(k, nf) / sumw;
        s->zbuf[k].r = v;
        s->zbuf[N - k].r = v;
    }
    kiss_fft(s->fwd, s->zbuf, s->fbuf);
    for (int i = 0; i < N; ++i)
    {
        s->H[i].r = s->fbuf[i].r / (float)N;
        s->H[i].i = s->fbuf[i].i / (float)N;
    }

    /* ---- 端点校正：present(k)=Σ_{m=-nf_half}^{k} w(m) ---- */
    float present = 0.0f;
    for (int m = -nf_half; m <= 0; ++m)
        present += sub_wfun(m, nf);
    s->endcorr[0] = sumw / present;
    for (int k = 1; k <= nf_half; ++k)
    {
        present += sub_wfun(k, nf);
        s->endcorr[k] = sumw / present;
    }

    return s;
}

void ft8_subtract_free(ft8_subtract_t* s)
{
    if (!s)
        return;
    if (s->fwd)
        kiss_fft_free(s->fwd);
    if (s->inv)
        kiss_fft_free(s->inv);
    free(s->H);
    free(s->endcorr);
    free(s->zbuf);
    free(s->fbuf);
    free(s->ebuf);
    free(s->ref_re);
    free(s->ref_im);
    free(s);
}

/* 计算复包络 env = LPF[x·conj(ref)]，结果存于 ebuf[0..L-1] */
static void sub_compute_env(ft8_subtract_t* s, const float* x, int num_samples, int n0)
{
    int N = s->N, L = s->L;
    memset(s->zbuf, 0, (size_t)N * sizeof(kiss_fft_cpx));
    for (int k = 0; k < L; ++k)
    {
        int idx = n0 + k;
        if (idx < 0 || idx >= num_samples)
            continue;
        float xv = x[idx];
        s->zbuf[k].r = xv * s->ref_re[k];
        s->zbuf[k].i = -xv * s->ref_im[k];
    }
    kiss_fft(s->fwd, s->zbuf, s->fbuf);
    for (int i = 0; i < N; ++i)
    {
        float ar = s->fbuf[i].r, ai = s->fbuf[i].i;
        float hr = s->H[i].r, hi = s->H[i].i;
        s->fbuf[i].r = ar * hr - ai * hi;
        s->fbuf[i].i = ar * hi + ai * hr;
    }
    kiss_fft(s->inv, s->fbuf, s->ebuf);
}

int ft8_subtract_signal(ft8_subtract_t* s, float* samples, int num_samples,
                        const uint8_t* tones, float freq0, float dt)
{
    if (!s || !samples || !tones || num_samples <= 0)
        return -1;
    if (!(freq0 > 0.0f))
        return -1;

    int L = s->L;
    int Ns = s->n_spsym;

    /* 1. 生成相位参考 */
    if (ft8_gfsk_reference(tones, FT8_NN, freq0, SUB_SYMBOL_BT, FT8_SYMBOL_PERIOD,
                           s->sample_rate, s->ref_re, s->ref_im) != L)
        return -1;

    int n0 = (int)lroundf(dt * (float)s->sample_rate);

    /* 2. 粗对齐：3 符号相干相关，对频偏鲁棒 */
    {
        int m = SUB_COARSE_SYMS * Ns;
        int best = 0;
        float best_mag = -1.0f;
        for (int sh = -SUB_COARSE_RANGE; sh <= SUB_COARSE_RANGE; sh += SUB_COARSE_STEP)
        {
            float cr, ci;
            ft8_align_correlate(samples, num_samples, n0 + sh, s->ref_re, s->ref_im, m, &cr, &ci);
            float mag = cr * cr + ci * ci;
            if (mag > best_mag)
            {
                best_mag = mag;
                best = sh;
            }
        }
        n0 += best;
    }

    /* 3. 频偏估计并调制参考 */
    float df = ft8_align_estimate_df(samples, num_samples, s->sample_rate, s->n_spsym,
                                     FT8_NN, s->ref_re, s->ref_im, n0);
    /* 合理范围保护：分子几百 Hz 视为估计失败 */
    if (fabsf(df) > 5.0f)
        df = 0.0f;
    ft8_align_modulate_ref(s->ref_re, s->ref_im, s->L, s->sample_rate, df);

    /* 4. 细对齐：频偏校正后 16 符号相干相关 */
    {
        int m = SUB_FINE_SYMS * Ns;
        if (m > L)
            m = L;
        int best = 0;
        float best_mag = -1.0f;
        for (int sh = -SUB_FINE_RANGE; sh <= SUB_FINE_RANGE; ++sh)
        {
            float cr, ci;
            ft8_align_correlate(samples, num_samples, n0 + sh, s->ref_re, s->ref_im, m, &cr, &ci);
            float mag = cr * cr + ci * ci;
            if (mag > best_mag)
            {
                best_mag = mag;
                best = sh;
            }
        }
        n0 += best;
    }

    /* 5. 复包络 */
    sub_compute_env(s, samples, num_samples, n0);

    /* 6. 端点校正 + 相减 */
    int nf_half = s->nf_half;
    for (int k = 0; k < L; ++k)
    {
        int idx = n0 + k;
        if (idx < 0 || idx >= num_samples)
            continue;

        float corr = 1.0f;
        if (k <= nf_half)
            corr = s->endcorr[k];
        else if (k >= L - 1 - nf_half)
            corr = s->endcorr[L - 1 - k];

        float er = s->ebuf[k].r * corr;
        float ei = s->ebuf[k].i * corr;
        samples[idx] -= 2.0f * (er * s->ref_re[k] - ei * s->ref_im[k]);
    }

    return 0;
}
