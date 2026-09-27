#ifndef FT8VOX_FTX_SESSION_H
#define FT8VOX_FTX_SESSION_H

#include <stdbool.h>
#include <stdint.h>

#include <common/monitor.h>
#include <ft8/message.h>

#ifdef __cplusplus
extern "C"
{
#endif

/// 单条解码结果（含 UI 展示所需的指标）。
typedef struct
{
    char text[FTX_MAX_MESSAGE_LENGTH]; ///< 报文明文
    int snr;                           ///< 近似 SNR（dB，2500 Hz 参考带宽）
    float dt;                          ///< 相对时隙起点的时间偏移（秒），名义 0
    int df;                            ///< 音频频率偏移（Hz）
    int score;                         ///< Costas 同步得分
} ftx_decode_result_t;

/// 可调解码参数（**热生效**：每次 ftx_session_decode 读取，无需重建会话）。
///
/// 与 monitor 配置（f_min/f_max/time_osr/freq_osr）不同，这些参数只影响解码搜索与
/// LDPC 迭代，不改变 STFT 结构，因此可在接收过程中随时调整。
typedef struct
{
    int min_score;       ///< Costas 同步最低得分，越高候选越少越快
    int max_candidates;  ///< 单时隙候选上限
    int ldpc_iterations; ///< LDPC 最大迭代次数，越高越慢但弱信号更有机会
    int max_decoded;     ///< 单时隙最多解出的报文条数
    /// SIC 解码趟数：1 = 单趟（不做减谱重解）；2 = 解完一趟后把所有已解报文
    /// 在瀑布幅度上抹掉、再搜一趟，把被强信号掩盖的弱信号挖出来。
    /// 0（未设置，例如 JNI 只填了前 4 个字段）按默认 2 趟处理。
    int passes;
} ftx_decode_params_t;

/// 默认解码参数（与 Kotlin 侧 DecodeSettings「快」预设一致：10/120/20/100、2 趟）。
ftx_decode_params_t ftx_decode_params_default(void);

/// 解码会话：封装 monitor（STFT/瀑布）、分块缓冲、waterfall 行流与呼号哈希表，
/// 供离线整段解码与实时流式解码共用。
///
/// 注意：呼号哈希表当前为进程级全局（ftx_message_decode 的回调没有上下文参数），
/// 因此同一进程内不应并发使用多个会话。
typedef struct ftx_session ftx_session_t;

/// 设置解码参数；各字段会被钳制到安全范围（防止越界导致栈溢出或死循环）。
void ftx_session_set_decode_params(ftx_session_t* session, const ftx_decode_params_t* params);

/// 创建会话并按 cfg 初始化 monitor。失败返回 NULL。
ftx_session_t* ftx_session_create(const monitor_config_t* cfg);

/// 释放会话。
void ftx_session_free(ftx_session_t* session);

/// 清空当前时隙的 waterfall 与分块缓冲，准备下一个周期（waterfall 行流的读游标保留）。
void ftx_session_reset(ftx_session_t* session);

/// 喂入任意长度的 12 kHz 单声道 PCM（内部按 monitor 的块大小累积，余数留待下次）。
void ftx_session_process(ftx_session_t* session, const float* samples, int count);

/// 对当前累积的 waterfall 解码。
/// @param results   输出缓冲
/// @param max_results 最多写入的条数
/// @return 实际解出的条数
int ftx_session_decode(ftx_session_t* session, ftx_decode_result_t* results, int max_results);

// -----------------------------------------------------------------------------
// 瀑布快照（后台解码线程用）
// -----------------------------------------------------------------------------
//
// 动机：真机上单次解码在 1 s 量级（强台多、SIC 两趟、LDPC 高迭代），若在 DSP 线程
// 里同步解码，解码期间采集环形缓冲不再被消费 → 溢出丢样，且解码结束时已迟到 1 s 以上
// → 整个下一个时隙被判「无法对齐」而丢弃（瀑布跟着停一整个时隙）。
//
// 用法：DSP 线程在时隙末尾 `ftx_session_freeze()` 拷一份瀑布（~200 KB，<1 ms）就立刻
// 回去继续喂样；解码线程慢慢 `ftx_session_decode_frozen()`，完事 `ftx_session_thaw()`
// 归还缓冲。解码数据源与实时瀑布彻底分离，互不干扰。
//
// 契约：
// - 会话内含 [K_FROZEN_SLOTS] 份乒乓快照；`freeze` 由采集侧调用、`thaw`/`decode_frozen`
//   由解码侧调用，同一份快照同一时刻只有一个线程碰（内部用原子 busy 标志交接）。
// - **同一会话同一时刻只允许一个解码在进行**（呼号哈希表是进程级全局）。
typedef struct ftx_frozen ftx_frozen_t;

/// 冻结当前瀑布并记下所属时隙号。两份快照都还在被解码线程占用时返回 NULL
/// （调用方应跳过本次解码，例如解码落后两个时隙以上）。
ftx_frozen_t* ftx_session_freeze(ftx_session_t* session, int64_t slot);

/// 取该快照对应的时隙号（解码线程用它回填「哪个时隙解码完了」）。
int64_t ftx_frozen_slot(const ftx_frozen_t* frozen);

/// 在冻结的瀑布上解码（语义同 ftx_session_decode，只是数据源是快照）。
int ftx_session_decode_frozen(ftx_session_t* session, ftx_frozen_t* frozen,
                              ftx_decode_result_t* results, int max_results);

/// 解码完成，归还快照给采集侧复用。
void ftx_session_thaw(ftx_session_t* session, ftx_frozen_t* frozen);

/// 本会话一个时隙所需的 12 kHz 采样数（= max_blocks * block_size）。
int ftx_session_slot_samples(const ftx_session_t* session);

/// waterfall 静态信息（供 UI 建立频率轴）。
/// @param bins   频率 bin 数（每 bin 一个音调间隔）
/// @param bin_hz 每个 bin 的频率宽度（Hz）
/// @param f_min  起始频率（Hz）
void ftx_session_waterfall_info(const ftx_session_t* session, int* bins, float* bin_hz, float* f_min);

/// 取走自上次调用以来新产生的 waterfall 行（每行 bins 字节，uint8 幅度）。
/// @param out      输出缓冲，容量至少 max_rows * bins 字节
/// @param max_rows 最多读取的行数
/// @return 实际写入的行数
int ftx_session_read_waterfall(ftx_session_t* session, uint8_t* out, int max_rows);

#ifdef __cplusplus
}
#endif

#endif // FT8VOX_FTX_SESSION_H
