/**
 * @file ft8_hash.c
 * @brief 呼号哈希表实现（基于 ft8_lib demo 的去重哈希表改写，加入互斥保护）。
 */
#include "ft8_hash.h"
#include "ft8_thread.h"

#include <string.h>
#include <stdint.h>
#include <stdbool.h>

#define FT8_HASH_TABLE_SIZE 256

static struct
{
    char callsign[12]; /**< 最多 11 个字符 + NUL */
    uint32_t hash;     /**< 高 8 位为年龄，低 22 位为哈希值 */
} s_table[FT8_HASH_TABLE_SIZE];

static int s_size;
static ft8_mutex_t* s_lock;

static ft8_mutex_t* lock_get(void)
{
    /* 首次调用时惰性创建；多线程首次并发概率极低，用一次性的简单保护 */
    if (!s_lock)
    {
        s_lock = ft8_mutex_create();
    }
    return s_lock;
}

void ft8_hash_clear(void)
{
    ft8_mutex_t* lock = lock_get();
    ft8_mutex_lock(lock);
    memset(s_table, 0, sizeof(s_table));
    s_size = 0;
    ft8_mutex_unlock(lock);
}

static void hash_add_locked(const char* callsign, uint32_t hash)
{
    uint16_t hash10 = (hash >> 12) & 0x3FFu;
    int idx = (hash10 * 23) % FT8_HASH_TABLE_SIZE;

    while (s_table[idx].callsign[0] != '\0')
    {
        if (((s_table[idx].hash & 0x3FFFFFu) == hash) && (0 == strcmp(s_table[idx].callsign, callsign)))
        {
            /* 命中相同条目：重置年龄 */
            s_table[idx].hash &= 0x3FFFFFu;
            return;
        }
        idx = (idx + 1) % FT8_HASH_TABLE_SIZE;
    }

    s_size++;
    strncpy(s_table[idx].callsign, callsign, 11);
    s_table[idx].callsign[11] = '\0';
    s_table[idx].hash = hash;
}

static bool hash_lookup_locked(ftx_callsign_hash_type_t hash_type, uint32_t hash, char* callsign)
{
    uint8_t hash_shift = (hash_type == FTX_CALLSIGN_HASH_10_BITS) ? 12
                         : (hash_type == FTX_CALLSIGN_HASH_12_BITS ? 10 : 0);
    uint16_t hash10 = (uint16_t)((hash >> (12 - hash_shift)) & 0x3FFu);
    int idx = (hash10 * 23) % FT8_HASH_TABLE_SIZE;

    while (s_table[idx].callsign[0] != '\0')
    {
        if (((s_table[idx].hash & 0x3FFFFFu) >> hash_shift) == hash)
        {
            strcpy(callsign, s_table[idx].callsign);
            return true;
        }
        idx = (idx + 1) % FT8_HASH_TABLE_SIZE;
    }

    callsign[0] = '\0';
    return false;
}

static void hash_save_cb(const char* callsign, uint32_t n22)
{
    ft8_mutex_t* lock = lock_get();
    ft8_mutex_lock(lock);
    hash_add_locked(callsign, n22);
    ft8_mutex_unlock(lock);
}

static bool hash_lookup_cb(ftx_callsign_hash_type_t hash_type, uint32_t hash, char* callsign)
{
    ft8_mutex_t* lock = lock_get();
    ft8_mutex_lock(lock);
    bool found = hash_lookup_locked(hash_type, hash, callsign);
    ft8_mutex_unlock(lock);
    return found;
}

ftx_callsign_hash_interface_t ft8_hash_if = {
    .lookup_hash = hash_lookup_cb,
    .save_hash = hash_save_cb
};

void ft8_hash_cleanup(uint8_t max_age)
{
    ft8_mutex_t* lock = lock_get();
    ft8_mutex_lock(lock);

    for (int idx = 0; idx < FT8_HASH_TABLE_SIZE; ++idx)
    {
        if (s_table[idx].callsign[0] != '\0')
        {
            uint8_t age = (uint8_t)(s_table[idx].hash >> 24);
            if (age > max_age)
            {
                s_table[idx].callsign[0] = '\0';
                s_table[idx].hash = 0;
                s_size--;
            }
            else
            {
                s_table[idx].hash = (((uint32_t)age + 1u) << 24) | (s_table[idx].hash & 0x3FFFFFu);
            }
        }
    }

    ft8_mutex_unlock(lock);
}

/* s_size 目前仅用于统计，保留以避免未使用告警 */
int ft8_hash_size(void);
int ft8_hash_size(void)
{
    return s_size;
}
