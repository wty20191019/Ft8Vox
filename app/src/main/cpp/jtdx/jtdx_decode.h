#ifndef JTDX_DECODE_H
#define JTDX_DECODE_H

// JTDX FT8 完整解码入口（多趟，默认 ipass=1..3）。
//
// 对应 JTDX 源码：
//   jtdx/lib/ft8_decode.f90  —— 时隙主循环（多趟 ipass）
//   jtdx/lib/ft8b.f90        —— 单候选择信号处理与译码
//   jtdx/lib/ft8v2/subtractft8.f90 —— 已解码信号的减法
//
// 流程：每趟 sync8 生成候选 -> 下变频 -> sync8d 时间/频率精调 -> twkfreq1 频率微调
//       -> 符号谱 + 相干度量 -> LDPC BP/OSD -> CRC + 报文解包；
//       前两趟解出强信号后重建并相减，供后续趟与候选使用。

#ifdef __cplusplus
extern "C"
{
#endif

typedef struct
{
    char text[40]; // 解码出的报文
    int snr;       // 估计 SNR（dB）
    float dt;      // 相对 0.5s 标称起点的时延（秒）
    int df;        // 音频频率（Hz）
} jtdx_decode_result_t;

/// 对单个 FT8 时隙（180000 点 12kHz 音频）做 JTDX 多趟解码。
/// @param[in]  dd8     音频样本
/// @param[in]  n       样本数（不足 180000 补零，超出截断）
/// @param[in]  fmin    搜索频率下限 Hz
/// @param[in]  fmax    搜索频率上限 Hz
/// @param[in]  npass   解码趟数（JTDX 默认 nft8cycles=1 时为 3；1 表示单趟）
/// @param[out] out     结果数组
/// @param[in]  max_out 结果容量
/// @return 解码条数
int jtdx_decode_slot(const float* dd8, int n, float fmin, float fmax, int npass,
                     jtdx_decode_result_t* out, int max_out);

#ifdef __cplusplus
}
#endif

#endif // JTDX_DECODE_H
