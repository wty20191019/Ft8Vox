#include "ftx_session.h"

#include <stdlib.h>
#include <string.h>

#include <ft8/decode.h>
#include <ft8/constants.h>

// -----------------------------------------------------------------------------
// 解码参数（与 ft8_lib 官方示例 demo/decode_ft8.c 保持一致）
// -----------------------------------------------------------------------------
#define K_MIN_SCORE            10
#define K_MAX_CANDIDATES       140
#define K_LDPC_ITERATIONS      25
#define K_MAX_DECODED_MESSAGES 50

// -----------------------------------------------------------------------------
// 呼号哈希表：用于 ftx_message_decode() 还原被哈希压缩的呼号。
// ftx_callsign_hash_interface_t 的回调没有上下文参数，故采用进程级全局表。
// -----------------------------------------------------------------------------
#define CALLSIGN_HASHTABLE_SIZE 256

static struct
{
    char callsign[12]; ///< 最多 11 个字符 + 结尾 0
    uint32_t hash;     ///< 高 8 位为年龄，低 22 位为哈希值
} callsign_hashtable[CALLSIGN_HASHTABLE_SIZE];

static int callsign_hashtable_size;

static void hashtable_init(void)
{
    callsign_hashtable_size = 0;
    memset(callsign_hashtable, 0, sizeof(callsign_hashtable));
}

static void hashtable_cleanup(uint8_t max_age)
{
    for (int idx_hash = 0; idx_hash < CALLSIGN_HASHTABLE_SIZE; ++idx_hash)
    {
        if (callsign_hashtable[idx_hash].callsign[0] != '\0')
        {
            uint8_t age = (uint8_t)(callsign_hashtable[idx_hash].hash >> 24);
            if (age > max_age)
            {
                callsign_hashtable[idx_hash].callsign[0] = '\0';
                callsign_hashtable[idx_hash].hash = 0;
                callsign_hashtable_size--;
            }
            else
            {
                callsign_hashtable[idx_hash].hash =
                    (((uint32_t)age + 1u) << 24) | (callsign_hashtable[idx_hash].hash & 0x3FFFFFu);
            }
        }
    }
}

static void hashtable_add(const char* callsign, uint32_t hash)
{
    uint16_t hash10 = (hash >> 12) & 0x3FFu;
    int idx_hash = (hash10 * 23) % CALLSIGN_HASHTABLE_SIZE;
    while (callsign_hashtable[idx_hash].callsign[0] != '\0')
    {
        if (((callsign_hashtable[idx_hash].hash & 0x3FFFFFu) == hash) &&
            (0 == strcmp(callsign_hashtable[idx_hash].callsign, callsign)))
        {
            callsign_hashtable[idx_hash].hash &= 0x3FFFFFu; // 重置年龄
            return;
        }
        idx_hash = (idx_hash + 1) % CALLSIGN_HASHTABLE_SIZE;
    }
    callsign_hashtable_size++;
    strncpy(callsign_hashtable[idx_hash].callsign, callsign, 11);
    callsign_hashtable[idx_hash].callsign[11] = '\0';
    callsign_hashtable[idx_hash].hash = hash;
}

static bool hashtable_lookup(ftx_callsign_hash_type_t hash_type, uint32_t hash, char* callsign)
{
    uint8_t hash_shift = (hash_type == FTX_CALLSIGN_HASH_10_BITS) ? 12
                       : (hash_type == FTX_CALLSIGN_HASH_12_BITS) ? 10
                                                                  : 0;
    uint16_t hash10 = (hash >> (12 - hash_shift)) & 0x3FFu;
    int idx_hash = (hash10 * 23) % CALLSIGN_HASHTABLE_SIZE;
    while (callsign_hashtable[idx_hash].callsign[0] != '\0')
    {
        if (((callsign_hashtable[idx_hash].hash & 0x3FFFFFu) >> hash_shift) == hash)
        {
            strcpy(callsign, callsign_hashtable[idx_hash].callsign);
            return true;
        }
        idx_hash = (idx_hash + 1) % CALLSIGN_HASHTABLE_SIZE;
    }
    callsign[0] = '\0';
    return false;
}

static ftx_callsign_hash_interface_t hash_if = {
    .lookup_hash = hashtable_lookup,
    .save_hash = hashtable_add,
};

// -----------------------------------------------------------------------------
// 会话
// -----------------------------------------------------------------------------
struct ftx_session
{
    monitor_t mon;
    float* pending; ///< 未满一个 block 的余数缓冲，容量 = mon.block_size
    int pending_len;
};

static int clamp_int(int v, int lo, int hi)
{
    return (v < lo) ? lo : ((v > hi) ? hi : v);
}

ftx_session_t* ftx_session_create(const monitor_config_t* cfg)
{
    if (cfg == NULL)
        return NULL;

    ftx_session_t* s = (ftx_session_t*)calloc(1, sizeof(ftx_session_t));
    if (s == NULL)
        return NULL;

    monitor_init(&s->mon, cfg);

    s->pending = (float*)calloc((size_t)s->mon.block_size, sizeof(float));
    s->pending_len = 0;
    if (s->pending == NULL)
    {
        monitor_free(&s->mon);
        free(s);
        return NULL;
    }

    hashtable_init();
    return s;
}

void ftx_session_free(ftx_session_t* session)
{
    if (session == NULL)
        return;
    free(session->pending);
    monitor_free(&session->mon);
    free(session);
}

void ftx_session_reset(ftx_session_t* session)
{
    if (session == NULL)
        return;
    session->pending_len = 0;
    monitor_reset(&session->mon);
}

void ftx_session_process(ftx_session_t* session, const float* samples, int count)
{
    if (session == NULL || samples == NULL || count <= 0)
        return;

    monitor_t* mon = &session->mon;
    const int bs = mon->block_size;
    int pos = 0;

    // 先补齐上次的余数
    if (session->pending_len > 0)
    {
        int need = bs - session->pending_len;
        int take = (count < need) ? count : need;
        memcpy(session->pending + session->pending_len, samples, (size_t)take * sizeof(float));
        session->pending_len += take;
        pos += take;
        if (session->pending_len < bs)
            return;
        monitor_process(mon, session->pending);
        session->pending_len = 0;
    }

    // 处理完整的 block
    while (pos + bs <= count)
    {
        monitor_process(mon, samples + pos);
        pos += bs;
    }

    // 余数留存
    int rem = count - pos;
    if (rem > 0)
    {
        memcpy(session->pending, samples + pos, (size_t)rem * sizeof(float));
        session->pending_len = rem;
    }
}

int ftx_session_slot_samples(const ftx_session_t* session)
{
    if (session == NULL)
        return 0;
    return session->mon.wf.max_blocks * session->mon.block_size;
}

int ftx_session_decode(ftx_session_t* session, char (*texts)[FTX_MAX_MESSAGE_LENGTH], int max_texts)
{
    if (session == NULL || texts == NULL || max_texts <= 0)
        return 0;

    const ftx_waterfall_t* wf = &session->mon.wf;

    // 按 Costas 同步得分定位候选
    ftx_candidate_t candidates[K_MAX_CANDIDATES];
    int num_candidates = ftx_find_candidates(wf, K_MAX_CANDIDATES, candidates, K_MIN_SCORE);

    // 本时隙已解码消息的哈希表，用于去重
    ftx_message_t decoded[K_MAX_DECODED_MESSAGES];
    ftx_message_t* decoded_hashtable[K_MAX_DECODED_MESSAGES];
    for (int i = 0; i < K_MAX_DECODED_MESSAGES; ++i)
        decoded_hashtable[i] = NULL;

    int num_decoded = 0;
    for (int idx = 0; idx < num_candidates; ++idx)
    {
        const ftx_candidate_t* cand = &candidates[idx];

        ftx_message_t message;
        ftx_decode_status_t status;
        if (!ftx_decode_candidate(wf, cand, K_LDPC_ITERATIONS, &message, &status))
            continue;

        // 去重
        int idx_hash = message.hash % K_MAX_DECODED_MESSAGES;
        bool found_empty = false;
        bool found_duplicate = false;
        do
        {
            if (decoded_hashtable[idx_hash] == NULL)
            {
                found_empty = true;
            }
            else if ((decoded_hashtable[idx_hash]->hash == message.hash) &&
                     (0 == memcmp(decoded_hashtable[idx_hash]->payload, message.payload,
                                  sizeof(message.payload))))
            {
                found_duplicate = true;
            }
            else
            {
                idx_hash = (idx_hash + 1) % K_MAX_DECODED_MESSAGES;
            }
        } while (!found_empty && !found_duplicate);

        if (!found_empty)
            continue;

        memcpy(&decoded[idx_hash], &message, sizeof(message));
        decoded_hashtable[idx_hash] = &decoded[idx_hash];

        char text[FTX_MAX_MESSAGE_LENGTH];
        ftx_message_offsets_t offsets;
        if (ftx_message_decode(&message, &hash_if, text, &offsets) == FTX_MESSAGE_RC_OK)
        {
            if (num_decoded < max_texts)
            {
                strncpy(texts[num_decoded], text, FTX_MAX_MESSAGE_LENGTH - 1);
                texts[num_decoded][FTX_MAX_MESSAGE_LENGTH - 1] = '\0';
                num_decoded++;
            }
        }
    }

    // 呼号哈希表老化（跨时隙保留）
    hashtable_cleanup(10);

    return num_decoded;
}
