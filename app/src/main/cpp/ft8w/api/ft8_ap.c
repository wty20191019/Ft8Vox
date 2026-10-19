/**
 * @file ft8_ap.c
 * @brief AP 提示构建与应用（移植 WSJT-X ft8apset.f90 + ft8b.f90 AP 分支）。
 */
#include "ft8_ap.h"

#include "ft8_hash.h"

#include <ft8/message.h>

#include <math.h>
#include <stdio.h>
#include <string.h>

static uint8_t payload_bit(const ftx_message_t* msg, int i)
{
    return (uint8_t)((msg->payload[i >> 3] >> (7 - (i & 7))) & 1);
}

static void hint_add(ft8_ap_hints_t* hints, int type, const ftx_message_t* msg,
                     int mask_len, const uint8_t* mask)
{
    if (!hints || hints->count >= FT8_AP_MAX_HINTS)
        return;
    ft8_ap_hint_t* h = &hints->hints[hints->count++];
    h->type = type;
    for (int i = 0; i < FT8_AP_PAYLOAD_BITS; ++i)
    {
        h->bits[i] = payload_bit(msg, i);
        h->mask[i] = (i < mask_len) ? mask[i] : 0;
    }
}

/* 编码 mycall + hiscall + extra；失败时尝试自由文本/非标准呼号路径。 */
static int encode_ap_message(ftx_message_t* msg, const char* my_call, const char* his_call,
                             const char* extra)
{
    ftx_message_init(msg);
    ftx_message_rc_t rc = ftx_message_encode_std(msg, &ft8_hash_if, my_call, his_call, extra);
    if (rc == FTX_MESSAGE_RC_OK)
        return 0;

    char text[64];
    snprintf(text, sizeof(text), "%s %s %s", my_call, his_call, extra);
    ftx_message_init(msg);
    rc = ftx_message_encode(msg, &ft8_hash_if, text);
    return (rc == FTX_MESSAGE_RC_OK) ? 0 : -1;
}

int ft8_ap_build(ft8_ap_hints_t* hints, const char* my_call, const char* his_call, int ap_mode)
{
    if (!hints)
        return 0;
    memset(hints, 0, sizeof(*hints));
    if (!my_call || !my_call[0] || ap_mode <= 0)
        return 0;

    const char* his = (his_call && his_call[0]) ? his_call : NULL;
    const char* dummy = "KA1ABC";
    const char* call_de = his ? his : dummy;

    /* 参考 ft8apset：用 mycall hiscall RRR 编码出一条类型 1/2 的 77bit 载荷，
     * 前 58 bit 就是两个呼号 + /R 标志。 */
    ftx_message_t msg;
    if (encode_ap_message(&msg, my_call, call_de, "RRR") != 0)
        return 0;

    const uint8_t i3 = ftx_message_get_i3(&msg);
    if (i3 != 1 && i3 != 2)
        return 0; /* AP 只覆盖标准类型 1/2；非标准呼号暂不做 AP */

    uint8_t mask[FT8_AP_PAYLOAD_BITS];
    memset(mask, 0, sizeof(mask));

    /* iaptype 2：MyCall ??? ???（29 + 3 = 32 bit） */
    if (ap_mode & 1)
    {
        memset(mask, 0, sizeof(mask));
        memset(mask, 1, 29);
        mask[74] = mask[75] = mask[76] = 1;
        hint_add(hints, 2, &msg, FT8_AP_PAYLOAD_BITS, mask);
    }

    /* iaptype 3：MyCall DxCall ???（58 + 3 = 61 bit） */
    if ((ap_mode & 2) && his)
    {
        memset(mask, 0, sizeof(mask));
        memset(mask, 1, 58);
        mask[74] = mask[75] = mask[76] = 1;
        hint_add(hints, 3, &msg, FT8_AP_PAYLOAD_BITS, mask);
    }

    /* iaptype 4/5/6：MyCall DxCall RRR/73/RR73（77 bit）
     * 全 77 bit 已知风险较高，依赖调用方的 nharderrors <= 36 过滤。 */
    if ((ap_mode & 4) && his)
    {
        static const char* extras[3] = {"RRR", "73", "RR73"};
        static const int types[3] = {4, 5, 6};
        for (int k = 0; k < 3; ++k)
        {
            ftx_message_t m;
            if (encode_ap_message(&m, my_call, his, extras[k]) != 0)
                continue;
            const uint8_t t3 = ftx_message_get_i3(&m);
            if (t3 != 1 && t3 != 2)
                continue;
            memset(mask, 1, sizeof(mask));
            hint_add(hints, types[k], &m, FT8_AP_PAYLOAD_BITS, mask);
        }
    }

    return hints->count;
}

float ft8_ap_apply(const ft8_ap_hint_t* hint, float* log174, uint8_t* apmask174)
{
    if (!hint || !log174 || !apmask174)
        return 0.0f;

    float max_abs = 0.0f;
    for (int i = 0; i < 174; ++i)
    {
        const float a = fabsf(log174[i]);
        if (a > max_abs)
            max_abs = a;
    }
    float apmag = max_abs * 1.1f;
    if (apmag < 1.0f)
        apmag = 16.0f;

    memset(apmask174, 0, 174);
    for (int i = 0; i < FT8_AP_PAYLOAD_BITS; ++i)
    {
        if (!hint->mask[i])
            continue;
        log174[i] = hint->bits[i] ? apmag : -apmag;
        apmask174[i] = 1;
    }
    return apmag;
}
