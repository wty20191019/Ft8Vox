#ifndef _INCLUDE_LDPC_H_
#define _INCLUDE_LDPC_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C"
{
#endif

// codeword is 174 log-likelihoods.
// plain is a return value, 174 ints, to be 0 or 1.
// iters is how hard to try.
// ok == 87 means success.
void ldpc_decode(float codeword[], int max_iters, uint8_t plain[], int* ok);

void bp_decode(float codeword[], int max_iters, uint8_t plain[], int* ok);

/// 带 AP（a priori）掩码的 BP：apmask[n] != 0 的比特视为已知，
/// 硬判决直接取 LLR 符号，且不累加校验节点回传的 tov（对应 WSJT-X
/// decode174_91 中 apmask == 1 时 zn(i) = llr(i) 的处理）。
void bp_decode_ap(float codeword[], int max_iters, uint8_t plain[], int* ok,
                  const uint8_t* apmask);

#ifdef __cplusplus
}
#endif

#endif // _INCLUDE_LDPC_H_
