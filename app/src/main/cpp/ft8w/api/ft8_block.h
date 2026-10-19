/**
 * @file ft8_block.h
 * @brief 论文式 FT8 N=1/2/3 分组检测（移植自 WSJT-X ft8_downsample + sync8d + ft8b）。
 *
 * 与旧 `ft8_spectrum_correlate()` 的区别：
 *   - 旧路径直接在 12 kHz 上对每个候选做 58×8 个 1920 点 Goertzel，慢且对
 *     时间/频率量化误差敏感；
 *   - 本模块按 WSJT-X 的做法，先把整时隙信号下变频到 **200 Hz 复基带**
 *     （32 样本/符号），再用 32 点 DFT 得到每个符号的 8 个复音调，最后做
 *     N=1/2/3 块内相干累加。
 *
 * 192000 点长 FFT 只用于“窄带抽取”，每个 decode_candidate_set（一段/一遍）
 * 只算一次；每个候选只做 3200 点 IFFT，成本远低于逐候选 Goertzel。
 */
#ifndef FT8_BLOCK_H
#define FT8_BLOCK_H

#include <stdint.h>

#ifdef __cplusplus
extern "C"
{
#endif

/** 分组检测上下文（每个候选一份，含 200 Hz 复基带与 79×8 复音调）。 */
typedef struct ft8_block_ctx ft8_block_ctx_t;

/** DDC 上下文（每个“段 + 遍”共享一份，含整时隙 192000 点频谱）。 */
typedef struct ft8_ddc ft8_ddc_t;

/**
 * 创建 DDC。当前只支持 12000 Hz（FT8 标准分析采样率）。
 * @return NULL 表示参数非法或内存不足。
 */
ft8_ddc_t* ft8_ddc_create(int sample_rate);

void ft8_ddc_free(ft8_ddc_t* ddc);

/**
 * 设置本遍的时域信号并计算整时隙长 FFT。每个段/遍调用一次。
 * @param samples     12 kHz 单声道样本（本遍残差）
 * @param num_samples 样本数（通常 ≤ 180000）
 * @return 0 成功；-1 参数非法或 FFT 未初始化
 */
int ft8_ddc_set_signal(ft8_ddc_t* ddc, const float* samples, int num_samples);

/**
 * 为一个候选准备 200 Hz 复基带、同步与逐符号音调。
 *
 * 内部完成：
 *   1. 从长频谱抽取 f0 附近约 62.5 Hz 窄带 → 3200 点 IFFT → 200 Hz 复基带；
 *   2. Costas 同步：时间粗搜 ±100 ms、频偏 ±3.5 Hz、时间细搜；
 *   3. 79 个符号 × 8 音调的 32 点 DFT。
 *
 * @param ddc   已调用 ft8_ddc_set_signal() 的 DDC
 * @param freq0 候选音调 0 频率 (Hz)
 * @param dt    候选起点相对时隙起点的时间 (s)
 * @return 上下文；NULL 表示参数非法、内存不足或同步失败
 */
ft8_block_ctx_t* ft8_block_prepare(const ft8_ddc_t* ddc, float freq0, float dt);

void ft8_block_free(ft8_block_ctx_t* ctx);

/**
 * 生成 N 符号块的 174 个 LLR（正 => 比特 1）。
 *
 * 与 WSJT-X `ft8b.f90` 一致：58 个数据符号按中间 Costas 分成两段各 29 符号，
 * 块不跨段；段内尾部不足 N 个符号时按 N=1 处理。
 *
 * @param ctx     ft8_block_prepare() 的返回值
 * @param n_syms  1/2/3
 * @param log174  输出，174 个 LLR
 * @return 0 成功；-1 参数非法
 */
int ft8_block_llr(const ft8_block_ctx_t* ctx, int n_syms, float* log174);

/**
 * 按 WSJT-X `normalizebmet()` 的口径归一化：除以标准差，再乘 2.83。
 * 目标 LLR 标准差约 2.83（方差约 8），与参考解码器一致。
 */
void ft8_block_normalize(float* log174);

/** 同步质量：21 个 Costas 符号中，最大音调与期望一致的数量（0..21）。 */
int ft8_block_sync_count(const ft8_block_ctx_t* ctx);

#ifdef __cplusplus
}
#endif

#endif /* FT8_BLOCK_H */
