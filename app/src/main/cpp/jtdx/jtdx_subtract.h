#ifndef JTDX_SUBTRACT_H
#define JTDX_SUBTRACT_H

// JTDX FT8 信号重建与减法（多趟解码用）。
//
// 对应 JTDX 源码：
//   jtdx/lib/ft8v2/subtractft8.f90 —— 从时域音频中减去一条已解码信号
//   jtdx/lib/ft8b.f90              —— 减法前的时间微调 scorr / xdt3（1989-2008 行）
//   jtdx/lib/cwfilter.f90          —— 减法所用频域低通滤波器 cw 与端部校正 endcorr
//
// 原理：
//   已测信号 dd(t)    = a(t)cos(2*pi*f0*t+theta(t))
//   参考信号 cref(t)  = exp( j*(2*pi*f0*t+phi(t)) )
//   复包络     cfilt(t) = LPF[ dd(t)*CONJG(cref(t)) ]
//   减法       dd(t)  <- dd(t) - 2*REAL{cref*cfilt}
//
// 本移植固定 swl=.false.，使用 NFILT1=4000 的 cos^2 窗。

#include <fft/kiss_fft.h>

#ifdef __cplusplus
extern "C"
{
#endif

/// 初始化减法用的频域低通滤波器 cw 与端部校正 endcorr（幂等）。
void jtdx_subtract_init(void);

/// 估计减法用的时间微调量并返回修正后的 xdt3。
/// 对应 ft8b.f90 中 lsubtract 段：用基带参考与 cd0 做 3 点（-10/0/+10 样点）
/// 相关，经 peakup 抛物线插值得到 dx，scorr=10*dx，xdt3=xdt+scorr*0.005。
/// @param[in] cd0   已频率微调的 200Hz 复信号（带 -800 偏移）
/// @param[in] i0    信号起始样点索引（= nint(xdt*200)）
/// @param[in] itone 已解码的 79 个音调
/// @param[in] xdt   当前时间估计（秒，绝对）
float jtdx_subtract_xdt3(const kiss_fft_cpx* cd0, int i0, const int* itone, float xdt);

/// 从 dd8 中减去一条已解码的 FT8 信号。
/// @param[in,out] dd8   180000 点 12kHz 实音频（原地修改）
/// @param[in]     itone 已解码的 79 个音调
/// @param[in]     f0    信号频率 Hz
/// @param[in]     dt    信号起始时间（秒，绝对，已含 xdt3 修正）
void jtdx_subtract(float* dd8, const int* itone, float f0, float dt);

#ifdef __cplusplus
}
#endif

#endif // JTDX_SUBTRACT_H
