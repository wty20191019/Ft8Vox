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

/// 解码会话：封装 monitor（STFT/瀑布）、分块缓冲、waterfall 行流与呼号哈希表，
/// 供离线整段解码与实时流式解码共用。
///
/// 注意：呼号哈希表当前为进程级全局（ftx_message_decode 的回调没有上下文参数），
/// 因此同一进程内不应并发使用多个会话。
typedef struct ftx_session ftx_session_t;

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
