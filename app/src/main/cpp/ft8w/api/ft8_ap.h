/**
 * @file ft8_ap.h
 * @brief AP（a priori）先验解码提示，移植自 WSJT-X ft8apset.f90。
 *
 * 只描述 77 bit 载荷中“已知/未知”的比特，不直接改 LLR；调用方在 BP 前
 * 用 ft8_ap_apply() 把已知位写成 ±apmag，并把对应的 apmask[174] 置 1。
 */
#ifndef FT8_AP_H
#define FT8_AP_H

#include <stdint.h>

#ifdef __cplusplus
extern "C"
{
#endif

#define FT8_AP_MAX_HINTS 6
#define FT8_AP_PAYLOAD_BITS 77

typedef struct
{
    int type; /**< WSJT-X iaptype：2=MyCall，3=MyCall+DxCall，4/5/6=+RRR/73/RR73 */
    uint8_t bits[FT8_AP_PAYLOAD_BITS];
    uint8_t mask[FT8_AP_PAYLOAD_BITS];
} ft8_ap_hint_t;

typedef struct ft8_ap_hints
{
    int count;
    ft8_ap_hint_t hints[FT8_AP_MAX_HINTS];
} ft8_ap_hints_t;

/**
 * 根据本台/对方呼号构建 AP 提示。
 *
 * @param hints    输出，先清零
 * @param my_call  本台呼号（空则返回 0 个提示）
 * @param his_call 对方呼号（空时只构建 iaptype=2）
 * @param ap_mode  位掩码：1=MyCall，2=MyCall+DxCall，4=+RRR/73/RR73
 * @return 构建的提示数
 */
int ft8_ap_build(ft8_ap_hints_t* hints, const char* my_call, const char* his_call, int ap_mode);

/**
 * 把一条 AP 提示写入 LLR。
 *
 * @param hint      提示
 * @param log174    174 个信道 LLR（会被就地修改；前 77 位被 AP 覆盖）
 * @param apmask174 输出，174 字节；前 77 位按 hint->mask 置 1，其余为 0
 * @return AP 幅度（用于诊断），失败返回 0
 */
float ft8_ap_apply(const ft8_ap_hint_t* hint, float* log174, uint8_t* apmask174);

#ifdef __cplusplus
}
#endif

#endif /* FT8_AP_H */
