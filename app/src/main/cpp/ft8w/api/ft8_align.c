/**
 * @file ft8_align.c
 * @brief 公共精细对齐实现（见 ft8_align.h）。
 *
 * 本文件由 ft8_spectrum.c 的 `sp_align_refine` 及其相关原语原样迁移而来，
 * 以保证重构前后数值逐位一致；同时把原语抽出供 ft8_subtract 复用。
 */
#include "ft8_align.h"
#include "ft8_gfsk.h"

#include <ft8/constants.h>

#include <math.h>
#include <stdlib.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define FT8_ALIGN_NSYM 79          /* FT8 符号总数 */
#define FT8_ALIGN_TONE_SPACING 6.25f /* 音调间隔 (Hz) */
#define FT8_ALIGN_SYMBOL_BT 2.0f   /* FT8 GFSK 带宽-时间积 */

void ft8_align_cfg_default(ft8_align_cfg_t* cfg)
{
    if (!cfg)
        return;
    cfg->coarse_range = 2400; /* ±0.2 s */
    cfg->coarse_step = 48;
    cfg->coarse_syms = 12;
    cfg->fine_syms = 16;
    cfg->fine_range = 24;
    cfg->freq_tones = 2;
    cfg->freq_step = 192;
    cfg->df_clamp = 3.0f;
}

void ft8_align_correlate(const float* x, int num_samples, int base,
                         const float* ref_re, const float* ref_im, int m,
                         float* out_re, float* out_im)
{
    float cr = 0.0f, ci = 0.0f;
    for (int k = 0; k < m; ++k)
    {
        int idx = base + k;
        if (idx < 0 || idx >= num_samples)
            continue;
        float xv = x[idx];
        cr += xv * ref_re[k];
        ci -= xv * ref_im[k];
    }
    *out_re = cr;
    *out_im = ci;
}

float ft8_align_correlate_nc(const float* x, int num_samples, int base,
                             const float* ref_re, const float* ref_im, int nsps, int n_sym)
{
    float sum = 0.0f;
    for (int i = 0; i < n_sym; ++i)
    {
        float sr, si;
        ft8_align_correlate(x, num_samples, base + i * nsps,
                            ref_re + (size_t)i * nsps, ref_im + (size_t)i * nsps,
                            nsps, &sr, &si);
        sum += sqrtf(sr * sr + si * si);
    }
    return sum;
}

float ft8_align_estimate_df(const float* x, int num_samples, int fs, int nsps,
                            int n_sym, const float* ref_re, const float* ref_im, int n0)
{
    double sw = 0.0, sx = 0.0, sy = 0.0, sxx = 0.0, sxy = 0.0;
    double unwrapped = 0.0;
    float prev_ph = 0.0f;
    int have_prev = 0;

    for (int i = 0; i < n_sym; ++i)
    {
        int base = n0 + i * nsps;
        if (base < 0 || base + nsps > num_samples)
            continue;

        float cr, ci;
        ft8_align_correlate(x, num_samples, base, ref_re + (size_t)i * nsps,
                            ref_im + (size_t)i * nsps, nsps, &cr, &ci);
        float mag = sqrtf(cr * cr + ci * ci);
        if (mag < 1e-9f)
            continue;
        float ph = atan2f(ci, cr);
        if (!have_prev)
        {
            unwrapped = ph;
            have_prev = 1;
        }
        else
        {
            float d = ph - prev_ph;
            while (d > (float)M_PI)
                d -= 2.0f * (float)M_PI;
            while (d < -(float)M_PI)
                d += 2.0f * (float)M_PI;
            unwrapped += d;
        }
        prev_ph = ph;

        double t = (double)(i * nsps + nsps / 2) / (double)fs;
        double w = (double)mag;
        sw += w;
        sx += w * t;
        sy += w * unwrapped;
        sxx += w * t * t;
        sxy += w * t * unwrapped;
    }

    if (sw < 1e-9)
        return 0.0f;
    double denom = sw * sxx - sx * sx;
    if (fabs(denom) < 1e-12)
        return 0.0f;
    double slope = (sw * sxy - sx * sy) / denom; /* rad/s */
    return (float)(slope / (2.0 * M_PI));
}

void ft8_align_modulate_ref(float* re, float* im, int len, int fs, float df)
{
    if (fabsf(df) < 1e-6f)
        return;
    double dphi = 2.0 * M_PI * (double)df / (double)fs;
    double cw = cos(dphi), sw = sin(dphi);
    double r = 1.0, q = 0.0;
    for (int k = 0; k < len; ++k)
    {
        float cc = (float)r, ss = (float)q;
        float vr = re[k], vi = im[k];
        re[k] = vr * cc - vi * ss;
        im[k] = vr * ss + vi * cc;
        double nr = r * cw - q * sw;
        q = r * sw + q * cw;
        r = nr;
        if ((k & 1023) == 1023)
        {
            double m = sqrt(r * r + q * q);
            if (m > 0)
            {
                r /= m;
                q /= m;
            }
        }
    }
}

int ft8_align_refine(const float* x, int num_samples, int fs, int nsps,
                     const uint8_t* tones, float freq0, float dt0,
                     const ft8_align_cfg_t* cfg, ft8_align_t* out)
{
    if (!x || !tones || !out || fs <= 0 || nsps <= 0)
        return -1;

    ft8_align_cfg_t def;
    if (!cfg)
    {
        ft8_align_cfg_default(&def);
        cfg = &def;
    }

    const int L = FT8_ALIGN_NSYM * nsps;

    float* ref_re = (float*)malloc((size_t)L * sizeof(float));
    float* ref_im = (float*)malloc((size_t)L * sizeof(float));
    if (!ref_re || !ref_im)
    {
        free(ref_re);
        free(ref_im);
        return -1;
    }

    if (ft8_gfsk_reference(tones, FT8_ALIGN_NSYM, freq0, FT8_ALIGN_SYMBOL_BT,
                           FT8_SYMBOL_PERIOD, fs, ref_re, ref_im) != L)
    {
        free(ref_re);
        free(ref_im);
        return -1;
    }

    int n0 = (int)lroundf(dt0 * (float)fs);
    int best_k = 0;

    /* 1. 频率消歧 + 非相干粗对齐。 */
    {
        float* work_re = (float*)malloc((size_t)L * sizeof(float));
        float* work_im = (float*)malloc((size_t)L * sizeof(float));
        if (!work_re || !work_im)
        {
            free(work_re);
            free(work_im);
            free(ref_re);
            free(ref_im);
            return -1;
        }

        /* 1a. 以较大步长在 ±freq_tones 个音调上粗搜 */
        int coarse_best = 0;
        float coarse_mag = -1.0f;
        for (int k = -cfg->freq_tones; k <= cfg->freq_tones; ++k)
        {
            const float* wr = ref_re;
            const float* wi = ref_im;
            if (k != 0)
            {
                memcpy(work_re, ref_re, (size_t)L * sizeof(float));
                memcpy(work_im, ref_im, (size_t)L * sizeof(float));
                ft8_align_modulate_ref(work_re, work_im, L, fs,
                                       (float)k * FT8_ALIGN_TONE_SPACING);
                wr = work_re;
                wi = work_im;
            }
            for (int sh = -cfg->coarse_range; sh <= cfg->coarse_range; sh += cfg->freq_step)
            {
                float mag = ft8_align_correlate_nc(x, num_samples, n0 + sh, wr, wi,
                                                   nsps, cfg->coarse_syms);
                if (mag > coarse_mag)
                {
                    coarse_mag = mag;
                    coarse_best = sh;
                    best_k = k;
                }
            }
        }

        /* 1b. 在最优音调上以精细步长收敛时延 */
        if (best_k != 0)
            ft8_align_modulate_ref(ref_re, ref_im, L, fs,
                                   (float)best_k * FT8_ALIGN_TONE_SPACING);

        int best = coarse_best;
        float best_mag = -1.0f;
        for (int sh = coarse_best - cfg->freq_step; sh <= coarse_best + cfg->freq_step;
             sh += cfg->coarse_step)
        {
            float mag = ft8_align_correlate_nc(x, num_samples, n0 + sh, ref_re, ref_im,
                                               nsps, cfg->coarse_syms);
            if (mag > best_mag)
            {
                best_mag = mag;
                best = sh;
            }
        }
        n0 += best;

        free(work_re);
        free(work_im);
    }

    /* 2. 频偏估计并调制参考 */
    float df = ft8_align_estimate_df(x, num_samples, fs, nsps, FT8_ALIGN_NSYM,
                                     ref_re, ref_im, n0);
    if (fabsf(df) > cfg->df_clamp)
        df = 0.0f;
    ft8_align_modulate_ref(ref_re, ref_im, L, fs, df);

    /* 3. 细对齐：频偏校正后相干相关，收敛到样本级 */
    {
        int m = cfg->fine_syms * nsps;
        if (m > L)
            m = L;
        int best = 0;
        float best_mag = -1.0f;
        for (int sh = -cfg->fine_range; sh <= cfg->fine_range; ++sh)
        {
            float cr, ci;
            ft8_align_correlate(x, num_samples, n0 + sh, ref_re, ref_im, m, &cr, &ci);
            float mag = cr * cr + ci * ci;
            if (mag > best_mag)
            {
                best_mag = mag;
                best = sh;
            }
        }
        n0 += best;
    }

    free(ref_re);
    free(ref_im);

    out->freq = freq0 + (float)best_k * FT8_ALIGN_TONE_SPACING + df;
    out->dt = (float)n0 / (float)fs;
    out->n0 = n0;
    return 0;
}
