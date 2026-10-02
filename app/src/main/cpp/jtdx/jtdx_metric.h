#ifndef JTDX_METRIC_H
#define JTDX_METRIC_H

// JTDX FT8 相干比特度量（LLR）。
//
// 对应 JTDX 源码：jtdx/lib/ft8b.f90 的符号提取与度量段：
//   lines 219-243   —— Costas 各符号 SNR（syncav / syncavemax）
//   lines 265-297   —— 79 符号谱 cs/csr/s8 提取与 sp 归一化
//   lines 537-543   —— srr（Costas 符号信噪比）
//   lines 757-849   —— 三组相干度量 bmeta/bmetb/bmetc/bmetd -> llra/b/c/d
//
// 本移植只实现 isubp1=1、无 AP、非 lreverse 的路径。

#include <fft/kiss_fft.h>

#ifdef __cplusplus
extern "C"
{
#endif

typedef struct
{
    float llra[174]; // 1 符号相干度量（LLR）
    float llrb[174]; // 2 符号相干度量
    float llrc[174]; // 3 符号相干度量
    float llrd[174]; // 归一化差分度量
    float syncav;    // Costas 平均 SNR
    float syncavemax;
    float srr;       // Costas 段信噪比
    float s8[8][80]; // 符号谱幅度（用于 SNR 估计），下标 [音调][符号 1..79]
} jtdx_metric_result_t;

/// 从下变频信号 cd0（起点 ibest）提取符号并计算四组 LLR。
/// @param[in] lreverse 非 0 时使用反相符号谱（对应 ft8b.f90 的 lreverse，ipass=2）
void jtdx_metric_compute(const kiss_fft_cpx* cd0, int ibest, int lreverse,
                         jtdx_metric_result_t* out);

#ifdef __cplusplus
}
#endif

#endif // JTDX_METRIC_H
