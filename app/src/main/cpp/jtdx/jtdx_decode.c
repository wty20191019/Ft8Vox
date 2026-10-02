#include "jtdx_decode.h"
#include "jtdx_ldpc.h"
#include "jtdx_sync.h"
#include "jtdx_metric.h"
#include "jtdx_downsample.h"
#include "jtdx_tables.h"

#include <ft8/encode.h>
#include <ft8/message.h>
#include <math.h>
#include <string.h>

// 单个 FT8 时隙的 JTDX 解码（ipass=1，无 AP）。

#define JTDX_PS2 200.0f // 下变频采样率
#define JTDX_SYNCMIN 1.5f

static void estimate_snr(const jtdx_metric_result_t* m, const uint8_t* payload, int* snr_out)
{
    uint8_t tones[79];
    ft8_encode(payload, tones);
    float xsnrtmp = 0.001f;
    for (int i = 1; i <= 79; ++i)
    {
        int tn = tones[i - 1];
        float xsig = m->s8[tn][i] * m->s8[tn][i];
        float sum = 0.0f;
        for (int kk = 0; kk < 8; ++kk)
        {
            sum += m->s8[kk][i] * m->s8[kk][i];
        }
        float xnoi = (sum - xsig) / 7.0f;
        if (xnoi < 0.01f)
        {
            xnoi = 0.01f;
        }
        float xsnr = (xnoi < xsig) ? (xsig / xnoi) : 1.01f;
        xsnrtmp += xsnr;
    }
    float xsnr = xsnrtmp / 79.0f - 1.0f;
    xsnr = 10.0f * log10f(xsnr) - 26.5f;
    if (xsnr > 7.0f)
    {
        xsnr += (xsnr - 7.0f) / 2.0f;
    }
    if (xsnr > 30.0f)
    {
        xsnr -= 1.0f;
        if (xsnr > 40.0f)
        {
            xsnr -= 1.0f;
        }
        if (xsnr > 49.0f)
        {
            xsnr = 49.0f;
        }
    }
    if (xsnr < -17.0f)
    {
        if (xsnr < -22.5f && xsnr > -23.5f)
        {
            xsnr = -22.5f;
        }
        float t = 1.0f + 1.4f / (23.0f + xsnr);
        xsnr = xsnr - t * t + 1.2f;
    }
    if (xsnr < -23.0f)
    {
        xsnr = -23.0f;
    }
    *snr_out = (int)lroundf(xsnr);
}

int jtdx_decode_slot(const float* dd8, int n, float fmin, float fmax,
                     jtdx_decode_result_t* out, int max_out)
{
    jtdx_tables_init();

    static float dd8buf[JTDX_NMAX];
    int ncopy = (n < JTDX_NMAX) ? n : JTDX_NMAX;
    if (ncopy < 0)
    {
        ncopy = 0;
    }
    memset(dd8buf, 0, sizeof(dd8buf));
    memcpy(dd8buf, dd8, (size_t)ncopy * sizeof(float));

    // 1) 候选生成
    static jtdx_candidate_t cands[JTDX_MAX_CAND];
    int ncand = 0;
    jtdx_sync8(dd8buf, fmin, fmax, JTDX_SYNCMIN, -10000.0f, -62, 62, 1, 100, cands, &ncand);

    // 2) 长 FFT 缓存
    static jtdx_downsample_ctx_t ctx;
    jtdx_downsample_prepare(&ctx, dd8buf);

    static kiss_fft_cpx cd0buf[4801];
    kiss_fft_cpx* cd0 = cd0buf + 800;

    int nout = 0;
    for (int ic = 0; ic < ncand; ++ic)
    {
        float f1 = cands[ic].freq;
        float xdt0 = cands[ic].xdt;
        int lcqcand = (cands[ic].redcq > 1.0f) ? 1 : 0;

        jtdx_downsample(&ctx, f1, cd0, 0);

        // 时间精调（±1/4 符号）
        int i0 = (int)lroundf((xdt0 + 0.5f) * JTDX_PS2);
        float smax = 0.0f;
        int ibest = i0;
        for (int idt = i0 - 8; idt <= i0 + 8; ++idt)
        {
            float sync = jtdx_sync8d(cd0, idt, NULL, 0, 1, 0, 1, lcqcand);
            if (sync > smax)
            {
                smax = sync;
                ibest = idt;
            }
        }
        float xdt2 = (float)ibest * 0.005f;

        // 频率精调（±2.5Hz）
        i0 = (int)lroundf(xdt2 * JTDX_PS2);
        smax = 0.0f;
        float delfbest = 0.0f;
        for (int ifr = -5; ifr <= 5; ++ifr)
        {
            float delf = (float)ifr * 0.5f;
            float sync = jtdx_sync8d(cd0, i0, jtdx_ctwkw[ifr + 5], 1, 1, 0, 1, lcqcand);
            if (sync > smax)
            {
                smax = sync;
                delfbest = delf;
            }
        }
        float a[5] = { -delfbest, 0.0f, 0.0f, 0.0f, 0.0f };
        jtdx_twkfreq1(cd0, -800, 3199, 4000, JTDX_PS2, a, cd0);
        float xdt = xdt2;
        f1 = f1 + delfbest;

        // 3) 符号谱 + 相干度量
        jtdx_metric_result_t m;
        jtdx_metric_compute(cd0, ibest, &m);
        if (m.syncavemax < 1.8f)
        {
            continue;
        }

        // 4) 三组度量依次 BP/OSD
        for (int isubp2 = 1; isubp2 <= 3; ++isubp2)
        {
            const float* llrz;
            if (isubp2 == 1)
            {
                llrz = m.llrd;
            }
            else if (isubp2 == 2)
            {
                llrz = m.llrb;
            }
            else
            {
                llrz = m.llrc;
            }

            uint8_t cw[174];
            int nhe = 0;
            int niter = 0;
            float dmin = 0.0f;
            jtdx_ldpc_bp_decode(llrz, NULL, JTDX_MAXIT, cw, &nhe, &niter);
            if (nhe < 0)
            {
                int nhm = 0;
                float dm = 0.0f;
                jtdx_ldpc_osd_decode(llrz, NULL, 3, cw, &nhm, &dm);
                nhe = nhm;
                dmin = dm;
            }

            int allzero = 1;
            for (int i = 0; i < 174; ++i)
            {
                if (cw[i])
                {
                    allzero = 0;
                    break;
                }
            }
            if (allzero)
            {
                continue;
            }
            if (nhe < 0 || nhe + dmin >= 60.0f || (isubp2 > 2 && nhe > 39))
            {
                continue;
            }

            uint8_t payload[10];
            memset(payload, 0, sizeof(payload));
            for (int i = 0; i < 77; ++i)
            {
                if (cw[i])
                {
                    payload[i / 8] |= (uint8_t)(0x80 >> (i % 8));
                }
            }
            ftx_message_t msg;
            memcpy(msg.payload, payload, sizeof(payload));
            char text[40];
            ftx_message_offsets_t offsets;
            if (ftx_message_decode(&msg, NULL, text, &offsets) != FTX_MESSAGE_RC_OK)
            {
                continue;
            }

            int dup = 0;
            for (int p = 0; p < nout; ++p)
            {
                if (strcmp(out[p].text, text) == 0)
                {
                    dup = 1;
                    break;
                }
            }
            if (dup)
            {
                break;
            }

            if (nout < max_out)
            {
                int snr = 0;
                estimate_snr(&m, payload, &snr);
                strncpy(out[nout].text, text, sizeof(out[nout].text) - 1);
                out[nout].text[sizeof(out[nout].text) - 1] = '\0';
                out[nout].snr = snr;
                out[nout].dt = xdt - 0.5f;
                out[nout].df = (int)lroundf(f1);
                nout++;
            }
            break;
        }
    }

    return nout;
}
