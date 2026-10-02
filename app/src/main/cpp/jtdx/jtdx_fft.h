#ifndef JTDX_FFT_H
#define JTDX_FFT_H

// JTDX 前端所需的 FFT 封装（基于 ft8_lib 内置的 kissfft）。
//
// 对应 JTDX 的 four2a.f90（FFTW 包装）：
//   four2a(a,n,ndim,isign,iform)
//     iform=0, isign=-1  -> 实数正变换 r2c（输出 n/2+1 个复数）
//     iform=1, isign=-1  -> 复数正变换（无缩放）
//     iform=1, isign=+1  -> 复数逆变换（无缩放，与 FFTW_BACKWARD 一致）
//
// kissfft 的正/逆变换同样不做 1/N 归一化，因此语义可直接对应。
// 注意：为复用 FFT 计划（大点数计划开销大），本模块内部缓存计划，
// 因此**不是线程安全**的，解码应在单线程内串行调用。

#include <fft/kiss_fft.h>

#ifdef __cplusplus
extern "C"
{
#endif

/// 实数到复数正变换。out 长度需 >= n/2+1。
void jtdx_fft_r2c(const float* in, int n, kiss_fft_cpx* out);

/// 复数正变换（原地，无缩放）。
void jtdx_fft_c2c_fwd(kiss_fft_cpx* a, int n);

/// 复数逆变换（原地，无缩放，等价 FFTW_BACKWARD）。
void jtdx_fft_c2c_inv(kiss_fft_cpx* a, int n);

// ---- 复数小工具 -----------------------------------------------------------

static inline kiss_fft_cpx jtdx_cpx(float r, float i)
{
    kiss_fft_cpx c;
    c.r = r;
    c.i = i;
    return c;
}

static inline kiss_fft_cpx jtdx_cadd(kiss_fft_cpx a, kiss_fft_cpx b)
{
    return jtdx_cpx(a.r + b.r, a.i + b.i);
}

static inline kiss_fft_cpx jtdx_csub(kiss_fft_cpx a, kiss_fft_cpx b)
{
    return jtdx_cpx(a.r - b.r, a.i - b.i);
}

static inline kiss_fft_cpx jtdx_cmul(kiss_fft_cpx a, kiss_fft_cpx b)
{
    return jtdx_cpx(a.r * b.r - a.i * b.i, a.r * b.i + a.i * b.r);
}

static inline kiss_fft_cpx jtdx_cmul_f(kiss_fft_cpx a, float s)
{
    return jtdx_cpx(a.r * s, a.i * s);
}

static inline kiss_fft_cpx jtdx_cconj(kiss_fft_cpx a)
{
    return jtdx_cpx(a.r, -a.i);
}

static inline float jtdx_cabs(kiss_fft_cpx a)
{
    return sqrtf(a.r * a.r + a.i * a.i);
}

#ifdef __cplusplus
}
#endif

#endif // JTDX_FFT_H
