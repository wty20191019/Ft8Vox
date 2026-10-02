#ifndef JTDX_TABLES_H
#define JTDX_TABLES_H

// JTDX FT8 前端常量与初始化表。
//
// 对应 JTDX 源码：
//   jtdx/lib/ft8_params.f90           —— 点数/步长常量
//   jtdx/lib/ft8_mod1.f90             —— windows/csync/ctwk 等表声明
//   jtdx/lib/cwfilter.f90             —— 表初始化（lines 60-155）
//
// csync / csynccq 由 gen_ft8wave 用真实 CQ 报文音调生成；
// 前 7 行只依赖 Costas 序列，与报文无关；第 8-15 行依赖报文音调。

#include <fft/kiss_fft.h>

#ifdef __cplusplus
extern "C"
{
#endif

// ---- 点数/步长常量（ft8_params.f90）----------------------------------------
#define JTDX_NSPS 1920   // 每符号采样数
#define JTDX_NN 79       // 符号数
#define JTDX_NMAX 180000 // 最长音频样本数
#define JTDX_NFFT1 3840  // 符号谱 FFT 点数
#define JTDX_NH1 1920    // 正频率 bin 数
#define JTDX_NSTEP 480   // 符号谱步长（NSPS/4）
#define JTDX_NHSYM 372   // 符号谱帧数
#define JTDX_NDOWN 60    // 12000/200
#define JTDX_NSSY 4      // 每符号的符号谱帧数
#define JTDX_NSSY36 144  // 36 符号 = 144 帧
#define JTDX_NSSY72 288  // 72 符号 = 288 帧
#define JTDX_MAXIT 30    // BP 最大迭代

// ---- 表 --------------------------------------------------------------------

extern float jtdx_windowx[201];     // 频域边缘平滑窗（201 点）
extern float jtdx_facx;             // windowx 缩放系数 1/300
extern float jtdx_windowc1[55];     // 下变频边缘平滑窗（55 点）
extern float jtdx_facc1;            // 下变频归一化 0.01/sqrt(61440)

extern const int jtdx_icos7[7];   // Costas 频率序列 [3,1,4,0,6,5,2]
extern const int jtdx_graymap[8]; // 格雷映射 [0,1,3,2,5,6,4,7]

extern kiss_fft_cpx jtdx_csync[7][32];   // Costas 参考（符号 1-7）
extern kiss_fft_cpx jtdx_csynccq[8][32]; // CQ 参考（符号 8-15）

extern kiss_fft_cpx jtdx_ctwkw[11][32];  // ±2.5Hz 频偏补偿（步进 0.5Hz）
extern kiss_fft_cpx jtdx_ctwkn[11][32];  // ±1.25Hz 频偏补偿（步进 0.25Hz）
extern kiss_fft_cpx jtdx_ctwk256[256];   // 3.125Hz 步进的旋转因子

/// 初始化上述表（幂等）。
void jtdx_tables_init(void);

#ifdef __cplusplus
}
#endif

#endif // JTDX_TABLES_H
