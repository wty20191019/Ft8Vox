/**
 * @file ft8_subtract.h
 * @brief 时域重合成信号消除（多遍解码用）。
 *
 * 思路（参考 JTDX，代码自研）：
 *   实测 x(t) = a·cos(2πft + φ(t) + θ)
 *   参考 ref(t) = exp(j(2πf't + φ(t)))
 *   基带 z(t) = x·conj(ref)，低通后得复包络 env(t) ≈ (a/2)·exp(j(2π(f-f')t+θ))
 *   相减 x ← x − 2·Re(env·ref)
 * 只要频率残差落在低通通带内，无需显式频率精化即可对齐；时间由互相关精化。
 */
#ifndef FT8_SUBTRACT_H
#define FT8_SUBTRACT_H

#include <stdint.h>

#ifdef __cplusplus
extern "C"
{
#endif

/** 消除器上下文（复用滤波内核与 FFT 计划）。 */
typedef struct ft8_subtract ft8_subtract_t;

/**
 * 创建消除器。
 * @param sample_rate 音频采样率 (Hz)
 * @param max_samples 单时隙最大采样数（仅用于参数校验，可传 0）
 * @return 上下文；失败返回 NULL
 */
ft8_subtract_t* ft8_subtract_create(int sample_rate, int max_samples);

/** 释放消除器。 */
void ft8_subtract_free(ft8_subtract_t* s);

/**
 * 从 samples 中原位减去一条已解码的 FT8 信号。
 * @param samples 待处理的音频（原地修改）
 * @param num_samples 采样点数
 * @param tones   79 个发端音调（由载荷重建）
 * @param freq0   音调 0 的音频频率 (Hz)
 * @param dt      消息起点相对 samples[0] 的时间偏移 (s)
 * @return 0 成功；负数为错误
 */
int ft8_subtract_signal(ft8_subtract_t* s, float* samples, int num_samples,
                        const uint8_t* tones, float freq0, float dt);

#ifdef __cplusplus
}
#endif

#endif /* FT8_SUBTRACT_H */
