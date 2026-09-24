#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>

#include <common/monitor.h>
#include <common/common.h>
#include <ft8/decode.h>
#include <ft8/message.h>
#include <ft8/constants.h>

// -----------------------------------------------------------------------------
// 解码参数（与 ft8_lib 官方示例 demo/decode_ft8.c 保持一致）
// -----------------------------------------------------------------------------
#define K_MIN_SCORE            10
#define K_MAX_CANDIDATES       140
#define K_LDPC_ITERATIONS      25
#define K_MAX_DECODED_MESSAGES 50

// -----------------------------------------------------------------------------
// 呼号哈希表：用于 ftx_message_decode() 还原被哈希压缩的呼号
// 注意：ftx_callsign_hash_interface_t 的回调没有上下文参数，因此这里使用文件级
// 全局表。当前假定单引擎实例；若将来需要多实例，需重构为可传上下文的形式。
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
// 引擎对象：Kotlin 侧持有一个 jlong 句柄指向它
// -----------------------------------------------------------------------------
typedef struct
{
    monitor_t mon;
} ft8_engine_t;

// -----------------------------------------------------------------------------
// 生命周期
// -----------------------------------------------------------------------------
JNIEXPORT jlong JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_nativeInit(
    JNIEnv* env, jobject thiz,
    jint protocol, jint sample_rate, jfloat f_min, jfloat f_max,
    jint time_osr, jint freq_osr)
{
    ft8_engine_t* eng = (ft8_engine_t*)calloc(1, sizeof(ft8_engine_t));
    if (eng == NULL)
        return 0;

    monitor_config_t cfg = {
        .f_min = f_min,
        .f_max = f_max,
        .sample_rate = sample_rate,
        .time_osr = time_osr,
        .freq_osr = freq_osr,
        // Kotlin 枚举 Protocol：0=FT8, 1=FT4
        .protocol = (protocol == 1) ? FTX_PROTOCOL_FT4 : FTX_PROTOCOL_FT8,
    };
    monitor_init(&eng->mon, &cfg);
    hashtable_init();

    return (jlong)(intptr_t)eng;
}

JNIEXPORT void JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_nativeReset(JNIEnv* env, jobject thiz, jlong handle)
{
    ft8_engine_t* eng = (ft8_engine_t*)(intptr_t)handle;
    if (eng == NULL)
        return;
    monitor_reset(&eng->mon);
}

JNIEXPORT void JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_nativeRelease(JNIEnv* env, jobject thiz, jlong handle)
{
    ft8_engine_t* eng = (ft8_engine_t*)(intptr_t)handle;
    if (eng == NULL)
        return;
    monitor_free(&eng->mon);
    free(eng);
}

// -----------------------------------------------------------------------------
// 解码输入：喂入一块 12 kHz 单声道 PCM（float，[-1,1]）
// 内部按 monitor 的 block_size 逐块调用 monitor_process 累积 waterfall。
// -----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_nativeProcess(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray samples, jint length)
{
    ft8_engine_t* eng = (ft8_engine_t*)(intptr_t)handle;
    if (eng == NULL || samples == NULL)
        return;

    jsize array_len = (*env)->GetArrayLength(env, samples);
    if (length <= 0 || length > array_len)
        length = array_len;

    jboolean is_copy = JNI_FALSE;
    jfloat* data = (*env)->GetFloatArrayElements(env, samples, &is_copy);
    if (data == NULL)
        return;

    monitor_t* mon = &eng->mon;
    for (int pos = 0; pos + mon->block_size <= length; pos += mon->block_size)
    {
        monitor_process(mon, data + pos);
    }

    (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);
}

// -----------------------------------------------------------------------------
// 解码：对当前累积的 waterfall 做候选查找与解码，返回报文明文数组
// -----------------------------------------------------------------------------
JNIEXPORT jobjectArray JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_nativeDecode(JNIEnv* env, jobject thiz, jlong handle)
{
    jclass str_cls = (*env)->FindClass(env, "java/lang/String");
    ft8_engine_t* eng = (ft8_engine_t*)(intptr_t)handle;
    if (eng == NULL || str_cls == NULL)
        return (*env)->NewObjectArray(env, 0, str_cls, NULL);

    const ftx_waterfall_t* wf = &eng->mon.wf;

    // 按 Costas 同步得分定位候选
    ftx_candidate_t candidates[K_MAX_CANDIDATES];
    int num_candidates = ftx_find_candidates(wf, K_MAX_CANDIDATES, candidates, K_MIN_SCORE);

    char texts[K_MAX_DECODED_MESSAGES][FTX_MAX_MESSAGE_LENGTH];
    int num_decoded = 0;

    // 本时隙已解码消息的哈希表，用于去重
    ftx_message_t decoded[K_MAX_DECODED_MESSAGES];
    ftx_message_t* decoded_hashtable[K_MAX_DECODED_MESSAGES];
    for (int i = 0; i < K_MAX_DECODED_MESSAGES; ++i)
        decoded_hashtable[i] = NULL;

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
            if (num_decoded < K_MAX_DECODED_MESSAGES)
            {
                strncpy(texts[num_decoded], text, FTX_MAX_MESSAGE_LENGTH - 1);
                texts[num_decoded][FTX_MAX_MESSAGE_LENGTH - 1] = '\0';
                num_decoded++;
            }
        }
    }

    // 呼号哈希表老化（跨时隙保留）
    hashtable_cleanup(10);

    jobjectArray result = (*env)->NewObjectArray(env, num_decoded, str_cls, NULL);
    for (int i = 0; i < num_decoded; ++i)
    {
        jstring s = (*env)->NewStringUTF(env, texts[i]);
        if (s != NULL)
        {
            (*env)->SetObjectArrayElement(env, result, i, s);
            (*env)->DeleteLocalRef(env, s);
        }
    }
    return result;
}

// -----------------------------------------------------------------------------
// 调试：验证 JNI 与 libft8.so 的加载链路
// -----------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_example_ft8vox_engine_Ft8Engine_test(JNIEnv* env, jobject thiz)
{
    return (*env)->NewStringUTF(env, "FT8 JNI ready");
}
