#include "jtdx_wave.h"
#include "jtdx_fft.h"

#include <math.h>
#include <string.h>

// -----------------------------------------------------------------------------
// 常量与缓存
//   NTAB=65536：相位查表（复指数）
//   pulse[3*nsps]：GFSK 频率平滑脉冲
//   dphi[(nsym+2)*nsps]：逐样点相位增量
// 这些表在 JTDX 中由 save 缓存；此处按键值（bt、nsps）缓存。
// -----------------------------------------------------------------------------

#define JTDX_WAVE_NTAB 65536
#define JTDX_WAVE_MAX_DPHI 200000
#define JTDX_WAVE_MAX_PULSE 23040

static int g_ibt0 = -1;
static int g_nsps0 = -1;
static float g_pulse[JTDX_WAVE_MAX_PULSE + 1];
static kiss_fft_cpx g_ctab[JTDX_WAVE_NTAB];
static float g_dphi[JTDX_WAVE_MAX_DPHI];

// GFSK 频率脉冲：0.5*(erf(c*b*(t+0.5)) - erf(c*b*(t-0.5)))
static float gfsk_pulse(float b, float t)
{
    const float pi = 3.14159265358979f;
    float c = pi * sqrtf(2.0f / logf(2.0f));
    return 0.5f * (erff(c * b * (t + 0.5f)) - erff(c * b * (t - 0.5f)));
}

static void jtdx_wave_init(float bt, int nsps)
{
    const float twopi = 8.0f * atanf(1.0f);
    int ibt = (int)lroundf(10.0f * bt);
    if (g_ibt0 == ibt && g_nsps0 == nsps)
    {
        return;
    }
    for (int i = 1; i <= 3 * nsps && i <= JTDX_WAVE_MAX_PULSE; ++i)
    {
        float tt = ((float)i - 1.5f * (float)nsps) / (float)nsps;
        g_pulse[i] = gfsk_pulse(bt, tt);
    }
    for (int i = 0; i < JTDX_WAVE_NTAB; ++i)
    {
        float phi = (float)i * twopi / (float)JTDX_WAVE_NTAB;
        g_ctab[i] = jtdx_cpx(cosf(phi), sinf(phi));
    }
    g_ibt0 = ibt;
    g_nsps0 = nsps;
    (void)twopi;
}

void jtdx_gen_ft8wave(const int* itone, int nsym, int nsps, float bt,
                      float fsample, float f0, kiss_fft_cpx* cwave,
                      float* wave, int icmplx, int nwave)
{
    const float twopi = 8.0f * atanf(1.0f);
    const float dt = 1.0f / fsample;
    const float hmod = 1.0f;

    jtdx_wave_init(bt, nsps);

    int dphi_len = (nsym + 2) * nsps;
    if (dphi_len > JTDX_WAVE_MAX_DPHI)
    {
        dphi_len = JTDX_WAVE_MAX_DPHI;
    }
    memset(g_dphi, 0, sizeof(float) * (size_t)dphi_len);

    float dphi_peak = twopi * hmod / (float)nsps;
    for (int j = 1; j <= nsym; ++j)
    {
        int ib = (j - 1) * nsps; // 0-based
        for (int i = 0; i < 3 * nsps; ++i)
        {
            int idx = ib + i;
            if (idx >= 0 && idx < dphi_len)
            {
                g_dphi[idx] += dphi_peak * g_pulse[i + 1] * (float)itone[j - 1];
            }
        }
    }
    // 首尾各补一个 dummy 符号
    for (int i = 0; i < 2 * nsps; ++i)
    {
        int idx = i;
        if (idx < dphi_len)
        {
            g_dphi[idx] += dphi_peak * (float)itone[0] * g_pulse[nsps + 1 + i];
        }
    }
    for (int i = 0; i < 2 * nsps; ++i)
    {
        int idx = nsym * nsps + i;
        if (idx < dphi_len)
        {
            g_dphi[idx] += dphi_peak * (float)itone[nsym - 1] * g_pulse[1 + i];
        }
    }

    // 叠加基频
    for (int i = 0; i < dphi_len; ++i)
    {
        g_dphi[i] += twopi * f0 * dt;
    }

    if (icmplx == 0 && wave)
    {
        memset(wave, 0, sizeof(float) * (size_t)nwave);
    }
    if (icmplx != 0 && cwave)
    {
        memset(cwave, 0, sizeof(kiss_fft_cpx) * (size_t)nwave);
    }

    float phi = 0.0f;
    int k = 0;
    for (int j = nsps; j <= nsps + nwave - 1; ++j)
    {
        if (j >= 0 && j < dphi_len)
        {
            if (icmplx == 0)
            {
                if (wave)
                {
                    wave[k] = sinf(phi);
                }
            }
            else
            {
                int idx = (int)(phi * (float)JTDX_WAVE_NTAB / twopi);
                if (idx < 0)
                {
                    idx = 0;
                }
                if (idx >= JTDX_WAVE_NTAB)
                {
                    idx = JTDX_WAVE_NTAB - 1;
                }
                if (cwave)
                {
                    cwave[k] = g_ctab[idx];
                }
            }
            phi = fmodf(phi + g_dphi[j], twopi);
            if (phi < 0.0f)
            {
                phi += twopi;
            }
        }
        k++;
    }

    // 首尾符号包络整形
    int nramp = (int)lroundf((float)nsps / 8.0f);
    int k1 = nsym * nsps - nramp; // 0-based 起点
    for (int i = 0; i < nramp; ++i)
    {
        float ramp_dn = (1.0f - cosf(twopi * (float)i / (2.0f * (float)nramp))) / 2.0f;
        float ramp_up = (1.0f + cosf(twopi * (float)i / (2.0f * (float)nramp))) / 2.0f;
        if (icmplx == 0)
        {
            if (wave && i < nwave)
            {
                wave[i] *= ramp_dn;
            }
            if (wave && k1 + i >= 0 && k1 + i < nwave)
            {
                wave[k1 + i] *= ramp_up;
            }
        }
        else
        {
            if (cwave && i < nwave)
            {
                cwave[i] = jtdx_cmul_f(cwave[i], ramp_dn);
            }
            if (cwave && k1 + i >= 0 && k1 + i < nwave)
            {
                cwave[k1 + i] = jtdx_cmul_f(cwave[k1 + i], ramp_up);
            }
        }
    }
}
