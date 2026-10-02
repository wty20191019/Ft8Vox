#include "jtdx_metric.h"
#include "jtdx_fft.h"
#include "jtdx_tables.h"

#include <math.h>
#include <string.h>

// sp 归一化的最小值下限
static void normalizebmet(float* bmet, int n)
{
    float sum = 0.0f;
    for (int i = 0; i < n; ++i)
    {
        sum += bmet[i] * bmet[i];
    }
    float sig = sqrtf(sum / (float)n);
    if (sig > 0.0f)
    {
        for (int i = 0; i < n; ++i)
        {
            bmet[i] /= sig;
        }
    }
}

void jtdx_metric_compute(const kiss_fft_cpx* cd0, int ibest, jtdx_metric_result_t* out)
{
    jtdx_tables_init();

    // 频率幅值/相位谱：cs[i][k], csr[i][k]，i=0..7（8 个音调 bin），k=1..79（符号）
    kiss_fft_cpx cs[8][80];
    kiss_fft_cpx csr[8][80];
    float s8[8][80];
    memset(cs, 0, sizeof(cs));
    memset(csr, 0, sizeof(csr));
    memset(s8, 0, sizeof(s8));

    // ---- Costas 各符号 SNR（snrsync -> syncav / syncavemax）----------------
    float snrsync[22];
    memset(snrsync, 0, sizeof(snrsync));
    for (int k = 1; k <= 79; ++k)
    {
        int is_costas = (k >= 1 && k <= 7) || (k >= 37 && k <= 43) || (k >= 73 && k <= 79);
        if (!is_costas)
        {
            continue;
        }
        int i1 = ibest + (k - 1) * 32;
        kiss_fft_cpx csymb[32];
        for (int t = 0; t < 32; ++t)
        {
            csymb[t] = cd0[i1 + t];
        }
        jtdx_fft_c2c_fwd(csymb, 32);
        float s81[8];
        float sum = 0.0f;
        for (int i = 0; i < 8; ++i)
        {
            s81[i] = jtdx_cabs(csymb[i]);
            sum += s81[i];
        }
        int row;
        if (k >= 1 && k <= 7)
        {
            row = k - 1;
            float synclev = s81[jtdx_icos7[row]];
            float snoiselev = (sum - synclev) / 7.0f;
            if (snoiselev > 1e-16f)
            {
                snrsync[k] = synclev / snoiselev;
            }
        }
        else if (k >= 37 && k <= 43)
        {
            row = k - 37;
            float synclev = s81[jtdx_icos7[row]];
            float snoiselev = (sum - synclev) / 7.0f;
            if (snoiselev > 1e-16f)
            {
                snrsync[k - 29] = synclev / snoiselev;
            }
        }
        else
        {
            row = k - 73;
            float synclev = s81[jtdx_icos7[row]];
            float snoiselev = (sum - synclev) / 7.0f;
            if (snoiselev > 1e-16f)
            {
                snrsync[k - 58] = synclev / snoiselev;
            }
        }
    }
    float syncav = 0.0f;
    for (int i = 1; i <= 21; ++i)
    {
        syncav += snrsync[i];
    }
    syncav /= 21.0f;
    float part[3];
    part[0] = (snrsync[1] + snrsync[2] + snrsync[3] + snrsync[4] + snrsync[5] + snrsync[6] + snrsync[7]) / 7.0f;
    part[1] = (snrsync[8] + snrsync[9] + snrsync[10] + snrsync[11] + snrsync[12] + snrsync[13] + snrsync[14]) / 7.0f;
    part[2] = (snrsync[15] + snrsync[16] + snrsync[17] + snrsync[18] + snrsync[19] + snrsync[20] + snrsync[21]) / 7.0f;
    float syncavemax = part[0];
    if (part[1] > syncavemax)
    {
        syncavemax = part[1];
    }
    if (part[2] > syncavemax)
    {
        syncavemax = part[2];
    }

    // ---- 79 符号谱提取 ------------------------------------------------------
    for (int k = 1; k <= 79; ++k)
    {
        int i1 = ibest + (k - 1) * 32;
        kiss_fft_cpx csymb[32];
        for (int t = 0; t < 32; ++t)
        {
            csymb[t] = cd0[i1 + t];
        }
        if (syncav < 2.5f)
        {
            csymb[0] = jtdx_cmul_f(csymb[0], 1.9f);
            csymb[31] = jtdx_cmul_f(csymb[31], 1.9f);
            float scr = sqrtf(jtdx_cabs(csymb[0])) / sqrtf(jtdx_cabs(csymb[31]));
            if (scr > 1.0f)
            {
                csymb[31] = jtdx_cmul_f(csymb[31], scr);
            }
            else if (scr > 1e-16f)
            {
                csymb[0] = jtdx_cmul_f(csymb[0], 1.0f / scr);
            }
        }
        kiss_fft_cpx csymbr[32];
        for (int i = 0; i < 32; ++i)
        {
            csymbr[i] = jtdx_cconj(csymb[31 - i]);
        }
        jtdx_fft_c2c_fwd(csymb, 32);
        for (int i = 0; i < 8; ++i)
        {
            cs[i][k] = jtdx_cmul_f(csymb[i], 1e-3f);
            s8[i][k] = jtdx_cabs(csymb[i]);
        }
        jtdx_fft_c2c_fwd(csymbr, 32);
        for (int i = 0; i < 8; ++i)
        {
            csr[i][k] = jtdx_cmul_f(csymbr[i], 1e-3f);
        }
    }

    // ---- sp 归一化 ----------------------------------------------------------
    float sp[8];
    for (int k = 0; k < 8; ++k)
    {
        float acc = 0.0f;
        for (int kk = 1; kk <= 7; ++kk)
        {
            acc += s8[k][kk];
        }
        for (int kk = 18; kk <= 79; ++kk)
        {
            acc += s8[k][kk];
        }
        sp[k] = acc;
    }
    int ka = 0;
    for (int k = 1; k < 8; ++k)
    {
        if (sp[k] < sp[ka])
        {
            ka = k;
        }
    }
    if (sp[ka] > 0.0f)
    {
        for (int kb = 0; kb < 8; ++kb)
        {
            if (kb == ka)
            {
                continue;
            }
            float spr = sp[kb] / sp[ka];
            if (spr > 1.5f)
            {
                for (int kk = 1; kk <= 79; ++kk)
                {
                    s8[kb][kk] /= spr;
                }
                float sprsqr = sqrtf(spr);
                for (int kk = 1; kk <= 79; ++kk)
                {
                    cs[kb][kk] = jtdx_cmul_f(cs[kb][kk], 1.0f / sprsqr);
                    csr[kb][kk] = jtdx_cmul_f(csr[kb][kk], 1.0f / sprsqr);
                }
            }
        }
    }

    // ---- srr（Costas 段落信噪比）-------------------------------------------
    float synclev = 0.0f;
    for (int k = 1; k <= 7; ++k)
    {
        synclev += s8[jtdx_icos7[k - 1]][k + 36];
    }
    float total = 0.0f;
    for (int i = 0; i < 8; ++i)
    {
        for (int k = 37; k <= 43; ++k)
        {
            total += s8[i][k];
        }
    }
    float snoiselev = (total - synclev) / 7.0f;
    if (snoiselev < 0.1f)
    {
        snoiselev = 1.0f;
    }
    float srr = synclev / snoiselev;

    // ---- 相干度量 -----------------------------------------------------------
    float bmeta[174], bmetb[174], bmetc[174], bmetd[174];
    memset(bmeta, 0, sizeof(bmeta));
    memset(bmetb, 0, sizeof(bmetb));
    memset(bmetc, 0, sizeof(bmetc));
    memset(bmetd, 0, sizeof(bmetd));

    float s2[512];
    for (int nsym = 1; nsym <= 3; ++nsym)
    {
        int nt = (1 << (3 * nsym)) - 1;
        for (int ihalf = 1; ihalf <= 2; ++ihalf)
        {
            for (int k = 1; k <= 29; k += nsym)
            {
                int ks = (ihalf == 1) ? (k + 7) : (k + 43);
                int ks1 = ks + 1;
                int ks2 = ks + 2;
                for (int i = 0; i <= nt; ++i)
                {
                    int i1 = i / 64;
                    int i2 = (i & 63) / 8;
                    int i33 = i & 7;
                    float mag;
                    if (nsym == 1)
                    {
                        mag = jtdx_cabs(cs[jtdx_graymap[i33]][ks]);
                    }
                    else if (nsym == 2)
                    {
                        kiss_fft_cpx v = jtdx_cadd(cs[jtdx_graymap[i2]][ks], cs[jtdx_graymap[i33]][ks1]);
                        mag = jtdx_cabs(v);
                    }
                    else
                    {
                        kiss_fft_cpx v = jtdx_cadd(jtdx_cadd(cs[jtdx_graymap[i1]][ks], cs[jtdx_graymap[i2]][ks1]),
                                                  cs[jtdx_graymap[i33]][ks2]);
                        mag = jtdx_cabs(v);
                    }
                    s2[i] = mag;
                    if (srr < 2.5f)
                    {
                        if (srr > 2.3f)
                        {
                            s2[i] = s2[i] * s2[i];
                        }
                        else
                        {
                            float ss1 = s2[i];
                            if (ss1 < 5.77f)
                            {
                                float ss2 = ss1 * ss1;
                                s2[i] = 1.0f + 8.0f * ss2 - 0.12f * ss2 * ss2;
                            }
                            else
                            {
                                s2[i] = (ss1 + 5.82f) * (ss1 + 5.82f);
                            }
                        }
                    }
                }

                int i32 = 1 + (k - 1) * 3 + (ihalf - 1) * 87;
                int ibmax = (nsym == 1) ? 2 : (nsym == 2) ? 5 : 8;
                for (int ib = 0; ib <= ibmax; ++ib)
                {
                    int bit = ibmax - ib;
                    float mx1 = -1e30f;
                    float mx0 = -1e30f;
                    for (int i = 0; i <= nt; ++i)
                    {
                        if ((i >> bit) & 1)
                        {
                            if (s2[i] > mx1)
                            {
                                mx1 = s2[i];
                            }
                        }
                        else
                        {
                            if (s2[i] > mx0)
                            {
                                mx0 = s2[i];
                            }
                        }
                    }
                    float bm = mx1 - mx0;
                    if (i32 + ib > 174)
                    {
                        continue;
                    }
                    if (nsym == 1)
                    {
                        bmeta[i32 + ib - 1] = bm;
                        float den = (mx1 > mx0) ? mx1 : mx0;
                        bmetd[i32 + ib - 1] = (den > 0.0f) ? (bm / den) : 0.0f;
                    }
                    else if (nsym == 2)
                    {
                        bmetb[i32 + ib - 1] = bm;
                    }
                    else
                    {
                        bmetc[i32 + ib - 1] = bm;
                    }
                }
            }
        }
    }

    normalizebmet(bmeta, 174);
    normalizebmet(bmetb, 174);
    normalizebmet(bmetc, 174);
    normalizebmet(bmetd, 174);
    const float scalefac = 2.83f;
    for (int i = 0; i < 174; ++i)
    {
        out->llra[i] = scalefac * bmeta[i];
        out->llrb[i] = scalefac * bmetb[i];
        out->llrc[i] = scalefac * bmetc[i];
        out->llrd[i] = scalefac * bmetd[i];
    }
    out->syncav = syncav;
    out->syncavemax = syncavemax;
    out->srr = srr;
    memcpy(out->s8, s8, sizeof(s8));
}
