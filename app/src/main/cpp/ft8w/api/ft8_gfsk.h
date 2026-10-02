/**
 * @file ft8_gfsk.h
 * @brief GFSK 脉冲与复相位参考生成，供编码端与信号消除共用。
 */
#ifndef FT8_GFSK_H
#define FT8_GFSK_H

#include <stdint.h>

#ifdef __cplusplus
extern "C"
{
#endif

/**
 * 计算 GFSK 频率脉冲，输出 3*n_spsym 个抽头（与 ft8_lib 编码端一致）。
 * @param n_spsym    每符号采样数
 * @param symbol_bt  GFSK 带宽-时间积（FT8 = 2.0）
 * @param pulse      输出缓冲，长度 3*n_spsym
 */
void ft8_gfsk_pulse(int n_spsym, float symbol_bt, float* pulse);

/**
 * 生成 FT8 复相位参考 ref[k] = exp(j*phi[k])。
 * 相位轨迹与编码端 synth_gfsk 完全一致（连续相位 GFSK），
 * 供信号消除做相干解调使用。
 *
 * @param tones         79 个音调 (0..7)
 * @param n_sym         符号数（FT8 = 79）
 * @param f0            音调 0 的音频频率 (Hz)
 * @param symbol_bt     GFSK BT（FT8 = 2.0）
 * @param symbol_period 符号周期 (s)，FT8 = 0.16
 * @param signal_rate   采样率 (Hz)
 * @param re,im         输出实/虚部，长度 >= n_sym*n_spsym
 * @return 输出采样点数 n_sym*n_spsym；内存不足返回 0
 */
int ft8_gfsk_reference(const uint8_t* tones, int n_sym, float f0, float symbol_bt,
                       float symbol_period, int signal_rate, float* re, float* im);

#ifdef __cplusplus
}
#endif

#endif /* FT8_GFSK_H */
