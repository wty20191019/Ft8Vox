#ifndef JTDX_DOWNSAMPLE_H
#define JTDX_DOWNSAMPLE_H

// JTDX FT8 下变频：12000Hz 实信号 -> 200Hz 复信号（32 采样/符号）。
//
// 对应 JTDX 源码：jtdx/lib/ft8_downsample.f90
//
// 流程：
//   1) 192000 点实数正变换（12000Hz，df=0.0625Hz），1.8e5 点补零；
//   2) 取 [f0-5.75, f0+55.75] 频带（约 984 bin），搬移到基带；
//   3) 频带两端乘平滑窗 windowc1，循环左移对准 f0；
//   4) 3200 点复数逆变换（200Hz），乘归一化 facc1 -> c0(0:3199)。
//
// 时间映射：c0[n] 对应原始 dd8 的样本 60*n（无附加延时）。

#include <fft/kiss_fft.h>

#ifdef __cplusplus
extern "C"
{
#endif

typedef struct
{
    kiss_fft_cpx cxx[96001]; // 长 FFT 结果（可跨候选复用）
    int valid;
} jtdx_downsample_ctx_t;

/// 计算并缓存长 FFT（每个时隙调用一次）。
void jtdx_downsample_prepare(jtdx_downsample_ctx_t* ctx, const float* dd8);

/// 下变频到 c0。c0 是带偏移的指针，可用下标 -800..4000。
void jtdx_downsample(const jtdx_downsample_ctx_t* ctx, float f0, kiss_fft_cpx* c0, int lhighsens);

#ifdef __cplusplus
}
#endif

#endif // JTDX_DOWNSAMPLE_H
