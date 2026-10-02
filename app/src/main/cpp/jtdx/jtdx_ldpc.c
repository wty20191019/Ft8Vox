// JTDX (174,91) LDPC 译码器移植（C）。对应 jtdx/lib/ft8v2 下的
// bpdecode174_91.f90 / osd174_91.f90 / chkcrc14a.f90。
//
// 设计说明：
// - 全部使用 0 基下标；JTDX 原文为 1 基，已在注释中标注对应关系。
// - BMP 的早停、OSD 的阶数/预处理参数、platanh 近似都与 JTDX 保持逐行一致，
//   以保证软判决行为（而非仅结果）尽量贴近。
// - 位序与 ft8_lib 相同：174 比特前 91 = 77 信息 + 14 CRC；正 LLR 判 1。

#include "jtdx_ldpc.h"
#include "jtdx_ldpc_data.h"

#include <math.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>

#define JDX_N 174
#define JDX_K 91
#define JDX_M 83

// -----------------------------------------------------------------------------
// CRC14（与 ft8_lib 的 ftx_compute_crc/ftx_extract_crc 完全一致，保证互通）
// -----------------------------------------------------------------------------
// 说明：FT8 的 14 位 CRC 定义以 WSJT-X 为准，ft8_lib 已证实与其双向兼容。
// JTDX 的 chkcrc14a.f90 用 boost::augmented_crc<14,0x2757> 对 91 位码字判定；
// 为保证与 ft8_lib 的报文层互通，这里直接复用 ft8_lib 的位级算法。
#define JDX_CRC_WIDTH 14
#define JDX_CRC_POLY 0x2757u
#define JDX_CRC_TOPBIT (1u << (JDX_CRC_WIDTH - 1))

static uint16_t jtdx_compute_crc(const uint8_t message[], int num_bits)
{
    uint16_t remainder = 0;
    int idx_byte = 0;
    for (int idx_bit = 0; idx_bit < num_bits; ++idx_bit)
    {
        if (idx_bit % 8 == 0)
        {
            remainder ^= (uint16_t)(message[idx_byte] << (JDX_CRC_WIDTH - 8));
            ++idx_byte;
        }
        if (remainder & JDX_CRC_TOPBIT)
        {
            remainder = (uint16_t)((remainder << 1) ^ JDX_CRC_POLY);
        }
        else
        {
            remainder = (uint16_t)(remainder << 1);
        }
    }
    return (uint16_t)(remainder & ((JDX_CRC_TOPBIT << 1) - 1u));
}

int jtdx_chkcrc14(const uint8_t decoded91[91])
{
    uint8_t a91[12];
    memset(a91, 0, sizeof(a91));
    for (int i = 0; i < 91; ++i)
    {
        if (decoded91[i])
        {
            a91[i >> 3] |= (uint8_t)(0x80u >> (i & 7));
        }
    }
    uint16_t ncrc14 = (uint16_t)(((a91[9] & 0x07u) << 11) | (a91[10] << 3) | (a91[11] >> 5));
    a91[9] &= 0xF8u;
    a91[10] = 0;
    a91[11] = 0;
    uint16_t icrc14 = jtdx_compute_crc(a91, 96 - 14);
    return (ncrc14 == icrc14) ? 0 : 1;
}

// platanh 近似（照 jtdx/lib/bpdecode144.f90 的 platanh）
static float jtdx_platanh(float x)
{
    float isign = 1.0f;
    float z = x;
    if (x < 0.0f)
    {
        isign = -1.0f;
        z = -x;
    }
    if (z <= 0.664f)
    {
        return x / 0.83f;
    }
    if (z <= 0.9217f)
    {
        return isign * (z - 0.4064f) / 0.322f;
    }
    if (z <= 0.9951f)
    {
        return isign * (z - 0.8378f) / 0.0524f;
    }
    if (z <= 0.9998f)
    {
        return isign * (z - 0.9914f) / 0.0012f;
    }
    return isign * 7.0f;
}

// -----------------------------------------------------------------------------
// BP 译码
// -----------------------------------------------------------------------------
int jtdx_ldpc_bp_decode(const float* llr, const uint8_t* apmask, int max_iterations,
                        uint8_t* cw, int* nharderror, int* niterations)
{
    if (max_iterations < 1)
    {
        max_iterations = 1;
    }
    static const uint8_t zero_apmask[JDX_N] = {0};
    if (apmask == NULL)
    {
        apmask = zero_apmask;
    }

    float toc[7][JDX_M];
    float tov[3][JDX_N];
    float tanhtoc[7][JDX_M];
    float zn[JDX_N];

    memset(toc, 0, sizeof(toc));
    memset(tov, 0, sizeof(tov));
    memset(tanhtoc, 0, sizeof(tanhtoc));

    // initialize messages to checks
    for (int j = 0; j < JDX_M; ++j)
    {
        for (int i = 0; i < kJtdxNrw[j]; ++i)
        {
            toc[i][j] = llr[kJtdxNm[j][i] - 1];
        }
    }

    int ncnt = 0;
    int nclast = 0;
    int iter = 0;

    for (iter = 0; iter <= max_iterations; ++iter)
    {
        // Update bit log likelihood ratios (tov=0 in iteration 0)
        for (int i = 0; i < JDX_N; ++i)
        {
            if (apmask[i] != 1)
            {
                zn[i] = llr[i] + tov[0][i] + tov[1][i] + tov[2][i];
            }
            else
            {
                zn[i] = llr[i];
            }
        }

        // Check to see if we have a codeword (check before we do any iteration)
        for (int i = 0; i < JDX_N; ++i)
        {
            cw[i] = (zn[i] > 0.0f) ? 1 : 0;
        }
        int ncheck = 0;
        for (int j = 0; j < JDX_M; ++j)
        {
            int synd = 0;
            for (int i = 0; i < kJtdxNrw[j]; ++i)
            {
                synd ^= cw[kJtdxNm[j][i] - 1];
            }
            if (synd & 1)
            {
                ++ncheck;
            }
        }
        if (ncheck == 0)
        {
            int he = 0;
            for (int i = 0; i < JDX_N; ++i)
            {
                float v = (2.0f * (float)cw[i] - 1.0f) * llr[i];
                if (v < 0.0f)
                {
                    ++he;
                }
            }
            if (jtdx_chkcrc14(cw) == 0)
            {
                *nharderror = he;
                *niterations = iter;
                return 0;
            }
        }

        if (iter > 0)
        {
            int nd = ncheck - nclast;
            if (nd < 0)
            {
                ncnt = 0;
            }
            else
            {
                ++ncnt;
            }
            if (ncnt >= 5 && iter >= 10 && ncheck > 15)
            {
                *nharderror = -1;
                *niterations = iter;
                return 1;
            }
        }
        nclast = ncheck;

        // Send messages from bits to check nodes
        for (int j = 0; j < JDX_M; ++j)
        {
            for (int i = 0; i < kJtdxNrw[j]; ++i)
            {
                int ibj = kJtdxNm[j][i] - 1;
                float t = zn[ibj];
                for (int kk = 0; kk < 3; ++kk)
                {
                    if (kJtdxMn[ibj][kk] - 1 == j)
                    {
                        t -= tov[kk][ibj];
                    }
                }
                toc[i][j] = t;
            }
        }

        // Send messages from check nodes to variable nodes
        for (int j = 0; j < JDX_M; ++j)
        {
            for (int i = 0; i < 7; ++i)
            {
                tanhtoc[i][j] = tanhf(-toc[i][j] / 2.0f);
            }
        }
        for (int b = 0; b < JDX_N; ++b)
        {
            for (int i = 0; i < 3; ++i)
            {
                int ichk = kJtdxMn[b][i] - 1;
                float tmn = 1.0f;
                for (int m = 0; m < kJtdxNrw[ichk]; ++m)
                {
                    int bm2 = kJtdxNm[ichk][m] - 1;
                    if (bm2 != b)
                    {
                        tmn *= tanhtoc[m][ichk];
                    }
                }
                tov[i][b] = 2.0f * jtdx_platanh(-tmn);
            }
        }
    }

    *nharderror = -1;
    *niterations = iter;
    return 1;
}

// -----------------------------------------------------------------------------
// OSD 译码
// -----------------------------------------------------------------------------
static uint8_t s_gen[JDX_K][JDX_N]; // 生成矩阵 [91][174]，原列序
static int s_gen_ready = 0;

static void build_generator(void)
{
    if (s_gen_ready)
    {
        return;
    }
    memset(s_gen, 0, sizeof(s_gen));
    for (int i = 1; i <= JDX_M; ++i) // JTDX: do i=1,M
    {
        const char* hex = kJtdxGenHex[i - 1];
        for (int j = 1; j <= 23; ++j)
        {
            char ch = hex[j - 1];
            int istr = (ch >= '0' && ch <= '9') ? (ch - '0') : (ch - 'a' + 10);
            int ibmax = (j == 23) ? 3 : 4;
            for (int jj = 1; jj <= ibmax; ++jj)
            {
                int irow = (j - 1) * 4 + jj; // 1..91
                if ((istr >> (4 - jj)) & 1)
                {
                    s_gen[irow - 1][JDX_K + i - 1] = 1;
                }
            }
        }
    }
    for (int irow = 1; irow <= JDX_K; ++irow)
    {
        s_gen[irow - 1][irow - 1] = 1;
    }
    s_gen_ready = 1;
}

// 用生成矩阵编码：cw = XOR_{msg[r]=1} gen[r][:]
void jtdx_ldpc_encode(const uint8_t msg91[91], uint8_t cw174[174])
{
    build_generator();
    memset(cw174, 0, JDX_N);
    for (int r = 0; r < JDX_K; ++r)
    {
        if (msg91[r] == 1)
        {
            for (int c = 0; c < JDX_N; ++c)
            {
                cw174[c] ^= s_gen[r][c];
            }
        }
    }
}

// mrbencode91：codeword = XOR_{me[i]} g2[:,i]
static void mrbencode91(const uint8_t* me, uint8_t* codeword, const uint8_t g2[JDX_N][JDX_K])
{
    memset(codeword, 0, JDX_N);
    for (int i = 0; i < JDX_K; ++i)
    {
        if (me[i] == 1)
        {
            for (int c = 0; c < JDX_N; ++c)
            {
                codeword[c] ^= g2[c][i];
            }
        }
    }
}

// nextpat91：生成下一个测试错误图样；iflag 指向 mi 中最低下标的 1；无更多图样时 iflag=-1
static void nextpat91(int* mi, int k, int iorder, int* iflag)
{
    int* ms = (int*)malloc(sizeof(int) * (size_t)(k + 1));
    int ind = -1;
    for (int i = 1; i <= k - 1; ++i)
    {
        if (mi[i] == 0 && mi[i + 1] == 1)
        {
            ind = i;
        }
    }
    if (ind < 0)
    {
        *iflag = -1;
        free(ms);
        return;
    }
    for (int i = 0; i <= k; ++i)
    {
        ms[i] = 0;
    }
    for (int i = 1; i <= ind - 1; ++i)
    {
        ms[i] = mi[i];
    }
    ms[ind] = 1;
    ms[ind + 1] = 0;
    if (ind + 1 < k)
    {
        int sum = 0;
        for (int i = 1; i <= k; ++i)
        {
            sum += ms[i];
        }
        int nz = iorder - sum;
        for (int i = k - nz + 1; i <= k; ++i)
        {
            ms[i] = 1;
        }
    }
    for (int i = 1; i <= k; ++i)
    {
        mi[i] = ms[i];
    }
    int found = 0;
    for (int i = 1; i <= k; ++i)
    {
        if (mi[i] == 1)
        {
            *iflag = i;
            found = 1;
            break;
        }
    }
    if (!found)
    {
        *iflag = -1;
    }
    free(ms);
}

// OSD 第二预处理用的哈希表（对照 JTDX boxit91/fetchit91）
typedef struct
{
    int* fp;      // 0:2^ntau-1，指向 np
    int* np;      // 链
    int* col1;    // indexes[.,1]
    int* col2;    // indexes[.,2]
    int npat;     // 2^ntau
} osd_hash_t;

static void boxit91(osd_hash_t* h, int* reset, const uint8_t* e2, int ntau, int npindex, int i1, int i2)
{
    if (*reset)
    {
        for (int i = 0; i < h->npat; ++i)
        {
            h->fp[i] = -1;
        }
        for (int i = 0; i < 5000; ++i)
        {
            h->np[i] = -1;
        }
        *reset = 0;
    }
    h->col1[npindex] = i1;
    h->col2[npindex] = i2;
    int ipat = 0;
    for (int i = 0; i < ntau; ++i)
    {
        if (e2[i] == 1)
        {
            ipat += 1 << (ntau - 1 - i);
        }
    }
    if (ipat < 0 || ipat >= h->npat)
    {
        return;
    }
    int ip = h->fp[ipat];
    if (ip == -1)
    {
        h->fp[ipat] = npindex;
    }
    else
    {
        while (h->np[ip] != -1)
        {
            ip = h->np[ip];
        }
        h->np[ip] = npindex;
    }
}

static void fetchit91(osd_hash_t* h, int* reset, int* lastpat, int* inext, const uint8_t* e2,
                      int ntau, int* i1, int* i2)
{
    if (*reset)
    {
        *lastpat = -1;
        *reset = 0;
    }
    int ipat = 0;
    for (int i = 0; i < ntau; ++i)
    {
        if (e2[i] == 1)
        {
            ipat += 1 << (ntau - 1 - i);
        }
    }
    int index = (ipat >= 0 && ipat < h->npat) ? h->fp[ipat] : -1;

    if (*lastpat != ipat && index > 0)
    {
        *i1 = h->col1[index];
        *i2 = h->col2[index];
        *inext = h->np[index];
    }
    else if (*lastpat == ipat && *inext > 0)
    {
        *i1 = h->col1[*inext];
        *i2 = h->col2[*inext];
        *inext = h->np[*inext];
    }
    else
    {
        *i1 = -1;
        *i2 = -1;
        *inext = -1;
    }
    *lastpat = ipat;
}

// 简单稳定排序：按 key 升序输出 idx[0..n-1]
typedef struct
{
    const float* key;
} sort_ctx_t;

static sort_ctx_t g_sort_ctx;
static int cmp_idx(const void* a, const void* b)
{
    int ia = *(const int*)a;
    int ib = *(const int*)b;
    float ka = g_sort_ctx.key[ia];
    float kb = g_sort_ctx.key[ib];
    if (ka < kb)
    {
        return -1;
    }
    if (ka > kb)
    {
        return 1;
    }
    return ia - ib;
}

static void jtdx_indexx(const float* arr, int n, int* idx)
{
    for (int i = 0; i < n; ++i)
    {
        idx[i] = i;
    }
    g_sort_ctx.key = arr;
    qsort(idx, (size_t)n, sizeof(int), cmp_idx);
}

void jtdx_ldpc_osd_decode(const float* llr, const uint8_t* apmask, int ndeep,
                          uint8_t* cw, int* nhardmin, float* dmin)
{
    build_generator();
    static const uint8_t zero_apmask[JDX_N] = {0};
    if (apmask == NULL)
    {
        apmask = zero_apmask;
    }

    uint8_t genmrb[JDX_K][JDX_N];
    uint8_t g2[JDX_N][JDX_K];
    int indices[JDX_N];
    uint8_t hdec[JDX_N];
    uint8_t hdec_orig[JDX_N];
    uint8_t apmaskr[JDX_N];
    uint8_t apmask_orig[JDX_N];
    float absrx_orig[JDX_N];
    float absrx[JDX_N];
    uint8_t c0[JDX_N];
    uint8_t ce[JDX_N];
    int nxor[JDX_N];

    memset(genmrb, 0, sizeof(genmrb));
    memset(g2, 0, sizeof(g2));

    for (int i = 0; i < JDX_N; ++i)
    {
        hdec_orig[i] = (llr[i] >= 0.0f) ? 1 : 0;
        absrx_orig[i] = fabsf(llr[i]);
        apmask_orig[i] = apmask[i];
    }

    int idx[JDX_N];
    jtdx_indexx(absrx_orig, JDX_N, idx);

    // Re-order the columns of the generator matrix in order of decreasing reliability
    for (int i = 0; i < JDX_N; ++i)
    {
        indices[i] = idx[JDX_N - 1 - i];
        for (int r = 0; r < JDX_K; ++r)
        {
            genmrb[r][i] = s_gen[r][indices[i]];
        }
    }

    // Gaussian elimination：把最可靠的前 K 位变成单位阵
    for (int id = 0; id < JDX_K; ++id)
    {
        int upper = JDX_K + 20;
        if (upper > JDX_N)
        {
            upper = JDX_N;
        }
        for (int icol = id; icol < upper; ++icol)
        {
            if (genmrb[id][icol] == 1)
            {
                if (icol != id)
                {
                    for (int r = 0; r < JDX_K; ++r)
                    {
                        uint8_t t = genmrb[r][id];
                        genmrb[r][id] = genmrb[r][icol];
                        genmrb[r][icol] = t;
                    }
                    int itmp = indices[id];
                    indices[id] = indices[icol];
                    indices[icol] = itmp;
                }
                for (int ii = 0; ii < JDX_K; ++ii)
                {
                    if (ii != id && genmrb[ii][id] == 1)
                    {
                        for (int c = 0; c < JDX_N; ++c)
                        {
                            genmrb[ii][c] ^= genmrb[id][c];
                        }
                    }
                }
                break;
            }
        }
    }

    for (int c = 0; c < JDX_N; ++c)
    {
        for (int r = 0; r < JDX_K; ++r)
        {
            g2[c][r] = genmrb[r][c];
        }
    }

    // 按可靠性顺序重排硬判决/软值/先验
    for (int i = 0; i < JDX_N; ++i)
    {
        hdec[i] = hdec_orig[indices[i]];
        absrx[i] = absrx_orig[indices[i]];
        apmaskr[i] = apmask_orig[indices[i]];
    }

    uint8_t m0[JDX_K];
    for (int i = 0; i < JDX_K; ++i)
    {
        m0[i] = hdec[i];
    }

    mrbencode91(m0, c0, g2);
    for (int i = 0; i < JDX_N; ++i)
    {
        nxor[i] = c0[i] ^ hdec[i];
    }
    int hmin = 0;
    float dist = 0.0f;
    for (int i = 0; i < JDX_N; ++i)
    {
        hmin += nxor[i];
        dist += (float)nxor[i] * absrx[i];
    }
    memcpy(cw, c0, JDX_N);

    if (ndeep <= 0)
    {
        goto done;
    }
    if (ndeep > 5)
    {
        ndeep = 5;
    }

    int nord = 0, npre1 = 0, npre2 = 0, nt = 0, ntheta = 0, ntau = 0;
    switch (ndeep)
    {
    case 1: nord = 1; npre1 = 0; npre2 = 0; nt = 40; ntheta = 12; break;
    case 2: nord = 1; npre1 = 1; npre2 = 0; nt = 40; ntheta = 12; break;
    case 3: nord = 1; npre1 = 1; npre2 = 1; nt = 40; ntheta = 12; ntau = 14; break;
    case 4: nord = 2; npre1 = 1; npre2 = 0; nt = 40; ntheta = 12; ntau = 19; break;
    case 5: nord = 2; npre1 = 1; npre2 = 1; nt = 40; ntheta = 12; ntau = 19; break;
    default: nord = 1; npre1 = 0; npre2 = 0; nt = 40; ntheta = 12; break;
    }

    int* misub = (int*)calloc((size_t)(JDX_K + 1), sizeof(int));
    int* mi = (int*)calloc((size_t)(JDX_K + 1), sizeof(int));
    for (int iorder = 1; iorder <= nord; ++iorder)
    {
        for (int i = 1; i <= JDX_K; ++i)
        {
            misub[i] = 0;
        }
        for (int i = JDX_K - iorder + 1; i <= JDX_K; ++i)
        {
            misub[i] = 1;
        }
        int iflag = JDX_K - iorder + 1;
        while (iflag >= 0)
        {
            int iend = (iorder == nord && npre1 == 0) ? iflag : 1;
            // e2sub 在 n1==iflag（本轮首个）时计算，之后被更小的 n1 复用，
            // 因此必须声明在 n1 循环之外（对应 Fortran 的循环外变量）。
            uint8_t e2sub[JDX_N - JDX_K];
            uint8_t e2[JDX_N - JDX_K];
            float d1 = 0.0f;
            memset(e2sub, 0, sizeof(e2sub));
            memset(e2, 0, sizeof(e2));
            for (int n1 = iflag; n1 >= iend; --n1)
            {
                memcpy(mi, misub, sizeof(int) * (size_t)(JDX_K + 1));
                mi[n1] = 1;
                int ap = 0;
                for (int i = 1; i <= JDX_K; ++i)
                {
                    if ((apmaskr[i - 1] & (uint8_t)mi[i]) == 1)
                    {
                        ap = 1;
                        break;
                    }
                }
                if (ap)
                {
                    continue;
                }
                uint8_t me[JDX_K];
                for (int i = 0; i < JDX_K; ++i)
                {
                    me[i] = m0[i] ^ (uint8_t)mi[i + 1];
                }

                int nd1Kpt;
                if (n1 == iflag)
                {
                    mrbencode91(me, ce, g2);
                    for (int c = 0; c < JDX_N - JDX_K; ++c)
                    {
                        e2sub[c] = ce[JDX_K + c] ^ hdec[JDX_K + c];
                        e2[c] = e2sub[c];
                    }
                    int s = 0;
                    for (int c = 0; c < nt && c < JDX_N - JDX_K; ++c)
                    {
                        s += e2sub[c];
                    }
                    nd1Kpt = s + 1;
                    for (int i = 0; i < JDX_K; ++i)
                    {
                        d1 += (float)(me[i] ^ hdec[i]) * absrx[i];
                    }
                }
                else
                {
                    for (int c = 0; c < JDX_N - JDX_K; ++c)
                    {
                        e2[c] = e2sub[c] ^ g2[JDX_K + c][n1 - 1];
                    }
                    int s = 0;
                    for (int c = 0; c < nt && c < JDX_N - JDX_K; ++c)
                    {
                        s += e2[c];
                    }
                    nd1Kpt = s + 2;
                }

                if (nd1Kpt <= ntheta)
                {
                    mrbencode91(me, ce, g2);
                    int nx = 0;
                    for (int i = 0; i < JDX_N; ++i)
                    {
                        nxor[i] = ce[i] ^ hdec[i];
                        nx += nxor[i];
                    }
                    float dd;
                    if (n1 == iflag)
                    {
                        dd = d1;
                        for (int c = 0; c < JDX_N - JDX_K; ++c)
                        {
                            dd += (float)e2sub[c] * absrx[JDX_K + c];
                        }
                    }
                    else
                    {
                        dd = d1 + (float)(ce[n1 - 1] ^ hdec[n1 - 1]) * absrx[n1 - 1];
                        for (int c = 0; c < JDX_N - JDX_K; ++c)
                        {
                            dd += (float)e2[c] * absrx[JDX_K + c];
                        }
                    }
                    if (dd < dist)
                    {
                        dist = dd;
                        memcpy(cw, ce, JDX_N);
                        hmin = nx;
                    }
                }
            }
            nextpat91(misub, JDX_K, iorder, &iflag);
        }
    }

    if (npre2 == 1)
    {
        osd_hash_t h;
        h.npat = 1 << ntau;
        h.fp = (int*)malloc(sizeof(int) * (size_t)h.npat);
        h.np = (int*)malloc(sizeof(int) * 5000);
        h.col1 = (int*)malloc(sizeof(int) * 5000);
        h.col2 = (int*)malloc(sizeof(int) * 5000);
        int reset = 1;
        int ntotal = 0;
        for (int i1 = JDX_K; i1 >= 1; --i1)
        {
            for (int i2 = i1 - 1; i2 >= 1; --i2)
            {
                ++ntotal;
                uint8_t e2t[32];
                for (int c = 0; c < ntau; ++c)
                {
                    e2t[c] = g2[JDX_K + c][i1 - 1] ^ g2[JDX_K + c][i2 - 1];
                }
                boxit91(&h, &reset, e2t, ntau, ntotal, i1, i2);
            }
        }

        int lastpat = -1;
        int inext = -1;
        reset = 1;
        for (int i = 1; i <= JDX_K; ++i)
        {
            misub[i] = 0;
        }
        for (int i = JDX_K - nord + 1; i <= JDX_K; ++i)
        {
            misub[i] = 1;
        }
        int iflag = JDX_K - nord + 1;
        while (iflag >= 0)
        {
            uint8_t me[JDX_K];
            for (int i = 0; i < JDX_K; ++i)
            {
                me[i] = m0[i] ^ (uint8_t)misub[i + 1];
            }
            mrbencode91(me, ce, g2);
            uint8_t e2sub[JDX_N - JDX_K];
            for (int c = 0; c < JDX_N - JDX_K; ++c)
            {
                e2sub[c] = ce[JDX_K + c] ^ hdec[JDX_K + c];
            }
            for (int i2 = 0; i2 <= ntau; ++i2)
            {
                uint8_t ui[32];
                memset(ui, 0, sizeof(ui));
                if (i2 > 0)
                {
                    ui[i2 - 1] = 1;
                }
                uint8_t r2pat[32];
                for (int c = 0; c < ntau; ++c)
                {
                    r2pat[c] = e2sub[c] ^ ui[c];
                }
                for (;;)
                {
                    int in1, in2;
                    fetchit91(&h, &reset, &lastpat, &inext, r2pat, ntau, &in1, &in2);
                    reset = 0;
                    if (in1 > 0 && in2 > 0)
                    {
                        memcpy(mi, misub, sizeof(int) * (size_t)(JDX_K + 1));
                        mi[in1] = 1;
                        mi[in2] = 1;
                        int sum = 0;
                        for (int i = 1; i <= JDX_K; ++i)
                        {
                            sum += mi[i];
                        }
                        int bad = (sum < nord + npre1 + npre2);
                        if (!bad)
                        {
                            for (int i = 1; i <= JDX_K; ++i)
                            {
                                if ((apmaskr[i - 1] & (uint8_t)mi[i]) == 1)
                                {
                                    bad = 1;
                                    break;
                                }
                            }
                        }
                        if (bad)
                        {
                            // Fortran 的 cycle 属于 do i2：放弃当前 pattern，进入下一个 i2
                            break;
                        }
                        uint8_t me2[JDX_K];
                        for (int i = 0; i < JDX_K; ++i)
                        {
                            me2[i] = m0[i] ^ (uint8_t)mi[i + 1];
                        }
                        mrbencode91(me2, ce, g2);
                        int nx = 0;
                        float dd = 0.0f;
                        for (int i = 0; i < JDX_N; ++i)
                        {
                            int x = ce[i] ^ hdec[i];
                            nx += x;
                            dd += (float)x * absrx[i];
                        }
                        if (dd < dist)
                        {
                            dist = dd;
                            memcpy(cw, ce, JDX_N);
                            hmin = nx;
                        }
                        continue; // goto 778：同一 pattern 的下一个匹配
                    }
                    break;
                }
            }
            nextpat91(misub, JDX_K, nord, &iflag);
        }
        free(h.fp);
        free(h.np);
        free(h.col1);
        free(h.col2);
    }

    free(misub);
    free(mi);

done:
    // 把码字从可靠性顺序还原到原始比特顺序
    {
        uint8_t tmp[JDX_N];
        for (int i = 0; i < JDX_N; ++i)
        {
            tmp[indices[i]] = cw[i];
        }
        memcpy(cw, tmp, JDX_N);
    }

    uint8_t decoded[JDX_K];
    memcpy(decoded, cw, JDX_K);
    int badcrc = jtdx_chkcrc14(decoded);

    *nhardmin = badcrc ? -hmin : hmin;
    *dmin = dist;
}
