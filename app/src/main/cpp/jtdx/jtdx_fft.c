#include "jtdx_fft.h"

#include <fft/kiss_fftr.h>

// -----------------------------------------------------------------------------
// FFT 计划缓存
// 点数固定（192000 / 3840 / 3200 / 32），缓存后避免重复申请。
// 单线程使用即可；见头文件说明。
// -----------------------------------------------------------------------------

#define JTDX_C2C_CACHE 8
#define JTDX_R_CACHE 4

typedef struct
{
    int n;
    int inverse;
    kiss_fft_cfg cfg;
} jtdx_c2c_entry_t;

typedef struct
{
    int n;
    int inverse;
    kiss_fftr_cfg cfg;
} jtdx_r_entry_t;

static jtdx_c2c_entry_t g_c2c[JTDX_C2C_CACHE];
static int g_c2c_count = 0;

static jtdx_r_entry_t g_r[JTDX_R_CACHE];
static int g_r_count = 0;

static kiss_fft_cfg jtdx_get_c2c(int n, int inverse)
{
    for (int i = 0; i < g_c2c_count; ++i)
    {
        if (g_c2c[i].n == n && g_c2c[i].inverse == inverse)
        {
            return g_c2c[i].cfg;
        }
    }
    if (g_c2c_count >= JTDX_C2C_CACHE)
    {
        return NULL;
    }
    kiss_fft_cfg cfg = kiss_fft_alloc(n, inverse, NULL, NULL);
    if (!cfg)
    {
        return NULL;
    }
    g_c2c[g_c2c_count].n = n;
    g_c2c[g_c2c_count].inverse = inverse;
    g_c2c[g_c2c_count].cfg = cfg;
    g_c2c_count++;
    return cfg;
}

static kiss_fftr_cfg jtdx_get_r(int n, int inverse)
{
    for (int i = 0; i < g_r_count; ++i)
    {
        if (g_r[i].n == n && g_r[i].inverse == inverse)
        {
            return g_r[i].cfg;
        }
    }
    if (g_r_count >= JTDX_R_CACHE)
    {
        return NULL;
    }
    kiss_fftr_cfg cfg = kiss_fftr_alloc(n, inverse, NULL, NULL);
    if (!cfg)
    {
        return NULL;
    }
    g_r[g_r_count].n = n;
    g_r[g_r_count].inverse = inverse;
    g_r[g_r_count].cfg = cfg;
    g_r_count++;
    return cfg;
}

void jtdx_fft_r2c(const float* in, int n, kiss_fft_cpx* out)
{
    kiss_fftr_cfg cfg = jtdx_get_r(n, 0);
    if (cfg)
    {
        kiss_fftr(cfg, in, out);
    }
}

void jtdx_fft_c2c_fwd(kiss_fft_cpx* a, int n)
{
    kiss_fft_cfg cfg = jtdx_get_c2c(n, 0);
    if (cfg)
    {
        kiss_fft(cfg, a, a);
    }
}

void jtdx_fft_c2c_inv(kiss_fft_cpx* a, int n)
{
    kiss_fft_cfg cfg = jtdx_get_c2c(n, 1);
    if (cfg)
    {
        kiss_fft(cfg, a, a);
    }
}
