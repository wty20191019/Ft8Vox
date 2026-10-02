#ifndef JTDX_SYNC_H
#define JTDX_SYNC_H

// JTDX FT8 同步搜索与候选生成。
//
// 对应 JTDX 源码：
//   jtdx/lib/sync8.f90    —— 符号谱 + Costas 同步评分 + 候选提取
//   jtdx/lib/sync8d.f90   —— 单点复相关同步（用于时间/频率精调）
//   jtdx/lib/twkfreq1.f90 —— 复信号频率微调
//   jtdx/lib/indexx.f90   —— 间接排序
//
// 本移植固定 swl=.false.、lagcc=.false.、lqsothread=.false.、filter=.false.，
// 即 JTDX 默认（m_agcc=false）下的 else 分支。

#include <fft/kiss_fft.h>

#ifdef __cplusplus
extern "C"
{
#endif

#define JTDX_MAX_CAND 460

typedef struct
{
    float freq;  // 候选频率 Hz
    float xdt;   // 候选时间（相对 0.5s 标称起点，ft8b 中会 +0.5）
    float sync;  // 归一化同步值
    float redcq; // >1 表示 CQ 候选
} jtdx_candidate_t;

/// sync8：计算符号谱、同步评分并输出候选。
/// @param[in]  dd8        180000 点 12kHz 实音频
/// @param[in]  nfa,nfb    搜索频率范围 Hz
/// @param[in]  syncmin    归一化同步门限（JTDX ipass=1 默认 1.5）
/// @param[in]  nfqso      优先频率（无 QSO 时取范围外任意值）
/// @param[in]  jzb,jzt    时间索引范围（非 swl：-62..62）
/// @param[in]  ipass      解码轮次（本移植只用 1）
/// @param[in]  ncandthin  抽稀百分比（100 表示不抽稀）
/// @param[out] out        候选数组，容量 JTDX_MAX_CAND
/// @param[out] out_count  候选数
void jtdx_sync8(const float* dd8, float nfa, float nfb, float syncmin, float nfqso,
                int jzb, int jzt, int ipass, int ncandthin,
                jtdx_candidate_t* out, int* out_count);

/// sync8d：单点复相关同步值。
/// @param[in]  cd0      下变频复信号（带 -800 偏移）
/// @param[in]  ctwk     32 点频偏补偿（itwk==1 时使用，否则传 NULL）
/// @param[in]  itwk     0=仅 Costas；1=Costas(+CQ) 频偏补偿
/// @param[in]  lcqcand  是否 CQ 候选（itwk==1 时追加 csynccq 项）
float jtdx_sync8d(const kiss_fft_cpx* cd0, int i0, const kiss_fft_cpx* ctwk, int itwk,
                  int ipass, int lastsync, int iqso, int lcqcand);

/// twkfreq1：对复信号做频率微调（原地安全）。
void jtdx_twkfreq1(kiss_fft_cpx* ca, int nbot, int npts, int ntop, float fsample,
                   const float* a, kiss_fft_cpx* cb);

/// indexx：间接排序，indx[1..n] 使 arr[indx[i]-1] 升序。
void jtdx_indexx(const float* arr, int n, int* indx);

#ifdef __cplusplus
}
#endif

#endif // JTDX_SYNC_H
