#include "jtdx_downsample.h"
#include "jtdx_fft.h"
#include "jtdx_tables.h"

#include <math.h>
#include <string.h>

#define JTDX_NFFT2 3200

void jtdx_downsample_prepare(jtdx_downsample_ctx_t* ctx, const float* dd8)
{
    static float x[192000];
    memset(x, 0, sizeof(x));
    memcpy(x, dd8, (size_t)JTDX_NMAX * sizeof(float));
    jtdx_tables_init();
    jtdx_fft_r2c(x, 192000, ctx->cxx);
    ctx->valid = 1;
}

void jtdx_downsample(const jtdx_downsample_ctx_t* ctx, float f0, kiss_fft_cpx* c0, int lhighsens)
{
    const float df = 0.0625f;
    int i0 = (int)lroundf(f0 / df);
    int it = (int)lroundf((f0 + 55.75f) / df);
    if (it > 96000)
    {
        it = 96000;
    }
    int ib = (int)lroundf((f0 - 5.75f) / df);
    if (ib < 1)
    {
        ib = 1;
    }

    static kiss_fft_cpx c1[JTDX_NFFT2];
    static kiss_fft_cpx tmp[JTDX_NFFT2];
    memset(c1, 0, sizeof(c1));

    int k = it - ib; // 0-based 末元素下标
    if (k < 0)
    {
        k = 0;
    }
    if (k >= JTDX_NFFT2)
    {
        k = JTDX_NFFT2 - 1;
    }
    for (int i = 0; i <= k; ++i)
    {
        c1[i] = ctx->cxx[ib + i];
    }

    // 频带两端平滑（windowc1）
    for (int i = 0; i <= 54 && i <= k; ++i)
    {
        c1[i] = jtdx_cmul_f(c1[i], jtdx_windowc1[54 - i]);
    }
    for (int i = 0; i <= 54 && (k - 54 + i) >= 0; ++i)
    {
        c1[k - 54 + i] = jtdx_cmul_f(c1[k - 54 + i], jtdx_windowc1[i]);
    }

    // 循环左移 shift=(i0-ib)：new[m]=old[(m+shift) mod 3200]
    int shift = i0 - ib;
    for (int m = 0; m < JTDX_NFFT2; ++m)
    {
        int idx = (m + shift) % JTDX_NFFT2;
        if (idx < 0)
        {
            idx += JTDX_NFFT2;
        }
        tmp[m] = c1[idx];
    }
    memcpy(c1, tmp, sizeof(c1));

    if (lhighsens)
    {
        c1[0] = jtdx_cmul_f(c1[0], 1.93f);
        c1[799] = jtdx_cmul_f(c1[799], 1.7f);
        c1[800] = jtdx_cmul_f(c1[800], 1.7f);
        c1[3199] = jtdx_cmul_f(c1[3199], 1.93f);
    }
    else
    {
        c1[45] = jtdx_cmul_f(c1[45], 1.49f);
        c1[54] = jtdx_cmul_f(c1[54], 1.49f);
        c1[3145] = jtdx_cmul_f(c1[3145], 1.49f);
        c1[3154] = jtdx_cmul_f(c1[3154], 1.49f);
    }

    jtdx_fft_c2c_inv(c1, JTDX_NFFT2);

    kiss_fft_cpx zero = jtdx_cpx(0.0f, 0.0f);
    for (int i = -800; i < 0; ++i)
    {
        c0[i] = zero;
    }
    for (int i = 0; i < JTDX_NFFT2; ++i)
    {
        c0[i] = jtdx_cmul_f(c1[i], jtdx_facc1);
    }
    for (int i = JTDX_NFFT2; i <= 4000; ++i)
    {
        c0[i] = zero;
    }
}
