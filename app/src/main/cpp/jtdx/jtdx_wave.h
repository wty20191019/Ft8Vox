#ifndef JTDX_WAVE_H
#define JTDX_WAVE_H

// JTDX FT8 波形生成（GFSK 平滑频率脉冲）。
//
// 对应 JTDX 源码：
//   jtdx/lib/gfsk_pulse.f90
//   jtdx/lib/gen_ft8wave.f90
//
// 用途：
//   1) 生成互相关参考序列 csync / csynccq（见表初始化）；
//   2) P3 阶段的信号重建与减法。

#include <fft/kiss_fft.h>

#ifdef __cplusplus
extern "C"
{
#endif

/// 生成 FT8 波形。
/// @param[in]  itone   每个符号的音调（0..7），长度 nsym
/// @param[in]  nsym    符号数（FT8=79）
/// @param[in]  nsps    每符号采样数（FT8=1920）
/// @param[in]  bt      GFSK 带宽时间积（FT8=2.0）
/// @param[in]  fsample 采样率（FT8=12000）
/// @param[in]  f0      基频（Hz）
/// @param[out] cwave   复数波形输出，长度 >= nwave（icmplx!=0 时使用）
/// @param[out] wave    实数波形输出，长度 >= nwave（icmplx==0 时使用）
/// @param[in]  icmplx  0=实数输出，非 0=复数输出
/// @param[in]  nwave   输出采样点数
void jtdx_gen_ft8wave(const int* itone, int nsym, int nsps, float bt,
                      float fsample, float f0, kiss_fft_cpx* cwave,
                      float* wave, int icmplx, int nwave);

#ifdef __cplusplus
}
#endif

#endif // JTDX_WAVE_H
