/**
 * @file ft8_osd.h
 * @brief FT8 Ordered Statistics Decoding (OSD) 兜底译码。
 *
 * 用途：当 BP（置信传播）迭代未能收敛时，基于 LLR 的可靠性排序与
 * LDPC 码的线性代数结构，搜索一个满足 CRC 的合法码字。
 *
 * 虚假解码抑制：对每个候选码字统计其与硬判决的汉明距离 nhard，
 * 仅当 nhard 不超过门限时才交给 accept() 做 CRC 校验（参考
 * WSJT-X 的 nharderrors 门限思路，避免 CRC-14 在多候选下的误通过）。
 *
 * 约定：与 bp_decode 一致，LLR 为正表示该比特更可能是 1。
 * 本模块自研，算法思路参考公开文献（Fossorier & Lin, 1995）及
 * WSJT-X/JTDX 的公开描述，未复制其源代码（见 docs/04 §11）。
 */
#ifndef FT8_OSD_H
#define FT8_OSD_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C"
{
#endif

/** 接受回调：plain174 为 0/1 的 174 比特码字（plain174[0..90] 为系统位）。
 *  返回 true 表示接受该候选并终止 OSD 搜索。 */
typedef bool (*ft8_osd_accept_fn)(const uint8_t* plain174, void* ctx);

/**
 * 执行 OSD 译码。
 *
 * @param log174          174 个 LLR（正 => 比特 1）
 * @param depth           搜索阶数：
 *                        0 = 仅 order-0；1 = 加单比特翻转；2 = 加双比特；3 = 加三比特
 * @param max_hard_errors 汉明距离门限（与硬判决不一致的比特数上限）；
 *                        ≤0 表示不限制
 * @param accept          接受回调，返回 true 立即停止
 * @param ctx             透传给回调的上下文
 * @return 1 表示接受了某个码字（accept 返回 true），0 表示未接受或 OSD 未
 *         成功执行；不使用「尝试次数」以免与成功语义混淆。
 */
int ft8_osd_decode(const float* log174, int depth, int max_hard_errors,
                   ft8_osd_accept_fn accept, void* ctx);

#ifdef __cplusplus
}
#endif

#endif /* FT8_OSD_H */
