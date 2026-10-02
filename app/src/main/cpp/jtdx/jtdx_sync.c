#include "jtdx_sync.h"
#include "jtdx_fft.h"
#include "jtdx_tables.h"

#include <math.h>
#include <string.h>

#define JTDX_DF 3.125f
#define JTDX_TSTEP 0.04f
#define JTDX_JCOLS 200
#define JTDX_JOFF 96

// 符号谱：s[k][i]，k=1..NHSYM（时间），i=1..NH1（频率）
static float s_sync[JTDX_NHSYM + 1][JTDX_NH1 + 32];
static float sync2d_sync[JTDX_NH1 + 1][JTDX_JCOLS];
static unsigned char syncq_sync[JTDX_NH1 + 1][JTDX_JCOLS];
static float red_sync[JTDX_NH1 + 1];
static unsigned char redcq_sync[JTDX_NH1 + 1];
static int jpeak_sync[JTDX_NH1 + 1];
static int indx_sync[JTDX_NH1 + 1];
static float cand0[6][451]; // 1-based 列

void jtdx_indexx(const float* arr, int n, int* indx)
{
    const int M = 7;
    int istack[60];
    for (int j = 1; j <= n; ++j)
    {
        indx[j] = j;
    }
    int jstack = 0;
    int l = 1;
    int ir = n;
    for (;;)
    {
        if (ir - l < M)
        {
            for (int j = l + 1; j <= ir; ++j)
            {
                int indxt = indx[j];
                float a = arr[indxt - 1];
                int i;
                for (i = j - 1; i >= 1; --i)
                {
                    if (arr[indx[i] - 1] <= a)
                    {
                        break;
                    }
                    indx[i + 1] = indx[i];
                }
                indx[i + 1] = indxt;
            }
            if (jstack == 0)
            {
                return;
            }
            ir = istack[jstack];
            l = istack[jstack - 1];
            jstack -= 2;
        }
        else
        {
            int k = (l + ir) / 2;
            int itemp = indx[k];
            indx[k] = indx[l + 1];
            indx[l + 1] = itemp;

            if (arr[indx[l + 1] - 1] > arr[indx[ir] - 1])
            {
                itemp = indx[l + 1];
                indx[l + 1] = indx[ir];
                indx[ir] = itemp;
            }
            if (arr[indx[l] - 1] > arr[indx[ir] - 1])
            {
                itemp = indx[l];
                indx[l] = indx[ir];
                indx[ir] = itemp;
            }
            if (arr[indx[l + 1] - 1] > arr[indx[l] - 1])
            {
                itemp = indx[l + 1];
                indx[l + 1] = indx[l];
                indx[l] = itemp;
            }

            int i = l + 1;
            int j = ir;
            int indxt = indx[l];
            float a = arr[indxt - 1];
            for (;;)
            {
                do
                {
                    i++;
                } while (arr[indx[i] - 1] < a);
                do
                {
                    j--;
                } while (arr[indx[j] - 1] > a);
                if (j < i)
                {
                    break;
                }
                itemp = indx[i];
                indx[i] = indx[j];
                indx[j] = itemp;
            }
            indx[l] = indx[j];
            indx[j] = indxt;
            jstack += 2;
            if (ir - i + 1 >= j - l)
            {
                istack[jstack] = ir;
                istack[jstack - 1] = i;
                ir = j - 1;
            }
            else
            {
                istack[jstack] = j - 1;
                istack[jstack - 1] = l;
                l = i;
            }
        }
    }
}

void jtdx_sync8(const float* dd8, float nfa, float nfb, float syncmin, float nfqso,
                int jzb, int jzt, int ipass, int ncandthin,
                jtdx_candidate_t* out, int* out_count)
{
    jtdx_tables_init();

    int ia = (int)lroundf(nfa / JTDX_DF);
    if (ia < 1)
    {
        ia = 1;
    }
    int ib = (int)lroundf(nfb / JTDX_DF);
    if (ib < 1)
    {
        ib = 1;
    }
    int iaw = ia;
    int ibw = ib;
    if (ibw > JTDX_NH1 - 17)
    {
        ibw = JTDX_NH1 - 17;
    }

    const int nssy = 4;
    const int nssy36 = 144;
    const int nssy72 = 288;
    const int nfos = 2;
    const int jstrt = 12; // 12.5 截断为整数（隐式整型）
    const float tstep = JTDX_TSTEP;

    // ---- 符号谱 FFT（ipass=1：幅度）-----------------------------------------
    static float x[JTDX_NFFT1];
    static kiss_fft_cpx cx[JTDX_NH1 + 1];
    memset(syncq_sync, 0, sizeof(syncq_sync));
    for (int j = 1; j <= JTDX_NHSYM; ++j)
    {
        int ia0 = (j - 1) * JTDX_NSTEP; // 0-based 符号起点
        memset(x, 0, sizeof(x));
        if (j != 1)
        {
            for (int t = 0; t < 201; ++t)
            {
                int src = ia0 - 201 + t;
                float v = (src >= 0 && src < JTDX_NMAX) ? dd8[src] : 0.0f;
                x[759 + t] = v * jtdx_windowx[200 - t];
            }
        }
        for (int t = 0; t < JTDX_NSPS; ++t)
        {
            int src = ia0 + t;
            float v = (src >= 0 && src < JTDX_NMAX) ? dd8[src] : 0.0f;
            x[960 + t] = v * jtdx_facx;
        }
        x[960] *= 1.9f;
        x[2879] *= 1.9f;
        if (j != JTDX_NHSYM)
        {
            int ib0 = ia0 + JTDX_NSPS - 1;
            for (int t = 0; t < 201; ++t)
            {
                int src = ib0 + 1 + t;
                float v = (src >= 0 && src < JTDX_NMAX) ? dd8[src] : 0.0f;
                x[2880 + t] = v * jtdx_windowx[t];
            }
        }
        jtdx_fft_r2c(x, JTDX_NFFT1, cx);
        for (int i = 1; i <= JTDX_NH1; ++i)
        {
            float re = cx[i].r;
            float im = cx[i].i;
            s_sync[j][i] = (ipass == 1) ? sqrtf(re * re + im * im) : (re * re + im * im);
        }
    }

    // ---- Costas 同步评分（非 lagcc 分支）------------------------------------
    for (int j = jzb; j <= jzt; ++j)
    {
        for (int i = iaw; i <= ibw; ++i)
        {
            float ta = 0, tb = 0, tc = 0, tcq = 0;
            float t0a = 0, t0b = 0, t0c = 0, t0cq = 0;
            for (int n = 0; n <= 6; ++n)
            {
                int k = j + jstrt + nssy * n;
                if (k > 0 && k <= JTDX_NHSYM)
                {
                    int idx = i + nfos * jtdx_icos7[n];
                    ta += s_sync[k][idx];
                    float sum = 0;
                    for (int t = 0; t <= 16; ++t)
                    {
                        sum += s_sync[k][i + t];
                    }
                    t0a += sum - s_sync[k][idx + 1];
                }
                int k36 = k + nssy36;
                if (k36 > 0 && k36 <= JTDX_NHSYM)
                {
                    int idx = i + nfos * jtdx_icos7[n];
                    tb += s_sync[k36][idx];
                    float sum = 0;
                    for (int t = 0; t <= 16; ++t)
                    {
                        sum += s_sync[k36][i + t];
                    }
                    t0b += sum - s_sync[k36][idx + 1];
                }
                int k72 = k + nssy72;
                if (k72 > 0 && k72 <= JTDX_NHSYM)
                {
                    int idx = i + nfos * jtdx_icos7[n];
                    tc += s_sync[k72][idx];
                    float sum = 0;
                    for (int t = 0; t <= 16; ++t)
                    {
                        sum += s_sync[k72][i + t];
                    }
                    t0c += sum - s_sync[k72][idx + 1];
                }
            }
            for (int n = 7; n <= 15; ++n)
            {
                int k = j + jstrt + nssy * n;
                if (k >= 1 && k <= JTDX_NHSYM)
                {
                    float sum = 0;
                    for (int t = 0; t <= 16; ++t)
                    {
                        sum += s_sync[k][i + t];
                    }
                    if (n < 15)
                    {
                        tcq += s_sync[k][i];
                        t0cq += sum - s_sync[k][i + 1];
                    }
                    else
                    {
                        tcq += s_sync[k][i + 2];
                        t0cq += sum - s_sync[k][i + 3];
                    }
                }
            }

            float t1 = ta + tb + tc;
            float t01 = t0a + t0b + t0c;
            float t2 = t1 + tcq;
            float t02 = t01 + t0cq;
            t01 = (t01 - t1 * 2.0f) / 42.0f;
            if (t01 < 1e-8f)
            {
                t01 = 1.0f;
            }
            t02 = (t02 - t2 * 2.0f) / 60.0f;
            if (t02 < 1e-8f)
            {
                t02 = 1.0f;
            }
            float sync01 = t1 / (7.0f * t01);
            float sync02 = (t1 / 7.0f + tcq / 9.0f) / t02;
            float syncf = sync01 > sync02 ? sync01 : sync02;
            int lcq = (sync02 > sync01);

            t1 = tb + tc;
            t01 = t0b + t0c;
            t2 = t1 + tcq;
            t02 = t01 + t0cq;
            t01 = (t01 - t1 * 2.0f) / 28.0f;
            if (t01 < 1e-8f)
            {
                t01 = 1.0f;
            }
            t02 = (t02 - t2 * 2.0f) / 46.0f;
            if (t02 < 1e-8f)
            {
                t02 = 1.0f;
            }
            sync01 = t1 / (7.0f * t01);
            sync02 = (t1 / 7.0f + tcq / 9.0f) / t02;
            float syncs = sync01 > sync02 ? sync01 : sync02;
            int lcq2 = (sync02 > sync01);

            int col = j + JTDX_JOFF;
            float smax = syncf > syncs ? syncf : syncs;
            sync2d_sync[i][col] = smax;
            if (syncf > syncs)
            {
                if (lcq)
                {
                    syncq_sync[i][col] = 1;
                }
            }
            else
            {
                if (lcq2)
                {
                    syncq_sync[i][col] = 1;
                }
            }
        }
    }

    // ---- 每个频率取最佳时间，构造 red ---------------------------------------
    memset(red_sync, 0, sizeof(red_sync));
    memset(redcq_sync, 0, sizeof(redcq_sync));
    for (int i = iaw; i <= ibw; ++i)
    {
        int j0 = jzb;
        float best = sync2d_sync[i][jzb + JTDX_JOFF];
        for (int j = jzb + 1; j <= jzt; ++j)
        {
            float v = sync2d_sync[i][j + JTDX_JOFF];
            if (v > best)
            {
                best = v;
                j0 = j;
            }
        }
        jpeak_sync[i] = j0;
        red_sync[i] = best;
        if (syncq_sync[i][j0 + JTDX_JOFF])
        {
            redcq_sync[i] = 1;
        }
    }

    int iz = ibw - iaw + 1;
    jtdx_indexx(&red_sync[iaw], iz, indx_sync);
    int ibase = indx_sync[(int)lroundf(0.40f * (float)iz)] - 1 + iaw;
    if (ibase < 1)
    {
        ibase = 1;
    }
    float base = red_sync[ibase];
    if (base < 1e-8f)
    {
        base = 1.0f;
    }
    for (int i = 1; i <= JTDX_NH1; ++i)
    {
        red_sync[i] /= base;
    }

    // ---- 候选提取 -----------------------------------------------------------
    for (int i = 0; i < 6; ++i)
    {
        memset(cand0[i], 0, sizeof(cand0[i]));
    }
    int k = 0;
    iz = ib - ia + 1;
    if (iz > JTDX_NH1)
    {
        iz = JTDX_NH1;
    }
    jtdx_indexx(&red_sync[ia], iz, indx_sync);
    float rcandthin = (float)ncandthin / 100.0f;
    for (int i = 1; i <= iz; ++i)
    {
        int n = ia + indx_sync[iz + 1 - i] - 1;
        float freq = (float)n * JTDX_DF;
        if (fabsf(freq - nfqso) > 3.0f)
        {
            if (red_sync[n] < syncmin)
            {
                continue;
            }
        }
        else
        {
            if (red_sync[n] < 1.1f)
            {
                continue;
            }
        }
        // 非 swl：jpeak 限幅 -49..76
        if (jpeak_sync[n] < -49 || jpeak_sync[n] > 76)
        {
            continue;
        }
        if (k < 450)
        {
            k++;
        }
        else
        {
            break;
        }
        cand0[1][k] = freq;
        cand0[2][k] = ((float)jpeak_sync[n] - 1.0f) * tstep;
        cand0[3][k] = red_sync[n];
        if (rcandthin < 0.99f)
        {
            // 抽稀选项（本移植默认不启用）
            cand0[5][k] = cand0[3][k];
        }
        if (redcq_sync[n])
        {
            cand0[4][k] = 2.0f;
        }
    }
    int ncand = k;

    // ---- 近频去重 -----------------------------------------------------------
    float fdif0 = 4.0f;
    for (int i = 1; i <= ncand; ++i)
    {
        if (i >= 2)
        {
            for (int j = 1; j <= i - 1; ++j)
            {
                float fdiff = fabsf(cand0[1][i] - cand0[1][j]);
                float xdtdelta = fabsf(cand0[2][i] - cand0[2][j]);
                if (fdiff < fdif0 && fabsf(cand0[1][i] - nfqso) > 3.0f)
                {
                    if (xdtdelta < 0.1f)
                    {
                        if (cand0[3][i] >= cand0[3][j])
                        {
                            cand0[3][j] = 0.0f;
                        }
                        if (cand0[3][i] < cand0[3][j])
                        {
                            cand0[3][i] = 0.0f;
                        }
                    }
                }
            }
        }
    }

    // ---- 排序（rcandthin>0.99 按同步值）-------------------------------------
    if (ncand > 0)
    {
        jtdx_indexx(&cand0[3][1], ncand, indx_sync);
    }

    // ---- 组装输出（nfqso 置顶 + 主列表）------------------------------------
    int outk = 0;
    float fprev = 5004.0f;
    for (int i = ncand; i >= 1; --i)
    {
        int j = indx_sync[i];
        if (fabsf(cand0[1][j] - nfqso) <= 3.0f && cand0[3][j] >= 1.1f &&
            fabsf(cand0[1][j] - fprev) > 3.0f)
        {
            out[outk].freq = cand0[1][j];
            out[outk].xdt = cand0[2][j];
            out[outk].sync = cand0[3][j];
            out[outk].redcq = cand0[4][j];
            outk++;
            fprev = cand0[1][j];
        }
    }
    for (int i = ncand; i >= 1; --i)
    {
        int j = indx_sync[i];
        float syncmin1 = (fabsf(cand0[1][j] - nfqso) > 3.0f) ? syncmin : 1.1f;
        if (cand0[3][j] >= syncmin1)
        {
            out[outk].freq = cand0[1][j];
            out[outk].xdt = cand0[2][j];
            out[outk].sync = cand0[3][j];
            out[outk].redcq = cand0[4][j];
            outk++;
            if (outk > JTDX_MAX_CAND - 1)
            {
                break;
            }
        }
    }
    *out_count = outk;
}

float jtdx_sync8d(const kiss_fft_cpx* cd0, int i0, const kiss_fft_cpx* ctwk, int itwk,
                  int ipass, int lastsync, int iqso, int lcqcand)
{
    float sync = 0.0f;
    kiss_fft_cpx csync2[32];
    kiss_fft_cpx zt1[7], zt2[7], zt3[7];

    for (int i = 0; i <= 6; ++i)
    {
        int i1 = i0 + i * 32;
        int i2 = i1 + 1152;
        int i3 = i1 + 2304;
        for (int t = 0; t < 32; ++t)
        {
            csync2[t] = jtdx_csync[i][t];
            if (itwk == 1 && ctwk)
            {
                csync2[t] = jtdx_cmul(ctwk[t], csync2[t]);
            }
        }
        kiss_fft_cpx s1 = jtdx_cpx(0, 0), s2 = jtdx_cpx(0, 0), s3 = jtdx_cpx(0, 0);
        for (int t = 0; t < 32; ++t)
        {
            kiss_fft_cpx conj = jtdx_cconj(csync2[t]);
            s1 = jtdx_cadd(s1, jtdx_cmul(cd0[i1 + t], conj));
            s2 = jtdx_cadd(s2, jtdx_cmul(cd0[i2 + t], conj));
            s3 = jtdx_cadd(s3, jtdx_cmul(cd0[i3 + t], conj));
        }
        zt1[i] = s1;
        zt2[i] = s2;
        zt3[i] = s3;
    }

    if (!lastsync)
    {
        for (int i = 0; i <= 6; ++i)
        {
            sync += jtdx_cabs(zt1[i]) + jtdx_cabs(zt2[i]) + jtdx_cabs(zt3[i]);
        }
    }
    else
    {
        for (int i = 0; i <= 6; ++i)
        {
            sync += zt1[i].r * zt1[i].r + zt1[i].i * zt1[i].i;
            sync += zt2[i].r * zt2[i].r + zt2[i].i * zt2[i].i;
            sync += zt3[i].r * zt3[i].r + zt3[i].i * zt3[i].i;
        }
    }

    if (itwk == 1 && lcqcand && iqso == 1)
    {
        for (int i = 0; i <= 7; ++i)
        {
            int i4 = i0 + (i + 7) * 32;
            kiss_fft_cpx s4 = jtdx_cpx(0, 0);
            for (int t = 0; t < 32; ++t)
            {
                kiss_fft_cpx cv = jtdx_csynccq[i][t];
                if (itwk == 1 && ctwk)
                {
                    cv = jtdx_cmul(ctwk[t], cv);
                }
                s4 = jtdx_cadd(s4, jtdx_cmul(cd0[i4 + t], jtdx_cconj(cv)));
            }
            if (ipass == 1 || ipass == 5 || ipass == 9)
            {
                sync += jtdx_cabs(s4);
            }
            else if (ipass == 2 || ipass == 6 || ipass == 7)
            {
                sync += s4.r * s4.r + s4.i * s4.i;
            }
            else
            {
                sync += fabsf(s4.r) + fabsf(s4.i);
            }
        }
    }

    return sync;
}

void jtdx_twkfreq1(kiss_fft_cpx* ca, int nbot, int npts, int ntop, float fsample,
                   const float* a, kiss_fft_cpx* cb)
{
    (void)nbot;
    (void)ntop;
    const float twopi = 8.0f * atanf(1.0f);
    kiss_fft_cpx w = jtdx_cpx(1.0f, 0.0f);
    for (int i = 0; i <= npts; ++i)
    {
        float dphi = a[0] * (twopi / fsample);
        kiss_fft_cpx wstep = jtdx_cpx(cosf(dphi), sinf(dphi));
        w = jtdx_cmul(w, wstep);
        cb[i] = jtdx_cmul(w, ca[i]);
    }
}
