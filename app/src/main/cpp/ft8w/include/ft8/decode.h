#ifndef _INCLUDE_DECODE_H_
#define _INCLUDE_DECODE_H_

#include <stdint.h>
#include <stdbool.h>

#include "constants.h"
#include "message.h"

#ifdef __cplusplus
extern "C"
{
#endif

typedef struct
{
    float mag;
    float phase;
} waterfall_cpx_t;

// #define WATERFALL_USE_PHASE

#ifdef WATERFALL_USE_PHASE
#define WF_ELEM_T          waterfall_cpx_t
#define WF_ELEM_MAG(x)     ((x).mag)
#define WF_ELEM_MAG_INT(x) (int)(2 * ((x).mag + 120.0f))
#else
#define WF_ELEM_T          uint8_t
#define WF_ELEM_MAG(x)     ((float)(x)*0.5f - 120.0f)
#define WF_ELEM_MAG_INT(x) (int)(x)
#endif

/// Input structure to ftx_find_sync() function. This structure describes stored waterfall data over the whole message slot.
/// Fields time_osr and freq_osr specify additional oversampling rate for time and frequency resolution.
/// If time_osr=1, FFT magnitude data is collected once for every symbol transmitted, i.e. every 1/6.25 = 0.16 seconds.
/// Values time_osr > 1 mean each symbol is further subdivided in time.
/// If freq_osr=1, each bin in the FFT magnitude data corresponds to 6.25 Hz, which is the tone spacing.
/// Values freq_osr > 1 mean the tone spacing is further subdivided by FFT analysis.
typedef struct
{
    int max_blocks;          ///< number of blocks (symbols) allocated in the mag array
    int num_blocks;          ///< number of blocks (symbols) stored in the mag array
    int num_bins;            ///< number of FFT bins in terms of 6.25 Hz
    int time_osr;            ///< number of time subdivisions
    int freq_osr;            ///< number of frequency subdivisions
    WF_ELEM_T* mag;          ///< FFT magnitudes stored as uint8_t[blocks][time_osr][freq_osr][num_bins]
    int block_stride;        ///< Helper value = time_osr * freq_osr * num_bins
    ftx_protocol_t protocol; ///< Indicate if using FT4 or FT8
} ftx_waterfall_t;

/// Output structure of ftx_find_sync() and input structure of ftx_decode().
/// Holds the position of potential start of a message in time and frequency.
typedef struct
{
    int16_t score;       ///< Candidate score (non-negative number; higher score means higher likelihood)
    int16_t time_offset; ///< Index of the time block
    int16_t freq_offset; ///< Index of the frequency bin
    uint8_t time_sub;    ///< Index of the time subdivision used
    uint8_t freq_sub;    ///< Index of the frequency subdivision used
} ftx_candidate_t;

/// Structure that contains the status of various steps during decoding of a message
typedef struct
{
    float freq;
    float time;
    int ldpc_errors;         ///< Number of LDPC errors during decoding
    uint16_t crc_extracted;  ///< CRC value recovered from the message
    uint16_t crc_calculated; ///< CRC value calculated over the payload
    // int unpack_status;       ///< Return value of the unpack routine
} ftx_decode_status_t;

/// Localize top N candidates in frequency and time according to their sync strength (looking at Costas symbols)
/// We treat and organize the candidate list as a min-heap (empty initially).
/// @param[in] power Waterfall data collected during message slot
/// @param[in] sync_pattern Synchronization pattern
/// @param[in] num_candidates Number of maximum candidates (size of heap array)
/// @param[in,out] heap Array of ftx_candidate_t type entries (with num_candidates allocated entries)
/// @param[in] min_score Minimal score allowed for pruning unlikely candidates (can be zero for no effect)
/// @return Number of candidates filled in the heap
int ftx_find_candidates(const ftx_waterfall_t* power, int num_candidates, ftx_candidate_t heap[], int min_score);

/// Attempt to decode a message candidate. Extracts the bit probabilities, runs LDPC decoder, checks CRC and unpacks the message in plain text.
/// @param[in] power Waterfall data collected during message slot
/// @param[in] cand Candidate to decode
/// @param[in] max_iterations Maximum allowed LDPC iterations (lower number means faster decode, but less precise)
/// @param[out] message ftx_message_t structure that will receive the decoded message
/// @param[out] status ftx_decode_status_t structure that will be filled with the status of various decoding steps
/// @return True if the decoding was successful, false otherwise (check status for details)
bool ftx_decode_candidate(const ftx_waterfall_t* power, const ftx_candidate_t* cand, int max_iterations, ftx_message_t* message, ftx_decode_status_t* status);

/// 带 OSD（Ordered Statistics Decoding）兜底的候选解码。
/// 先执行 BP；若 BP 未收敛或 CRC 未通过，则在 BP 残余校验错误数不超过门限时，
/// 以同一份 LLR 运行 OSD 搜索，CRC 通过即成功。
/// @param[in] osd_depth OSD 阶数（0=关闭；1..3=启用并限制翻转深度）
/// 其余参数同 ftx_decode_candidate()。
bool ftx_decode_candidate_osd(const ftx_waterfall_t* power, const ftx_candidate_t* cand,
                              int max_iterations, int osd_depth,
                              ftx_message_t* message, ftx_decode_status_t* status);

/// P2.3：时域精化 LLR 回调。根据候选粗位置重算 174 个 LLR（正 => 比特 1）。
/// 返回 0 表示成功填充 log174；非 0 表示放弃，调用方仍可用瀑布域 LLR。
typedef int (*ftx_refine_llr_fn)(void* ctx, const ftx_candidate_t* cand, float* log174);

/// 带「选择性 LLR 重解」的候选解码（FT8）。
/// 级联：瀑布 LLR→BP；失败且满足门限时用 refine 回调重算 LLR→再 BP；
/// 仍未成功则用（优先精化后的）LLR 跑 OSD。
/// @param[in] refine            精化回调；NULL 时行为等价于 ftx_decode_candidate_osd()
/// @param[in] refine_ctx        回调上下文
/// @param[in] refine_min_errors 仅当 BP 残余错误数 ≤ 此值时才调用 refine
bool ftx_decode_candidate_ex(const ftx_waterfall_t* power, const ftx_candidate_t* cand,
                             int max_iterations, int osd_depth,
                             ftx_refine_llr_fn refine, void* refine_ctx,
                             int refine_min_errors,
                             ftx_message_t* message, ftx_decode_status_t* status);

/// 估计候选信号的“原始信噪比指标”（未做绝对标定），思路参考 JTDX：
/// 对 79 个符号逐个比较“解码音调功率”与“其余 7 个音调的平均功率”，
/// 返回 10*log10(mean(P_sig/P_noise) - 1)，单位 dB。
/// 调用方需叠加一个经验偏移得到可对外显示的 SNR。
/// @param[in] power 瀑布数据
/// @param[in] cand 已成功解码的候选
/// @param[in] payload 解码得到的 77bit 载荷（用于重建发端音调序列）
/// @return 原始 SNR 指标 (dB)；输入无效时返回 0
float ftx_compute_snr(const ftx_waterfall_t* power, const ftx_candidate_t* cand, const uint8_t* payload);

#ifdef __cplusplus
}
#endif

#endif // _INCLUDE_DECODE_H_
