/**
 * @file ft8_spectrum.c
 * @brief P2.2 逐符号频谱 SNR 估计实现（见 ft8_spectrum.h）。
 *
 * 流程：
 *   1. 由已解码音调生成 GFSK 复相位参考；
 *   2. 三段式精细对齐（3 符号粗搜 → 逐符号相位斜率估 Δf → 16 符号细搜），
 *      修正瀑布候选到样本级的系统偏差与残余频偏；
 *   3. 在精化位置逐符号做 8 音调窄带 DFT（矩形 1 符号窗，Goertzel）；
 *   4. 按 JTDX 同源公式估计 SNR。
 *
 * 对齐逻辑与 ft8_subtract.c 同源；P2.3 将抽出公共 ft8_align 以消除重复。
 */
#include "ft8_spectrum.h"
#include "ft8_align.h"

#include <ft8/constants.h>
#include <fft/kiss_fftr.h>

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define FT8_SP_TONES 8         /* 每符号音调数 */
#define FT8_SP_NSYM 79         /* FT8 符号总数 */
#define FT8_SP_TONE_SPACING 6.25f /* 音调间隔 (Hz) = 1/符号周期 */

/* 1=Hann，2=Blackman，其它=矩形。用于抑制带内其它信号的旁瓣泄漏。 */
#ifndef FT8_SP_WINDOW_TYPE
#define FT8_SP_WINDOW_TYPE 0
#endif

/* 计算一个符号窗内 8 个音调的窄带 DFT 功率。
 * 采用 Goertzel 递推，避免为每个候选预生成 sin/cos 表。
 * 音调间隔恰为 1/符号周期；窗函数类型影响旁瓣抑制与相邻音调泄漏的折中。 */
static void tone_powers(const float* x, int n, double freq0, int sample_rate, float* p8)
{
    const double w_scale = 2.0 * M_PI / (double)sample_rate;

    for (int j = 0; j < FT8_SP_TONES; ++j)
    {
        const double w = w_scale * ((double)freq0 + (double)j * FT8_SP_TONE_SPACING);
        const double coeff = 2.0 * cos(w);
        double s1 = 0.0;
        double s2 = 0.0;

        for (int i = 0; i < n; ++i)
        {
            const double s0 = (double)x[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        double p = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        if (!(p > 0.0))
            p = 0.0;
        p8[j] = (float)p;
    }
}

/* ---- WSJT-X 同源频谱噪声底（spectrum baseline）-------------------------- *
 * 用「没有信号」的频谱区域估计噪声底，取代「信号自身 8 音调其余 7 个」的
 * JTDX 口径。参数与 WSJT-X `get_spectrum_baseline.f90` + `baseline.f90` 对齐。 */
#define FT8_SP_BASE_NFFT 3840   /* 频率分辨率 df = fs/NFFT（12kHz → 3.125Hz） */
#define FT8_SP_BASE_STEP 1920   /* 相邻段步长（样本）= NFFT/2 */
#define FT8_SP_BASE_NSEG 93     /* 段数上限 */
#define FT8_SP_BASE_FLO 100.0   /* 基线拟合频率下限 (Hz) */
#define FT8_SP_BASE_FHI 3000.0  /* 基线拟合频率上限 (Hz) */
#define FT8_SP_BASE_PCT 10      /* 低包络百分位 */
#define FT8_SP_BASE_SEGS 10     /* 分位数分段数 */
#define FT8_SP_BASE_PAD 0.65    /* baseline.f90 的常数偏置 (dB) */

/* Nuttall 窗（与 WSJT-X nuttal_window 一致）。 */
static void sp_nuttall_window(float* w, int n)
{
    for (int i = 0; i < n; ++i)
    {
        const double t = 2.0 * M_PI * (double)i / (double)(n - 1);
        w[i] = (float)(0.355768 - 0.487396 * cos(t) + 0.144232 * cos(2.0 * t) -
                       0.012604 * cos(3.0 * t));
    }
}

/* 就地取 v[0..n-1] 的第 npct 百分位（0=最小）；n 较小，简单排序即可。 */
static float sp_pctile(const float* v, int n, int npct)
{
    float t[512];
    if (n > (int)(sizeof(t) / sizeof(t[0])))
        n = (int)(sizeof(t) / sizeof(t[0]));
    memcpy(t, v, (size_t)n * sizeof(float));
    for (int i = 0; i < n - 1; ++i)
        for (int j = i + 1; j < n; ++j)
            if (t[j] < t[i])
            {
                float tmp = t[i];
                t[i] = t[j];
                t[j] = tmp;
            }
    int k = (int)((double)npct * n / 100.0);
    if (k < 0)
        k = 0;
    if (k >= n)
        k = n - 1;
    return t[k];
}

/* 4 阶多项式最小二乘（5 系数），高斯消元；对应 WSJT-X polyfit(nterms=5)。 */
static void sp_polyfit4(const double* x, const double* y, int n, double a[5])
{
    double A[5][5] = {{0}};
    double b[5] = {0};
    for (int i = 0; i < n; ++i)
    {
        double xp[9];
        xp[0] = 1.0;
        for (int k = 1; k < 9; ++k)
            xp[k] = xp[k - 1] * x[i];
        for (int r = 0; r < 5; ++r)
        {
            for (int c = 0; c < 5; ++c)
                A[r][c] += xp[r + c];
            b[r] += xp[r] * y[i];
        }
    }
    for (int col = 0; col < 5; ++col)
    {
        int piv = col;
        for (int r = col + 1; r < 5; ++r)
            if (fabs(A[r][col]) > fabs(A[piv][col]))
                piv = r;
        if (fabs(A[piv][col]) < 1e-30)
            continue;
        if (piv != col)
        {
            for (int c = 0; c < 5; ++c)
            {
                double t = A[col][c];
                A[col][c] = A[piv][c];
                A[piv][c] = t;
            }
            double t = b[col];
            b[col] = b[piv];
            b[piv] = t;
        }
        const double d = A[col][col];
        for (int c = col; c < 5; ++c)
            A[col][c] /= d;
        b[col] /= d;
        for (int r = 0; r < 5; ++r)
        {
            if (r == col)
                continue;
            const double f = A[r][col];
            for (int c = col; c < 5; ++c)
                A[r][c] -= f * A[col][c];
            b[r] -= f * b[col];
        }
    }
    for (int k = 0; k < 5; ++k)
        a[k] = b[k];
}

int ft8_spectrum_baseline(const float* samples, int num_samples, int sample_rate,
                          float* sbase, int nbin)
{
    if (!samples || !sbase || num_samples <= 0 || sample_rate <= 0 || nbin < FT8_SP_BASE_NBIN)
        return -1;

    const int nfft = FT8_SP_BASE_NFFT;
    const int nh = nfft / 2;

    float* win = (float*)malloc((size_t)nfft * sizeof(float));
    kiss_fft_scalar* seg = (kiss_fft_scalar*)malloc((size_t)nfft * sizeof(kiss_fft_scalar));
    kiss_fft_cpx* fd = (kiss_fft_cpx*)malloc((size_t)(nh + 1) * sizeof(kiss_fft_cpx));
    double* savg = (double*)calloc((size_t)nh, sizeof(double));
    float* db = (float*)malloc((size_t)nh * sizeof(float));
    kiss_fftr_cfg cfg = kiss_fftr_alloc(nfft, 0, NULL, NULL);
    if (!win || !seg || !fd || !savg || !db || !cfg)
    {
        free(win);
        free(seg);
        free(fd);
        free(savg);
        free(db);
        if (cfg)
            kiss_fftr_free(cfg);
        return -1;
    }

    /* 与 WSJT-X 一致的加窗归一：window = window/sum(window)*NSPS*2/300 */
    sp_nuttall_window(win, nfft);
    double wsum = 0.0;
    for (int i = 0; i < nfft; ++i)
        wsum += (double)win[i];
    const double nsps = (double)sample_rate * FT8_SYMBOL_PERIOD;
    const double gain = (wsum > 0.0) ? (nsps * 2.0 / 300.0 / wsum) : 1.0;
    for (int i = 0; i < nfft; ++i)
        win[i] = (float)((double)win[i] * gain);

    int used = 0;
    for (int j = 0; j < FT8_SP_BASE_NSEG; ++j)
    {
        const int ia = j * FT8_SP_BASE_STEP;
        if (ia + nfft > num_samples)
            break;
        for (int i = 0; i < nfft; ++i)
            seg[i] = (kiss_fft_scalar)(samples[ia + i] * win[i]);
        kiss_fftr(cfg, seg, fd);
        for (int i = 0; i < nh; ++i)
            savg[i] += (double)fd[i].r * fd[i].r + (double)fd[i].i * fd[i].i;
        ++used;
    }
    kiss_fftr_free(cfg);
    free(seg);
    free(fd);
    free(win);
    if (used == 0)
    {
        free(savg);
        free(db);
        return -1;
    }
    for (int i = 0; i < nh; ++i)
        savg[i] /= (double)used;

    const double df = (double)sample_rate / (double)nfft;
    int ia = (int)lround(FT8_SP_BASE_FLO / df);
    int ib = (int)lround(FT8_SP_BASE_FHI / df);
    if (ia < 1)
        ia = 1;
    if (ib > nh - 1)
        ib = nh - 1;
    if (ib <= ia)
    {
        free(savg);
        free(db);
        return -1;
    }

    for (int i = ia; i <= ib; ++i)
        db[i] = (float)(10.0 * log10(savg[i] > 1e-30 ? savg[i] : 1e-30));

    const int nlen = (ib - ia + 1) / FT8_SP_BASE_SEGS;
    const int i0 = (ib - ia + 1) / 2;
    double xs[1000], ys[1000];
    int k = 0;
    for (int n = 0; n < FT8_SP_BASE_SEGS; ++n)
    {
        const int ja = ia + n * nlen;
        const float base = sp_pctile(&db[ja], nlen, FT8_SP_BASE_PCT);
        for (int i = ja; i < ja + nlen; ++i)
        {
            if (db[i] <= base && k < 1000)
            {
                xs[k] = (double)(i - i0);
                ys[k] = (double)db[i];
                ++k;
            }
        }
    }
    free(savg);
    free(db);
    if (k < 5)
        return -1;

    double a[5] = {0};
    sp_polyfit4(xs, ys, k, a);

    for (int i = 0; i < nbin; ++i)
    {
        const double t = (double)(i - i0);
        sbase[i] = (float)(a[0] + t * (a[1] + t * (a[2] + t * (a[3] + t * a[4]))) +
                           FT8_SP_BASE_PAD);
    }
    return 0;
}

float ft8_spectrum_snr(const float* samples, int num_samples, int sample_rate,
                       const uint8_t* tones, float freq0, float dt,
                       const float* sbase, int nbin)
{
    if (!samples || !tones || num_samples <= 0 || sample_rate <= 0)
        return 0.0f;
    if (freq0 < 0.0f)
        return 0.0f;

    const int nsps = (int)(sample_rate * FT8_SYMBOL_PERIOD + 0.5f);
    if (nsps < FT8_SP_TONES)
        return 0.0f;

    /* 先精化对齐；失败则退回粗定位 */
    ft8_align_t al;
    if (ft8_align_refine(samples, num_samples, sample_rate, nsps, tones, freq0, dt, NULL, &al) != 0)
    {
        al.freq = freq0;
        al.dt = dt;
        al.n0 = (int)lroundf(dt * (float)sample_rate);
    }

    float* buf = (float*)malloc((size_t)nsps * sizeof(float));
    float* win = (float*)malloc((size_t)nsps * sizeof(float));
    if (!buf || !win)
    {
        free(buf);
        free(win);
        return 0.0f;
    }
    for (int n = 0; n < nsps; ++n)
    {
#if FT8_SP_WINDOW_TYPE == 1
        win[n] = 0.5f - 0.5f * cosf(2.0f * (float)M_PI * (float)n / (float)nsps);
#elif FT8_SP_WINDOW_TYPE == 2
        const float t = 2.0f * (float)M_PI * (float)n / (float)nsps;
        win[n] = 0.42f - 0.5f * cosf(t) + 0.08f * cosf(2.0f * t);
#else
        win[n] = 1.0f;
#endif
    }

    const long start0 = (long)floor((double)al.dt * sample_rate + 0.5);
    double sum_ratio = 0.0;
    double sum_sig = 0.0;
    int used = 0;

    for (int sym = 0; sym < FT8_SP_NSYM; ++sym)
    {
        const long base = start0 + (long)sym * nsps;

        /* 边界外按 0 补齐（与瀑布把时隙外数据视为静默一致） */
        for (int n = 0; n < nsps; ++n)
        {
            const long pos = base + n;
            buf[n] = ((pos >= 0 && pos < num_samples) ? samples[pos] : 0.0f) * win[n];
        }

        float p8[FT8_SP_TONES];
        tone_powers(buf, nsps, (double)al.freq, sample_rate, p8);

        float total = 0.0f;
        for (int j = 0; j < FT8_SP_TONES; ++j)
            total += p8[j];

        const float sig = p8[tones[sym]];
        float noise = (total - sig) / (float)(FT8_SP_TONES - 1);
        if (noise < 1e-20f)
            noise = 1e-20f;

        sum_ratio += (sig > noise) ? (double)(sig / noise) : 1.01;
        sum_sig += (double)sig;
        ++used;
    }

    free(buf);
    free(win);

    if (used == 0)
        return 0.0f;

    /* P2.2 收尾：优先用整时隙频谱噪声底（WSJT-X 同源口径）。
     * 返回原始指标 z = 10log10(Σ正确音调功率) - sbase[f]，由上层线性标定。 */
    if (sbase && nbin > 0)
    {
        const double df = (double)sample_rate / (double)FT8_SP_BASE_NFFT;
        int bin = (int)lround((double)al.freq / df);
        if (bin < 0)
            bin = 0;
        if (bin >= nbin)
            bin = nbin - 1;
        const double sig10 = (sum_sig > 1e-30) ? (10.0 * log10(sum_sig)) : -300.0;
#if defined(FT8_SP_DEBUG)
        fprintf(stderr, "[sp]   base freq=%.3f dt=%.4f bin=%d sbase=%.2f z=%.2f\n",
                al.freq, al.dt, bin, (double)sbase[bin], sig10 - (double)sbase[bin]);
#endif
        return (float)(sig10 - (double)sbase[bin]);
    }

    double x = sum_ratio / (double)used - 1.0;
    if (x < 0.001)
        x = 0.001;

#if defined(FT8_SP_DEBUG)
    fprintf(stderr, "[sp]   final freq=%.3f dt=%.4f x=%.3f used=%d\n", al.freq, al.dt, x, used);
#endif

#if defined(FT8_SP_RAW_ONLY)
    return (float)(10.0 * log10(x));
#else
    /* JTDX 同源公式：10*log10(mean(sig/noise)-1) 并减去参考带宽偏移。
     * 偏移 -26.5 使后续高/低 SNR 修正门限与 JTDX 一致；线性标定另行拟合。 */
    double snr = 10.0 * log10(x) - 26.5;

    /* 高 SNR 段：估计器饱和，按 JTDX 做扩张修正 */
    if (snr > 7.0)
        snr += (snr - 7.0) / 2.0;
    if (snr > 30.0)
    {
        snr -= 1.0;
        if (snr > 40.0)
            snr -= 1.0;
        if (snr > 49.0)
            snr = 49.0;
    }

    /* 低 SNR 段：噪声底导致的压缩，按 JTDX 做补偿 */
    if (snr < -17.0)
    {
        if (snr < -22.5 && snr > -23.5)
            snr = -22.5; /* 避开 1/(23+snr) 的数值奇点 */
        const double t = 1.0 + 1.4 / (23.0 + snr);
        snr = snr - t * t + 1.2;
    }
    if (snr < -23.0)
        snr = -23.0;

    return (float)snr;
#endif
}

/* ---- P2.3：解码前时域精化 LLR ---------------------------------------- *
 * BP 失败候选不知道数据音调，故只能用**已知的 Costas 同步音调**做小范围
 * 时间/频率精化；随后在精化位置上对 58 个数据符号做 1 符号窗 8 音调
 * Goertzel，按与 ft8_extract_symbol 相同的 Gray/max4 组合得到 174 个 LLR。
 * 量纲取功率 dB，与瀑布域 WF_ELEM_MAG 一致（归一化会消除绝对尺度）。 */

/* 单音 Goertzel 功率（越界样本按 0）。 */
static double sp_tone_power(const float* x, int num_samples, long base, int n,
                            double freq, int sample_rate)
{
    const double w = 2.0 * M_PI * freq / (double)sample_rate;
    const double coeff = 2.0 * cos(w);
    double s1 = 0.0, s2 = 0.0;
    for (int i = 0; i < n; ++i)
    {
        const long pos = base + i;
        const double v = (pos >= 0 && pos < num_samples) ? (double)x[pos] : 0.0;
        const double s0 = v + coeff * s1 - s2;
        s2 = s1;
        s1 = s0;
    }
    const double p = s1 * s1 + s2 * s2 - coeff * s1 * s2;
    return (p > 0.0) ? p : 0.0;
}

static float sp_max4(float a, float b, float c, float d)
{
    float m = a;
    if (b > m)
        m = b;
    if (c > m)
        m = c;
    if (d > m)
        m = d;
    return m;
}

int ft8_spectrum_llr(const float* samples, int num_samples, int sample_rate,
                     float freq0, float dt, float* log174)
{
    if (!samples || !log174 || num_samples <= 0 || sample_rate <= 0 || freq0 < 0.0f)
        return -1;

    const int nsps = (int)(sample_rate * FT8_SYMBOL_PERIOD + 0.5f);
    if (nsps < FT8_SP_TONES)
        return -1;

    /* Costas 同步位置：3 组 × 7 符号（音调已知） */
    int cpos[FT8_NUM_SYNC * FT8_LENGTH_SYNC];
    int ctone[FT8_NUM_SYNC * FT8_LENGTH_SYNC];
    int nc = 0;
    for (int g = 0; g < FT8_NUM_SYNC; ++g)
        for (int k = 0; k < FT8_LENGTH_SYNC; ++k)
        {
            cpos[nc] = g * FT8_SYNC_OFFSET + k;
            ctone[nc] = kFT8_Costas_pattern[k];
            ++nc;
        }

    long n0 = (long)lround((double)dt * sample_rate);
    double freq = (double)freq0;

    /* 频率精化：±2 Hz，步长 0.25 Hz，最大化 Costas 音调总功率 */
    {
        double best_pw = -1.0;
        double best_df = 0.0;
        for (int q = -8; q <= 8; ++q)
        {
            const double df = q * 0.25;
            double pw = 0.0;
            for (int c = 0; c < nc; ++c)
                pw += sp_tone_power(samples, num_samples, n0 + (long)cpos[c] * nsps, nsps,
                                    (double)freq0 + df + (double)ctone[c] * FT8_SP_TONE_SPACING,
                                    sample_rate);
            if (pw > best_pw)
            {
                best_pw = pw;
                best_df = df;
            }
        }
        freq = (double)freq0 + best_df;
    }

    /* 时间精化：±(nsps/8) 样本，步长 4，最大化 Costas 音调总功率 */
    {
        const int half = nsps / 8;
        long best_sh = 0;
        double best_pw = -1.0;
        for (int sh = -half; sh <= half; sh += 4)
        {
            double pw = 0.0;
            for (int c = 0; c < nc; ++c)
                pw += sp_tone_power(samples, num_samples, n0 + sh + (long)cpos[c] * nsps, nsps,
                                    freq + (double)ctone[c] * FT8_SP_TONE_SPACING, sample_rate);
            if (pw > best_pw)
            {
                best_pw = pw;
                best_sh = sh;
            }
        }
        n0 += best_sh;
    }

    /* 对 58 个数据符号做 8 音调功率 → 功率 dB → Gray/max4 → 3 LLR */
    float* buf = (float*)malloc((size_t)nsps * sizeof(float));
    if (!buf)
        return -1;

    for (int k = 0; k < FT8_ND; ++k)
    {
        const int sym = k + ((k < 29) ? 7 : 14);
        const long base = n0 + (long)sym * nsps;
        for (int i = 0; i < nsps; ++i)
        {
            const long pos = base + i;
            buf[i] = (pos >= 0 && pos < num_samples) ? samples[pos] : 0.0f;
        }

        float p8[FT8_SP_TONES];
        tone_powers(buf, nsps, freq, sample_rate, p8);

        /* 功率 → 功率 dB（与瀑布 WF_ELEM_MAG 同量纲） */
        float s2[8];
        for (int j = 0; j < 8; ++j)
        {
            const float p = p8[kFT8_Gray_map[j]];
            s2[j] = 10.0f * log10f((p > 1e-30f) ? p : 1e-30f);
        }

        float* ll = log174 + 3 * k;
        ll[0] = sp_max4(s2[4], s2[5], s2[6], s2[7]) - sp_max4(s2[0], s2[1], s2[2], s2[3]);
        ll[1] = sp_max4(s2[2], s2[3], s2[6], s2[7]) - sp_max4(s2[0], s2[1], s2[4], s2[5]);
        ll[2] = sp_max4(s2[1], s2[3], s2[5], s2[7]) - sp_max4(s2[0], s2[2], s2[4], s2[6]);
    }

    free(buf);
    return 0;
}
