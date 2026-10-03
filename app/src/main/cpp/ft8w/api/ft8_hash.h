/**
 * @file ft8_hash.h
 * @brief 呼号哈希表：缓存解出的标准呼号，用于反查非标准/哈希呼号。
 */
#ifndef FT8_HASH_H
#define FT8_HASH_H

#include <ft8/message.h>

#ifdef __cplusplus
extern "C"
{
#endif

/** 可传给 ftx_message_* 的哈希接口。 */
extern ftx_callsign_hash_interface_t ft8_hash_if;

/** 清空哈希表。 */
void ft8_hash_clear(void);

/** 移除年龄超过 max_age 的条目（每个时隙调用一次即可）。 */
void ft8_hash_cleanup(uint8_t max_age);

#ifdef __cplusplus
}
#endif

#endif /* FT8_HASH_H */
