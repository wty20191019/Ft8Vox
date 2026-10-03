/**
 * @file ft8_align.h
 * @brief 公共精细对齐：由粗定位 (freq0, dt0) 估计精化位置 (freq*, dt*)。
 *
 * 与 `ft8_subtract.c` / `ft8_spectrum.c` 的三段式对齐同源：
 *   1. 粗时间搜索（可选音调消歧）→ 2. 逐符号相位斜率加权回归估 Δf →
 *   3. 频偏校正后的相干细搜索（样本级）。
 *
 * 参数以 `ft8_align_cfg_t` 暴露，`ft8_align_cfg_default()` 给出频谱/SNR 路径
 * 使用的默认值（与重构前逐位一致）。subtract 路径复用其中的相关/频偏/调制
 * 原语，但保留自己的粗搜策略。
 *
 * 许可证：公式参考 WSJT-X/JTDX 公开算法思路，代码自研；详见 docs/04 §3.5。
 */
#ifndef FT8_ALIGN_H
#define FT8_ALIGN_H

#include <stdint.h>

#ifdef __cplusplus
extern "C"
{
#endif

/** 精化对齐结果。 */
typedef struct
{
    float freq; /**< 音调 0 精化频率 (Hz) */
    float dt;   /**< 消息起点精化偏移 (s)，相对 samples[0] */
    int n0;     /**< 精化起点样本下标 = round(dt * fs) */
} ft8_align_t;

/** 对齐搜索参数。 */
typedef struct
{
    int coarse_range; /**< 粗搜最大偏移（样本，±） */
    int coarse_step;  /**< 粗搜精化步长（样本） */
    int coarse_syms;  /**< 非相干粗搜使用的符号数 */
    int fine_syms;    /**< 细搜相干相关符号数 */
    int fine_range;   /**< 细搜范围（样本，±） */
    int freq_tones;   /**< 音调消歧范围（± 个音调）；0 = 关闭 */
    int freq_step;    /**< 音调消歧粗搜的时间步长（样本） */
    float df_clamp;   /**< 频偏估计钳制 (Hz)，超过则置 0 */
} ft8_align_cfg_t;

/** 填充频谱/SNR 路径默认参数。 */
void ft8_align_cfg_default(ft8_align_cfg_t* cfg);

/**
 * 三段式精细对齐。
 *
 * @param x          单声道浮点音频
 * @param num_samples 样本数
 * @param fs         采样率 (Hz)
 * @param nsps       每符号采样数
 * @param tones      79 个发端音调（含 Costas）
 * @param freq0      音调 0 的粗频率 (Hz)
 * @param dt0        消息起点的粗偏移 (s)
 * @param cfg        搜索参数（NULL 时用默认）
 * @param out        精化结果
 * @return 0 成功；-1 参数无效或内存不足
 */
int ft8_align_refine(const float* x, int num_samples, int fs, int nsps,
                     const uint8_t* tones, float freq0, float dt0,
                     const ft8_align_cfg_t* cfg, ft8_align_t* out);

/* ---- 共享原语（供 ft8_subtract 复用，行为与重构前一致） ---- */

/** 相关：Σ x[base+k]·conj(ref[k])，越界样本按 0 处理。 */
void ft8_align_correlate(const float* x, int num_samples, int base,
                         const float* ref_re, const float* ref_im, int m,
                         float* out_re, float* out_im);

/** 多符号非相干相关幅度：累加逐符号相关幅值（对残余频偏不敏感）。 */
float ft8_align_correlate_nc(const float* x, int num_samples, int base,
                             const float* ref_re, const float* ref_im, int nsps, int n_sym);

/** 逐符号相关相位斜率的加权线性回归，返回频偏 Δf (Hz)。 */
float ft8_align_estimate_df(const float* x, int num_samples, int fs, int nsps,
                            int n_sym, const float* ref_re, const float* ref_im, int n0);

/** 以 exp(j·2πΔf·k/fs) 调制参考（原地）。 */
void ft8_align_modulate_ref(float* re, float* im, int len, int fs, float df);

#ifdef __cplusplus
}
#endif

#endif /* FT8_ALIGN_H */
