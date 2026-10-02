#include "jtdx_subtract.h"
#include "jtdx_fft.h"
#include "jtdx_wave.h"
#include "jtdx_tables.h"

#include <math.h>
#include <string.h>

// -----------------------------------------------------------------------------
// 常量
//   JTDX_NFRAME = 1920*79 = 151680  一个 FT8 时隙的有效信号长度
//   JTDX_NFFT   = 180000            subtractft8.f90 中的 FFT 点数
//   JTDX_NFILT1 = 4000              非 swl 的低通窗长度
// -----------------------------------------------------------------------------

#define JTDX_SUB_NFRAME 151680
#define JTDX_SUB_NFFT 180000
#define JTDX_SUB_NFILT1 4000
#define JTDX_SUB_HALF (JTDX_SUB_NFILT1 / 2)

// 频域低通滤波器（cwfilter.f90 中 cw 的 FFT 结果，含 1/NFFT 归一化）
static kiss_fft_cpx s_cw[JTDX_SUB_NFFT];
// 端部校正（endcorr）
static float s_endcorr[JTDX_SUB_HALF + 1];
// 波形缓存：既用于减法参考 cref，也用于 scorr 的基带参考
static kiss_fft_cpx s_cref[JTDX_SUB_NFRAME];
// 复包络缓存
static kiss_fft_cpx s_cfilt[JTDX_SUB_NFFT];
static int s_ready = 0;

void jtdx_subtract_init(void)
{
    if (s_ready)
    {
        return;
    }

    static float w[JTDX_SUB_NFILT1 + 1];
    const double pi = 4.0 * atan(1.0);
    const int half = JTDX_SUB_HALF;

    // window1(j) = cos(pi*j/NFILT1)^2，j=-half..half，存入 w[0..NFILT1]
    double sumw = 0.0;
    for (int k = 0; k <= JTDX_SUB_NFILT1; ++k)
    {
        float j = (float)(k - half);
        float c = (float)cos(pi * (double)j / (double)JTDX_SUB_NFILT1);
        w[k] = c * c;
        sumw += (double)w[k];
    }

    // cw = cshift(window1/sumw, half+1)：newc[m] = oldc[(m+half+1) mod N]
    memset(s_cw, 0, sizeof(s_cw));
    for (int k = 0; k <= JTDX_SUB_NFILT1; ++k)
    {
        int m = k - (half + 1);
        if (m < 0)
        {
            m += JTDX_SUB_NFFT;
        }
        s_cw[m] = jtdx_cpx((float)((double)w[k] / sumw), 0.0f);
    }

    // endcorr(j)=1/(1 - sum(window1(j-1:half))/sumw)，j=1..half+1
    for (int e = 0; e <= half; ++e)
    {
        double acc = 0.0;
        for (int k = e + half; k <= JTDX_SUB_NFILT1; ++k)
        {
            acc += (double)w[k];
        }
        double denom = 1.0 - acc / sumw;
        s_endcorr[e] = (denom != 0.0) ? (float)(1.0 / denom) : 1.0f;
    }

    // 频域化并乘 1/NFFT
    jtdx_fft_c2c_fwd(s_cw, JTDX_SUB_NFFT);
    float fac = 1.0f / (float)JTDX_SUB_NFFT;
    for (int i = 0; i < JTDX_SUB_NFFT; ++i)
    {
        s_cw[i] = jtdx_cmul_f(s_cw[i], fac);
    }

    s_ready = 1;
}

float jtdx_subtract_xdt3(const kiss_fft_cpx* cd0, int i0, const int* itone, float xdt)
{
    jtdx_subtract_init();

    // 基带参考波形（f0=0），csig0
    jtdx_gen_ft8wave(itone, 79, 1920, 2.0f, 12000.0f, 0.0f, s_cref, NULL, 1, JTDX_SUB_NFRAME);

    const int noff = 10;
    double sync0 = 0.0, syncp = 0.0, syncm = 0.0;
    for (int i = 0; i < 79; ++i)
    {
        int base = i0 + i * 32;
        kiss_fft_cpx z0 = jtdx_cpx(0, 0), zp = jtdx_cpx(0, 0), zm = jtdx_cpx(0, 0);
        for (int j = 0; j < 32; ++j)
        {
            kiss_fft_cpx ref = s_cref[(i * 32 + j) * 60];
            kiss_fft_cpx conj = jtdx_cconj(ref);
            int p0 = base + j;
            int pp = p0 + noff;
            int pm = p0 - noff;
            if (p0 >= 0 && p0 <= 4000)
            {
                z0 = jtdx_cadd(z0, jtdx_cmul(cd0[p0], conj));
            }
            if (pp >= 0 && pp <= 4000)
            {
                zp = jtdx_cadd(zp, jtdx_cmul(cd0[pp], conj));
            }
            if (pm >= 0 && pm <= 4000)
            {
                zm = jtdx_cadd(zm, jtdx_cmul(cd0[pm], conj));
            }
        }
        sync0 += (double)z0.r * z0.r + (double)z0.i * z0.i;
        syncp += (double)zp.r * zp.r + (double)zp.i * zp.i;
        syncm += (double)zm.r * zm.r + (double)zm.i * zm.i;
    }

    // peakup：b=yp-ym; c=yp+ym-2*y0; dx=-((b/c)/2)
    double b = syncp - syncm;
    double c = syncp + syncm - 2.0 * sync0;
    double dx = (c != 0.0) ? -((b / c) / 2.0) : 0.0;
    float scorr = (fabs(dx) > 1.0) ? 0.0f : (float)((double)noff * dx);
    return xdt + scorr * 0.005f;
}

void jtdx_subtract(float* dd8, const int* itone, float f0, float dt)
{
    jtdx_subtract_init();

    int nstart = (int)(dt * 12000.0f);

    // 生成参考波形（实际频率 f0）
    jtdx_gen_ft8wave(itone, 79, 1920, 2.0f, 12000.0f, f0, s_cref, NULL, 1, JTDX_SUB_NFRAME);

    // cfilt = dd8*conjg(cref)，越界补零；后半补零至 NFFT
    for (int i = 0; i < JTDX_SUB_NFRAME; ++i)
    {
        int id = nstart + i;
        kiss_fft_cpx v = jtdx_cpx(0, 0);
        if (id >= 0 && id < JTDX_NMAX)
        {
            v.r = dd8[id];
        }
        s_cfilt[i] = jtdx_cmul(v, jtdx_cconj(s_cref[i]));
    }
    memset(s_cfilt + JTDX_SUB_NFRAME, 0,
           sizeof(kiss_fft_cpx) * (size_t)(JTDX_SUB_NFFT - JTDX_SUB_NFRAME));

    // 低通：FFT -> 乘 cw -> IFFT（均无缩放）
    jtdx_fft_c2c_fwd(s_cfilt, JTDX_SUB_NFFT);
    for (int i = 0; i < JTDX_SUB_NFFT; ++i)
    {
        s_cfilt[i] = jtdx_cmul(s_cfilt[i], s_cw[i]);
    }
    jtdx_fft_c2c_inv(s_cfilt, JTDX_SUB_NFFT);

    // 端部校正
    for (int e = 0; e <= JTDX_SUB_HALF; ++e)
    {
        s_cfilt[e] = jtdx_cmul_f(s_cfilt[e], s_endcorr[e]);
        int tail = JTDX_SUB_NFRAME - 1 - e;
        s_cfilt[tail] = jtdx_cmul_f(s_cfilt[tail], s_endcorr[e]);
    }

    // 从 dd8 中减去 2*REAL(cfilt*cref)
    for (int i = 0; i < JTDX_SUB_NFRAME; ++i)
    {
        int id = nstart + i;
        if (id >= 0 && id < JTDX_NMAX)
        {
            kiss_fft_cpx v = jtdx_cmul(s_cfilt[i], s_cref[i]);
            dd8[id] -= 2.0f * v.r;
        }
    }
}
