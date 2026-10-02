#ifndef JTDX_LDPC_H
#define JTDX_LDPC_H

// JTDX (174,91) LDPC 译码器移植（C）。
//
// 对应 JTDX 源码：
//   jtdx/lib/ft8v2/bpdecode174_91.f90   —— 对数域置信传播（带 AP 掩码）
//   jtdx/lib/ft8v2/osd174_91.f90        —— 有序统计译码（OSD）兜底
//   jtdx/lib/ft8v2/chkcrc14a.f90        —— 14 位 CRC 校验
//
// 位序与 ft8_lib 一致：174 比特中前 91 比特为「77 位信息 + 14 位 CRC」，可用 ft8_lib 的
// crc.h / message.h 直接解包。正 LLR 判为 1。

#include <stdint.h>

#ifdef __cplusplus
extern "C"
{
#endif

/// 对数域 BP 译码。找到合法码字且 CRC 通过时返回 0；否则返回 1。
/// @param[in]  llr             174 个对数似然比
/// @param[in]  apmask          174 个先验掩码（1=该比特由先验固定，BP 时不更新）；无先验时全 0
/// @param[in]  max_iterations  最大迭代次数（JTDX 常用 30）
/// @param[out] cw              174 位码字（0/1）
/// @param[out] nharderror      与 LLR 符号不一致的比特数；未找到码字时为 -1
/// @param[out] niterations     实际迭代次数
int jtdx_ldpc_bp_decode(const float* llr, const uint8_t* apmask, int max_iterations,
                        uint8_t* cw, int* nharderror, int* niterations);

/// 有序统计译码（OSD）。
/// @param[in]  ndeep   0=仅 0 阶；1..5 递增深度（JTDX 常用 3，深档 4/5）
/// @param[out] cw      174 位码字（0/1）
/// @param[out] nhardmin 与硬判决不一致的比特数；CRC 失败时为负
/// @param[out] dmin    与接收软值的加权距离
void jtdx_ldpc_osd_decode(const float* llr, const uint8_t* apmask, int ndeep,
                          uint8_t* cw, int* nhardmin, float* dmin);

/// 校验前 91 位（77 信息 + 14 CRC）。通过返回 0，失败返回 1。
int jtdx_chkcrc14(const uint8_t decoded91[91]);

/// 用 (174,91) 生成矩阵把 91 位（77 信息 + 14 CRC）编码成 174 位码字。
/// 前 91 位与输入相同，后 83 位为校验位。主要供离线自测与信号重建使用。
void jtdx_ldpc_encode(const uint8_t msg91[91], uint8_t cw174[174]);

#ifdef __cplusplus
}
#endif

#endif // JTDX_LDPC_H
