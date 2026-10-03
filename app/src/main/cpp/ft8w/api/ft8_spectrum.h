/**
 * @file ft8_spectrum.h
 * @brief P2.2 逐符号频谱：在时域样本上做窄带 DFT，估计已解码候选的 SNR。
 *
 * 与旧实现的差异：
 *   - 旧 `ftx_compute_snr()` 在 2 符号 Hann 窗量化瀑布上取幅度（dB 再还原），
 *     频率/时间分辨率受瀑布网格限制，动态范围被压缩；
 *   - 本模块对每个符号在**原始时域样本**上、在 8 个精确音调频率处做
 *     1 符号窗的窄带 DFT（Goertzel），得到线性功率。
 *
 * 噪声估计（P2.2 收尾，改为 WSJT-X 同源口径）：
 *   - 不再用「信号自身 8 音调中其余 7 个」的平均功率当噪声（JTDX 口径），
 *     因为邻近信号会污染这些音调、导致强信号 SNR 被严重低估；
 *   - 改从**整时隙频谱中「没有信号」的区域**估计噪声底（spectrum baseline）：
 *     93 段 Nuttall 加窗 FFT 平均 → 分 10 段取第 10 百分位低包络 → 4 阶多项式拟合。
 *     参考 WSJT-X `lib/ft8/get_spectrum_baseline.f90` + `baseline.f90`。
 *
 * 许可证：公式参考 WSJT-X/JTDX 的公开算法思路，代码自研；详见 docs/04 §3、§11。
 */
#ifndef FT8_SPECTRUM_H
#define FT8_SPECTRUM_H

#include <stdint.h>

#ifdef __cplusplus
extern "C"
{
#endif

/** 噪声底频点数（NFFT/2，12kHz 下分辨率 3.125 Hz）。 */
#define FT8_SP_BASE_NBIN 1920

/**
 * 计算整时隙频谱噪声底 `sbase`（dB，逐 3.125Hz 频点）。
 * 对应 WSJT-X `get_spectrum_baseline()` + `baseline()`：把「没有信号」的
 * 低包络频点拟合为平滑曲线，作为该频率处的噪声功率。
 *
 * @param samples     单声道浮点音频
 * @param num_samples 样本数（建议 ≥ 1 个时隙）
 * @param sample_rate 采样率 (Hz)
 * @param sbase       输出，长度至少 FT8_SP_BASE_NBIN，单位 dB
 * @return 0 成功；-1 参数无效或段数不足
 */
int ft8_spectrum_baseline(const float* samples, int num_samples, int sample_rate,
                          float* sbase, int nbin);

/**
 * 估计一条已解码 FT8 候选的原始 SNR 指标（未做对外线性标定）。
 *
 * @param samples     单声道浮点音频（通常为本遍残差）
 * @param num_samples 样本数
 * @param sample_rate 采样率 (Hz)
 * @param tones       79 个发端音调（由载荷重建，含 Costas 段）
 * @param freq0       音调 0 的音频频率 (Hz)
 * @param dt          消息起点相对 samples[0] 的时间偏移 (s)
 * @param sbase       频谱噪声底（ft8_spectrum_baseline 的输出）；为 NULL 时
 *                    退回旧的 JTDX 7 音调口径
 * @param nbin        sbase 长度（至少 FT8_SP_BASE_NBIN）
 * @return 原始 SNR 指标 (dB)；参数无效或无法估计时返回 0
 */
float ft8_spectrum_snr(const float* samples, int num_samples, int sample_rate,
                       const uint8_t* tones, float freq0, float dt,
                       const float* sbase, int nbin);

/**
 * P2.3：为**尚未解码**的候选重算 174 个时域精化 LLR（供 BP 失败后重试）。
 *
 * 与 `ft8_spectrum_snr` 不同，解码前不知道数据音调，故对齐只能用**已知的
 * Costas 同步音调**：在粗定位附近小范围搜索时间/频率，使 Costas 音调总功率
 * 最大；随后在精化位置上对 58 个数据符号做 1 符号窗 8 音调 Goertzel，
 * 按 Gray/max4 组合（与 `ft8_extract_symbol` 一致）得到 LLR（功率 dB 量纲）。
 *
 * @param samples     单声道浮点音频（通常为本遍残差）
 * @param num_samples 样本数
 * @param sample_rate 采样率 (Hz)
 * @param freq0       音调 0 的粗频率 (Hz)
 * @param dt          消息起点的粗偏移 (s)
 * @param log174      输出，174 个 LLR（正 => 比特 1）
 * @return 0 成功；-1 参数无效或内存不足
 */
int ft8_spectrum_llr(const float* samples, int num_samples, int sample_rate,
                     float freq0, float dt, float* log174);

#ifdef __cplusplus
}
#endif

#endif /* FT8_SPECTRUM_H */
