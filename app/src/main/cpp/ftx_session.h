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

/// 解码会话：封装 monitor（STFT/瀑布）、分块缓冲与呼号哈希表，
/// 供离线整段解码与实时流式解码共用。
///
/// 注意：呼号哈希表当前为进程级全局（ftx_message_decode 的回调没有上下文参数），
/// 因此同一进程内不应并发使用多个会话。
typedef struct ftx_session ftx_session_t;

/// 创建会话并按 cfg 初始化 monitor。失败返回 NULL。
ftx_session_t* ftx_session_create(const monitor_config_t* cfg);

/// 释放会话。
void ftx_session_free(ftx_session_t* session);

/// 清空当前时隙的 waterfall 与分块缓冲，准备下一个周期。
void ftx_session_reset(ftx_session_t* session);

/// 喂入任意长度的 12 kHz 单声道 PCM（内部按 monitor 的块大小累积，余数留待下次）。
void ftx_session_process(ftx_session_t* session, const float* samples, int count);

/// 对当前累积的 waterfall 解码。
/// @param texts     输出缓冲，按行写入明文，每行 FTX_MAX_MESSAGE_LENGTH 字节
/// @param max_texts 最多写入的条数
/// @return 实际解出的条数
int ftx_session_decode(ftx_session_t* session, char (*texts)[FTX_MAX_MESSAGE_LENGTH], int max_texts);

/// 本会话一个时隙所需的 12 kHz 采样数（= max_blocks * block_size）。
int ftx_session_slot_samples(const ftx_session_t* session);

#ifdef __cplusplus
}
#endif

#endif // FT8VOX_FTX_SESSION_H
