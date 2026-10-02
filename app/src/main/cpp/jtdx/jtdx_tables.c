#include "jtdx_tables.h"
#include "jtdx_fft.h"
#include "jtdx_wave.h"

#include <ft8/encode.h>
#include <ft8/message.h>
#include <math.h>
#include <string.h>

float jtdx_windowx[201];
float jtdx_facx;
float jtdx_windowc1[55];
float jtdx_facc1;

const int jtdx_icos7[7] = { 3, 1, 4, 0, 6, 5, 2 };
const int jtdx_graymap[8] = { 0, 1, 3, 2, 5, 6, 4, 7 };

kiss_fft_cpx jtdx_csync[7][32];
kiss_fft_cpx jtdx_csynccq[8][32];

kiss_fft_cpx jtdx_ctwkw[11][32];
kiss_fft_cpx jtdx_ctwkn[11][32];
kiss_fft_cpx jtdx_ctwk256[256];

static int g_tables_ready = 0;

// 用 ft8_lib 的编码器把 CQ 报文转成 79 个音调；失败则退回仅 Costas。
static void cq_tones(int* itone)
{
    ftx_message_t msg;
    uint8_t tones[JTDX_NN];
    ftx_message_rc_t rc = ftx_message_encode(&msg, NULL, "CQ 2E0DLA IO92");
    if (rc == FTX_MESSAGE_RC_OK)
    {
        ft8_encode(msg.payload, tones);
        for (int i = 0; i < JTDX_NN; ++i)
        {
            itone[i] = (int)tones[i];
        }
        return;
    }
    for (int i = 0; i < JTDX_NN; ++i)
    {
        itone[i] = 0;
    }
    for (int i = 0; i < 7; ++i)
    {
        itone[i] = jtdx_icos7[i];
        itone[36 + i] = jtdx_icos7[i];
        itone[72 + i] = jtdx_icos7[i];
    }
}

void jtdx_tables_init(void)
{
    if (g_tables_ready)
    {
        return;
    }

    const float twopi = 8.0f * atanf(1.0f);
    const float pivalue = 4.0f * atanf(1.0f);

    // windowc1(i)=(1+cos(i*pi/55))/2, i=0..54
    for (int i = 0; i < 55; ++i)
    {
        jtdx_windowc1[i] = (1.0f + cosf((float)i * pivalue / 55.0f)) / 2.0f;
    }
    // windowx(i)=(1+cos(i*pi/200))/2, i=0..200；再乘 facx=1/300
    jtdx_facx = 1.0f / 300.0f;
    for (int i = 0; i <= 200; ++i)
    {
        jtdx_windowx[i] = (1.0f + cosf((float)i * pivalue / 200.0f)) / 2.0f;
        jtdx_windowx[i] *= jtdx_facx;
    }
    jtdx_facc1 = 0.01f / sqrtf(61440.0f);

    // csync / csynccq：用 gen_ft8wave 生成整个 79 符号波形后按 (j,k) 采样
    int itone[JTDX_NN];
    cq_tones(itone);
    static kiss_fft_cpx csig0[151680];
    float xjunk[1];
    jtdx_gen_ft8wave(itone, JTDX_NN, JTDX_NSPS, 2.0f, 12000.0f, 0.0f, csig0, xjunk, 1, 151680);

    int m = 0; // 0-based：对应 Fortran m=1
    for (int j = 0; j <= 14; ++j)
    {
        for (int k = 1; k <= 32; ++k)
        {
            if (j < 7)
            {
                jtdx_csync[j][k - 1] = csig0[m];
            }
            else
            {
                jtdx_csynccq[j - 7][k - 1] = csig0[m];
            }
            m += 60;
        }
    }

    // ctwkw / ctwkn：频偏补偿的复旋转序列，dt2=1/200
    const float dt2 = 0.005f;
    for (int k = 0; k < 11; ++k)
    {
        float ifr = (float)(k - 5); // -5..5
        float dphi = twopi * (ifr * 0.5f) * dt2;
        float phi = 0.0f;
        for (int i = 0; i < 32; ++i)
        {
            jtdx_ctwkw[k][i] = jtdx_cpx(cosf(phi), sinf(phi));
            phi = fmodf(phi + dphi, twopi);
        }
    }
    for (int k = 0; k < 11; ++k)
    {
        float ifr = (float)(k - 5);
        float dphi = twopi * (ifr * 0.25f) * dt2;
        float phi = 0.0f;
        for (int i = 0; i < 32; ++i)
        {
            jtdx_ctwkn[k][i] = jtdx_cpx(cosf(phi), sinf(phi));
            phi = fmodf(phi + dphi, twopi);
        }
    }

    // ctwk256：delf=3.125Hz
    {
        float dphi = twopi * 3.125f * dt2;
        float phi = 0.0f;
        for (int i = 0; i < 256; ++i)
        {
            jtdx_ctwk256[i] = jtdx_cpx(cosf(phi), sinf(phi));
            phi = fmodf(phi + dphi, twopi);
        }
    }

    g_tables_ready = 1;
}
