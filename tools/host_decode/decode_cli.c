// 宿主机离线解码 CLI（开发基准工具，**不参与 Android 构建**）。
//
// 用途：把 app/src/main/cpp/ft8_lib/test/wav 语料喂给 Ft8Vox 的真实解码路径
// （ftx_session → decode_waterfall），按 WSJT-X 风格逐条打印，供 bench.py 统计
// recall 与假阳性。与 App 用的是同一份 native 源码，因此基准结果可信。
// --deep 打开深档（照 FT8CN：快跑后高迭代深跑 + 减谱重解循环），不加则为快档。
//
// 编译：见 tools/host_decode/build.py（需要一个支持 C11 的 gcc 或 clang；
// Windows 上 MSVC 不支持 VLA，不能用）。
// 用法：decode_cli <file.wav> [-ft4] [--min-score N] [--candidates N] [--ldpc N]
//                   [--max-decoded N] [--deep] [--fmin HZ] [--fmax HZ]

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "ftx_session.h"
#include "common/monitor.h"
#include "jtdx_decode.h"

#define OUT_CAP 256
#define TARGET_RATE 12000


static uint32_t rd_u32(const unsigned char* p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static uint16_t rd_u16(const unsigned char* p)
{
    return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}

// 读取 16-bit PCM 单声道 WAV，返回 [-1,1] 浮点样本；采样率非 12 kHz 时线性插值重采样。
// 成功返回缓冲（调用方 free），失败返回 NULL；*out_n 为样本数。
static float* load_wav_12k(const char* path, int* out_n)
{
    FILE* f = fopen(path, "rb");
    if (f == NULL)
    {
        fprintf(stderr, "cannot open %s\n", path);
        return NULL;
    }
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (sz < 44)
    {
        fclose(f);
        return NULL;
    }
    unsigned char* buf = (unsigned char*)malloc((size_t)sz);
    if (buf == NULL || fread(buf, 1, (size_t)sz, f) != (size_t)sz)
    {
        free(buf);
        fclose(f);
        return NULL;
    }
    fclose(f);

    if (memcmp(buf, "RIFF", 4) != 0 || memcmp(buf + 8, "WAVE", 4) != 0)
    {
        free(buf);
        return NULL;
    }

    unsigned fmt = 0, ch = 0, bits = 0, rate = 0;
    const unsigned char* data = NULL;
    unsigned data_len = 0;
    unsigned pos = 12;
    while (pos + 8 <= (unsigned)sz)
    {
        const unsigned char* c = buf + pos;
        unsigned csz = rd_u32(c + 4);
        if (memcmp(c, "fmt ", 4) == 0 && csz >= 16)
        {
            fmt = rd_u16(c + 8);
            ch = rd_u16(c + 10);
            rate = rd_u32(c + 12);
            bits = rd_u16(c + 22);
        }
        else if (memcmp(c, "data", 4) == 0)
        {
            data = c + 8;
            data_len = csz;
            if (pos + 8 + (long)csz > sz)
                data_len = (unsigned)(sz - (pos + 8));
        }
        pos += 8 + csz + (csz & 1);
    }
    if (data == NULL || fmt != 1 || ch != 1 || bits != 16)
    {
        fprintf(stderr, "unsupported wav (fmt=%u ch=%u bits=%u): %s\n", fmt, ch, bits, path);
        free(buf);
        return NULL;
    }

    int n_in = (int)(data_len / 2);
    float* in = (float*)malloc(sizeof(float) * (size_t)n_in);
    if (in == NULL)
    {
        free(buf);
        return NULL;
    }
    for (int i = 0; i < n_in; ++i)
        in[i] = (int16_t)rd_u16(data + i * 2) / 32768.0f;

    float* out;
    int n_out;
    if ((int)rate == TARGET_RATE)
    {
        out = in;
        n_out = n_in;
    }
    else
    {
        double step = (double)rate / TARGET_RATE;
        n_out = (int)((n_in - 1) / step);
        out = (float*)malloc(sizeof(float) * (size_t)(n_out > 0 ? n_out : 1));
        if (out == NULL)
        {
            free(in);
            free(buf);
            return NULL;
        }
        for (int i = 0; i < n_out; ++i)
        {
            double x = i * step;
            int i0 = (int)x;
            double fr = x - i0;
            int i1 = (i0 + 1 < n_in) ? i0 + 1 : i0;
            out[i] = (float)(in[i0] * (1.0 - fr) + in[i1] * fr);
        }
        free(in);
    }
    free(buf);
    *out_n = n_out;
    return out;
}

/// 对一段 12 kHz 音频跑完整的 ftx_session 解码，结果写进 out，返回条数。
static int decode_buffer(const float* samples, int n, ftx_protocol_t proto,
                         const ftx_decode_params_t* p, float fmin, float fmax,
                         ftx_decode_result_t* out, int cap)
{
    monitor_config_t cfg = {
        .f_min = fmin,
        .f_max = fmax,
        .sample_rate = TARGET_RATE,
        .time_osr = 2,
        .freq_osr = 2,
        .protocol = proto,
    };
    ftx_session_t* session = ftx_session_create(&cfg);
    if (session == NULL)
        return 0;
    ftx_session_set_decode_params(session, p);
    ftx_session_process(session, samples, n);
    int nr = ftx_session_decode(session, out, cap);
    ftx_session_free(session);
    return nr;
}

static void usage(void)
{
    fprintf(stderr,
            "usage: decode_cli <file.wav> [-ft4] [--min-score N] [--candidates N] [--ldpc N]\n"
            "                   [--max-decoded N] [--deep] [--jtdx] [--passes N] [--fmin HZ] [--fmax HZ]\n");
}

int main(int argc, char** argv)
{
    const char* path = NULL;
    ftx_protocol_t proto = FTX_PROTOCOL_FT8;
    ftx_decode_params_t p = ftx_decode_params_default();
    float fmin = 100.0f;
    float fmax = 3000.0f;
    int use_jtdx = 0;
    int jtdx_passes = 3;

    for (int i = 1; i < argc; ++i)
    {
        const char* a = argv[i];
        if (strcmp(a, "-ft4") == 0)
            proto = FTX_PROTOCOL_FT4;
        else if (strcmp(a, "--min-score") == 0 && i + 1 < argc)
            p.min_score = atoi(argv[++i]);
        else if (strcmp(a, "--candidates") == 0 && i + 1 < argc)
            p.max_candidates = atoi(argv[++i]);
        else if (strcmp(a, "--ldpc") == 0 && i + 1 < argc)
            p.ldpc_iterations = atoi(argv[++i]);
        else if (strcmp(a, "--max-decoded") == 0 && i + 1 < argc)
            p.max_decoded = atoi(argv[++i]);
        else if (strcmp(a, "--deep") == 0)
            p.deep = 1;
        else if (strcmp(a, "--jtdx") == 0)
            use_jtdx = 1;
        else if (strcmp(a, "--passes") == 0 && i + 1 < argc)
            jtdx_passes = atoi(argv[++i]);
        else if (strcmp(a, "--fmin") == 0 && i + 1 < argc)
            fmin = (float)atof(argv[++i]);
        else if (strcmp(a, "--fmax") == 0 && i + 1 < argc)
            fmax = (float)atof(argv[++i]);
        else if (a[0] != '-')
            path = a;
        else
        {
            fprintf(stderr, "unknown arg: %s\n", a);
            usage();
            return 2;
        }
    }
    if (path == NULL)
    {
        usage();
        return 2;
    }

    int n = 0;
    float* samples = load_wav_12k(path, &n);
    if (samples == NULL)
        return 1;

    if (use_jtdx)
    {
        static jtdx_decode_result_t jres[OUT_CAP];
        int nr = jtdx_decode_slot(samples, n, fmin, fmax, jtdx_passes, jres, OUT_CAP);
        free(samples);
        for (int i = 0; i < nr; ++i)
        {
            const jtdx_decode_result_t* r = &jres[i];
            printf("000000 %+3d %+5.1f %4d ~  %s\n", r->snr, r->dt, r->df, r->text);
        }
        return 0;
    }

    ftx_decode_result_t results[OUT_CAP];
    int nr = decode_buffer(samples, n, proto, &p, fmin, fmax, results, OUT_CAP);
    free(samples);
    for (int i = 0; i < nr; ++i)
    {
        const ftx_decode_result_t* r = &results[i];
        // WSJT-X 风格：<utc6> <snr> <dt> <freq> ~ <message>；bench.py 只取频率与报文。
        printf("000000 %+3d %+5.1f %4d ~  %s\n", r->snr, r->dt, r->df, r->text);
    }
    return 0;
}
