/**
 * @file ft8_osd.c
 * @brief FT8 OSD 兜底译码实现（自研）。
 *
 * 算法概要（order-0 与低阶重处理）：
 *   1. 按 |LLR| 降序对 174 个比特位置排序，得到可靠性顺序 order[]；
 *      取前 K=91 个最可靠位置为「信息集」。
 *   2. 以 order[] 为列序重排校验矩阵 H，做 Gauss-Jordan 消元，使后 M=83 列
 *      成为单位阵；若某列无法选主元，则与其信息区列交换（同步更新列映射）。
 *   3. 消元后，第 p 个校验等式给出：c[K+p] = XOR_k (A[p][k] & c[k])，
 *      其中 c[0..K-1] 即信息位 u，A 取自消元后主元行。
 *   4. order-0：u 直接取信息位硬判决，生成码字；
 *      重处理：在信息集中**可靠性最低**的若干位置翻转 1/2/3 个比特。
 *   5. 每个候选码字先过汉明距离门限 nhard，再交 accept()（CRC 校验），
 *      以抑制 CRC-14 在多候选下的虚假通过。
 *
 * 复杂度：消元约 83×(83×6) 字操作；每次生成码字约 83×91 位操作。
 */
#include "ft8_osd.h"
#include "constants.h"

#include <math.h>
#include <string.h>

#define OSD_N 174
#define OSD_K 91
#define OSD_M 83
#define OSD_WORDS 6          /* ceil(174/32) */
#define OSD_K_WORDS 3        /* ceil(91/32) */
#define OSD_MAX_ATTEMPTS 256 /* 总候选上限，保护时延 */

static inline int bit_get(const uint32_t* r, int k)
{
    return (int)((r[k >> 5] >> (k & 31)) & 1u);
}

static inline void bit_set(uint32_t* r, int k, int v)
{
    uint32_t m = 1u << (k & 31);
    if (v)
        r[k >> 5] |= m;
    else
        r[k >> 5] &= ~m;
}

/* 32 位奇偶（异或和） */
static inline int parity32(uint32_t x)
{
    x ^= x >> 16;
    x ^= x >> 8;
    x ^= x >> 4;
    x &= 0xFu;
    return (int)((0x6996u >> x) & 1u);
}

/*
 * 由信息位 u（K 位）生成 174 比特码字并写回原始位序 plain[]。
 * H 为消元后的校验矩阵，row_of_parity[p] 为第 p 个校验式的主元行。
 */
static void osd_build_codeword(const uint32_t H[OSD_M][OSD_WORDS],
                               const int* row_of_parity, const int* col,
                               const uint8_t* u, uint8_t* plain)
{
    uint32_t c[OSD_WORDS];
    uint32_t uw[OSD_K_WORDS];
    memset(c, 0, sizeof(c));
    memset(uw, 0, sizeof(uw));

    for (int k = 0; k < OSD_K; ++k)
    {
        if (u[k])
        {
            c[k >> 5] |= 1u << (k & 31);
            uw[k >> 5] |= 1u << (k & 31);
        }
    }

    for (int p = 0; p < OSD_M; ++p)
    {
        const uint32_t* row = H[row_of_parity[p]];
        int par = 0;
        for (int w = 0; w < OSD_K_WORDS; ++w)
        {
            par ^= parity32(row[w] & uw[w]);
        }
        if (par)
        {
            int k = OSD_K + p;
            c[k >> 5] |= 1u << (k & 31);
        }
    }

    for (int k = 0; k < OSD_N; ++k)
    {
        plain[col[k]] = (uint8_t)((c[k >> 5] >> (k & 31)) & 1u);
    }
}

static int osd_hard_dist(const uint8_t* plain, const int* col, const uint8_t* hard_ord)
{
    int d = 0;
    for (int k = 0; k < OSD_N; ++k)
    {
        int bit = plain[col[k]] ? 1 : 0;
        if (bit != hard_ord[k])
            ++d;
    }
    return d;
}

int ft8_osd_decode(const float* log174, int depth, int max_hard_errors,
                   ft8_osd_accept_fn accept, void* ctx)
{
    if (!log174 || !accept)
        return 0;
    if (depth < 0)
        depth = 0;
    if (depth > 3)
        depth = 3;

    /* 1. 可靠性排序（按 |LLR| 降序，插入排序，N=174 足够快） */
    int order[OSD_N];
    for (int i = 0; i < OSD_N; ++i)
        order[i] = i;
    for (int i = 1; i < OSD_N; ++i)
    {
        int idx = order[i];
        float key = fabsf(log174[idx]);
        int j = i - 1;
        while (j >= 0 && fabsf(log174[order[j]]) < key)
        {
            order[j + 1] = order[j];
            --j;
        }
        order[j + 1] = idx;
    }

    /* 2. 以可靠性顺序为列序构建 H，并准备列映射（col[k] = 原始比特下标） */
    uint32_t H[OSD_M][OSD_WORDS];
    int col[OSD_N];
    int pos[OSD_N];
    memset(H, 0, sizeof(H));
    for (int k = 0; k < OSD_N; ++k)
    {
        col[k] = order[k];
        pos[order[k]] = k;
    }
    for (int m = 0; m < OSD_M; ++m)
    {
        int nr = kFTX_LDPC_Num_rows[m];
        for (int t = 0; t < nr; ++t)
        {
            int orig = kFTX_LDPC_Nm[m][t] - 1;
            int k = pos[orig];
            H[m][k >> 5] |= 1u << (k & 31);
        }
    }

    /* 3. Gauss-Jordan：让第 K..N-1 列（低可靠区）成为单位阵 */
    int row_of_parity[OSD_M];
    int used[OSD_M];
    memset(used, 0, sizeof(used));

    for (int p = 0; p < OSD_M; ++p)
    {
        int target = OSD_K + p;
        int r = -1;
        for (int m = 0; m < OSD_M; ++m)
        {
            if (!used[m] && bit_get(H[m], target))
            {
                r = m;
                break;
            }
        }
        if (r < 0)
        {
            /* 该列在剩余行中无主元：与信息区某列交换后重试 */
            for (int q = OSD_K - 1; q >= 0 && r < 0; --q)
            {
                int m2 = -1;
                for (int m = 0; m < OSD_M; ++m)
                {
                    if (!used[m] && bit_get(H[m], q))
                    {
                        m2 = m;
                        break;
                    }
                }
                if (m2 >= 0)
                {
                    for (int m = 0; m < OSD_M; ++m)
                    {
                        int a = bit_get(H[m], target);
                        int b = bit_get(H[m], q);
                        bit_set(H[m], target, b);
                        bit_set(H[m], q, a);
                    }
                    int tmp = col[target];
                    col[target] = col[q];
                    col[q] = tmp;
                    pos[col[target]] = target;
                    pos[col[q]] = q;
                    r = m2;
                }
            }
        }
        if (r < 0)
        {
            /* H 在此可靠性顺序下无法系统化，放弃（调用方回退） */
            return 0;
        }

        used[r] = 1;
        row_of_parity[p] = r;
        for (int m = 0; m < OSD_M; ++m)
        {
            if (m != r && bit_get(H[m], target))
            {
                for (int w = 0; w < OSD_WORDS; ++w)
                    H[m][w] ^= H[r][w];
            }
        }
    }

    /* 4. 硬判决（按可靠性顺序） */
    uint8_t hard_ord[OSD_N];
    for (int k = 0; k < OSD_N; ++k)
        hard_ord[k] = (log174[col[k]] > 0.0f) ? 1 : 0;

    uint8_t u[OSD_K];
    for (int k = 0; k < OSD_K; ++k)
        u[k] = hard_ord[k];

    uint8_t plain[OSD_N];
    int attempts = 0;

    /* 生成候选并（在距离门限内）尝试接受 */
#define OSD_TRY()                                                             \
    do                                                                        \
    {                                                                         \
        osd_build_codeword(H, row_of_parity, col, u, plain);                  \
        ++attempts;                                                           \
        if (max_hard_errors <= 0 || osd_hard_dist(plain, col, hard_ord) <= max_hard_errors) \
        {                                                                     \
            if (accept(plain, ctx))                                           \
                return 1;                                                     \
        }                                                                     \
    } while (0)

    /* order-0 */
    OSD_TRY();

    /* order-1：信息集中可靠性最低的 16 位，逐个翻转 */
    if (depth >= 1)
    {
        const int win1 = 16;
        for (int a = 1; a <= win1 && a <= OSD_K; ++a)
        {
            if (attempts >= OSD_MAX_ATTEMPTS)
                break;
            int idx = OSD_K - a;
            u[idx] ^= 1;
            OSD_TRY();
            u[idx] ^= 1;
        }
    }

    /* order-2：可靠性最低的 8 位内两两翻转 */
    if (depth >= 2)
    {
        const int win2 = 8;
        for (int a = 1; a <= win2; ++a)
        {
            for (int b = a + 1; b <= win2; ++b)
            {
                if (attempts >= OSD_MAX_ATTEMPTS)
                    break;
                int ia = OSD_K - a;
                int ib = OSD_K - b;
                u[ia] ^= 1;
                u[ib] ^= 1;
                OSD_TRY();
                u[ia] ^= 1;
                u[ib] ^= 1;
            }
            if (attempts >= OSD_MAX_ATTEMPTS)
                break;
        }
    }

    /* order-3：可靠性最低的 6 位内三三翻转 */
    if (depth >= 3)
    {
        const int win3 = 6;
        for (int a = 1; a <= win3; ++a)
        {
            for (int b = a + 1; b <= win3; ++b)
            {
                for (int c = b + 1; c <= win3; ++c)
                {
                    if (attempts >= OSD_MAX_ATTEMPTS)
                        break;
                    int ia = OSD_K - a;
                    int ib = OSD_K - b;
                    int ic = OSD_K - c;
                    u[ia] ^= 1;
                    u[ib] ^= 1;
                    u[ic] ^= 1;
                    OSD_TRY();
                    u[ia] ^= 1;
                    u[ib] ^= 1;
                    u[ic] ^= 1;
                }
                if (attempts >= OSD_MAX_ATTEMPTS)
                    break;
            }
            if (attempts >= OSD_MAX_ATTEMPTS)
                break;
        }
    }

#undef OSD_TRY

    return 0;
}
