/**
 * @file ft8_cli.c
 * @brief 主机端测试与基准工具（不参与 Android 构建）。
 *
 * 用法：
 *   ft8_cli decode <wav> [选项]
 *   ft8_cli gen <message> <out.wav> [freq] [选项]
 *   ft8_cli bench <dir> [选项]
 *
 * 通用选项：
 *   --threads N     解码线程数（默认 1）
 *   --time-osr N    时间过采样（默认 2）
 *   --freq-osr N    频率过采样（默认 2）
 *   --min-score N   同步最低分（默认 10）
 *   --iters N       LDPC 迭代上限（默认 25）
 *   --candidates N  最大候选数（默认 140）
 *   --fmin HZ       频率下限（默认 200）
 *   --fmax HZ       频率上限（默认 3000）
 *   --bands N       频率分段数（默认 1；>1 时启用分段并行解码）
 *   --global-candidates 0|1
 *                   候选上限的分配口径（仅 --bands>1 时有效）：
 *                   1=全局择优（默认），0=按段均分
 *   --repeat N      基准重复次数（默认 1）
 *   --block N       论文式分组检测最大 N（0=关，1..3；默认 0）
 *   --block-min-n N 分组检测起始 N（默认 1；诊断可设 2/3）
 *   --block-skip-fast 跳过瀑布域 N=1，直接走 200 Hz 分组检测
 *   --block-min-score N / --block-max-errors N / --block-candidates N
 *                   分组检测的同步分下限 / BP 残错上限 / 每遍候选预算
 *   --ap-mode N     AP 位掩码（1=MyCall，2=MyCall+DxCall，4=+RRR/73/RR73）
 *   --my-call CALL / --his-call CALL  AP 上下文
 */
#include "ft8api.h"
#include "common/wave.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <math.h>
#include <stdbool.h>

#ifdef _WIN32
#include <windows.h>
#define strtok_r strtok_s
#define strdup _strdup
#else
#include <dirent.h>
#include <time.h>
#endif

#define MAX_LOAD_SAMPLES (15 * 48000)

/* ------------------------------------------------------------------------- */
/* 计时                                                                       */
/* ------------------------------------------------------------------------- */
static double now_ms(void)
{
#ifdef _WIN32
    static double freq = 0.0;
    LARGE_INTEGER c;
    if (freq == 0.0)
    {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        freq = (double)f.QuadPart / 1000.0;
    }
    QueryPerformanceCounter(&c);
    return (double)c.QuadPart / freq;
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
#endif
}

/* ------------------------------------------------------------------------- */
/* 选项                                                                       */
/* ------------------------------------------------------------------------- */
typedef struct
{
    ft8_decode_config_t dec;
    int repeat;
} options_t;

static void options_default(options_t* o)
{
    ft8_decode_config_default(&o->dec);
    o->repeat = 1;
}

static bool next_int(int argc, char** argv, int* i, int* out)
{
    if (*i + 1 >= argc)
        return false;
    *out = atoi(argv[++(*i)]);
    return true;
}

static bool next_float(int argc, char** argv, int* i, float* out)
{
    if (*i + 1 >= argc)
        return false;
    *out = (float)atof(argv[++(*i)]);
    return true;
}

static bool parse_option(int argc, char** argv, int* i, options_t* o)
{
    const char* a = argv[*i];
    if (strcmp(a, "--threads") == 0)
        return next_int(argc, argv, i, &o->dec.num_threads);
    if (strcmp(a, "--time-osr") == 0)
        return next_int(argc, argv, i, &o->dec.time_osr);
    if (strcmp(a, "--freq-osr") == 0)
        return next_int(argc, argv, i, &o->dec.freq_osr);
    if (strcmp(a, "--min-score") == 0)
        return next_int(argc, argv, i, &o->dec.min_sync_score);
    if (strcmp(a, "--iters") == 0)
        return next_int(argc, argv, i, &o->dec.ldpc_iterations);
    if (strcmp(a, "--candidates") == 0)
        return next_int(argc, argv, i, &o->dec.max_candidates);
    if (strcmp(a, "--repeat") == 0)
        return next_int(argc, argv, i, &o->repeat);
    if (strcmp(a, "--depth") == 0)
        return next_int(argc, argv, i, &o->dec.decode_depth);
    if (strcmp(a, "--osd") == 0)
        return next_int(argc, argv, i, &o->dec.osd_depth);
    if (strcmp(a, "--bands") == 0)
        return next_int(argc, argv, i, &o->dec.num_bands);
    if (strcmp(a, "--global-candidates") == 0)
    {
        int v = 1;
        if (!next_int(argc, argv, i, &v))
            return false;
        o->dec.global_candidates = (v != 0);
        return true;
    }
    if (strcmp(a, "--fmin") == 0)
        return next_float(argc, argv, i, &o->dec.f_min_hz);
    if (strcmp(a, "--fmax") == 0)
        return next_float(argc, argv, i, &o->dec.f_max_hz);
    if (strcmp(a, "--subtract") == 0)
    {
        o->dec.enable_subtract = true;
        return true;
    }
    if (strcmp(a, "--llr-refine") == 0)
    {
        o->dec.enable_llr_refine = true;
        return true;
    }
    if (strcmp(a, "--block") == 0)
        return next_int(argc, argv, i, &o->dec.block_max_n);
    if (strcmp(a, "--block-min-n") == 0)
        return next_int(argc, argv, i, &o->dec.block_min_n);
    if (strcmp(a, "--block-skip-fast") == 0)
    {
        o->dec.block_skip_fast = true;
        return true;
    }
    if (strcmp(a, "--block-min-score") == 0)
        return next_int(argc, argv, i, &o->dec.block_min_score);
    if (strcmp(a, "--block-max-errors") == 0)
        return next_int(argc, argv, i, &o->dec.block_max_bp_errors);
    if (strcmp(a, "--block-candidates") == 0)
        return next_int(argc, argv, i, &o->dec.max_block_candidates);
    if (strcmp(a, "--ap-mode") == 0)
        return next_int(argc, argv, i, &o->dec.ap_mode);
    if (strcmp(a, "--my-call") == 0)
    {
        if (*i + 1 >= argc)
            return false;
        o->dec.my_call = argv[++(*i)];
        return true;
    }
    if (strcmp(a, "--his-call") == 0)
    {
        if (*i + 1 >= argc)
            return false;
        o->dec.his_call = argv[++(*i)];
        return true;
    }
    return false;
}

/* ------------------------------------------------------------------------- */
/* WAV 加载                                                                   */
/* ------------------------------------------------------------------------- */
static int cli_load_wav(const char* path, float** samples_out, int* num_out, int* rate_out)
{
    int capacity = MAX_LOAD_SAMPLES;
    float* samples = (float*)malloc((size_t)capacity * sizeof(float));
    if (!samples)
        return -1;

    int num = capacity;
    int rate = 0;
    int rc = load_wav(samples, &num, &rate, path);
    if (rc < 0)
    {
        free(samples);
        return rc;
    }
    *samples_out = samples;
    *num_out = num;
    *rate_out = rate;
    return 0;
}

/* ------------------------------------------------------------------------- */
/* 消息键规范化（与 ft8_lib/utils/run_tests.py 语义一致）                     */
/* ------------------------------------------------------------------------- */
static void normalize_token(const char* in, char* out, size_t cap)
{
    size_t len = strlen(in);
    if (len >= 2 && in[0] == '<' && in[len - 1] == '>')
    {
        snprintf(out, cap, "<...>");
        return;
    }
    snprintf(out, cap, "%s", in);
}

/// 从一段自由文本中提取比较键（最多 3 个 token）并大写化
static void make_key(const char* text, char* key, size_t cap)
{
    char buf[256];
    size_t bi = 0;
    for (const char* p = text; *p && bi + 1 < sizeof(buf); ++p)
        buf[bi++] = (char)toupper((unsigned char)*p);
    buf[bi] = '\0';

    char tok[3][32];
    int n = 0;
    char* save = NULL;
    for (char* t = strtok_r(buf, " \t\r\n", &save); t && n < 3; t = strtok_r(NULL, " \t\r\n", &save))
    {
        normalize_token(t, tok[n], sizeof(tok[n]));
        n++;
    }

    key[0] = '\0';
    for (int i = 0; i < n; ++i)
    {
        if (i)
            strncat(key, " ", cap - strlen(key) - 1);
        strncat(key, tok[i], cap - strlen(key) - 1);
    }
}

/// 从参考 txt 一行中取 "~" 之后的内容作为消息
static void parse_ref_line(const char* line, char* key, size_t cap)
{
    const char* tilde = strchr(line, '~');
    const char* msg = tilde ? tilde + 1 : line;
    make_key(msg, key, cap);
}

/// 从参考 txt 一行中取 SNR（第 2 个字段，单位 dB）
static int parse_ref_snr(const char* line)
{
    const char* p = line;
    while (*p && *p != ' ' && *p != '\t')
        ++p; /* 跳过 MMddHH */
    while (*p == ' ' || *p == '\t')
        ++p;
    return atoi(p);
}

/* ------------------------------------------------------------------------- */
/* decode 命令                                                                */
/* ------------------------------------------------------------------------- */
static int cmd_decode(int argc, char** argv)
{
    if (argc < 3)
    {
        fprintf(stderr, "用法: ft8_cli decode <wav> [选项]\n");
        return 2;
    }
    const char* wav = argv[2];
    options_t o;
    options_default(&o);
    for (int i = 3; i < argc; ++i)
    {
        if (!parse_option(argc, argv, &i, &o))
        {
            fprintf(stderr, "未知选项: %s\n", argv[i]);
            return 2;
        }
    }

    float* samples = NULL;
    int num = 0, rate = 0;
    if (cli_load_wav(wav, &samples, &num, &rate) != 0)
    {
        fprintf(stderr, "无法读取 WAV: %s\n", wav);
        return 1;
    }
    o.dec.sample_rate = rate;

    ft8_message_result_t results[256];
    double t0 = now_ms();
    int count = ft8_decode_slot(samples, num, &o.dec, results, 256);
    double t1 = now_ms();

    if (count < 0)
    {
        fprintf(stderr, "解码失败: %s\n", ft8_last_error());
        free(samples);
        return 1;
    }

    for (int i = 0; i < count && i < 256; ++i)
    {
        printf("%+4.0f %+5.1f %4.0f ~  %s",
               results[i].snr, results[i].dt, results[i].freq, results[i].text);
        if (results[i].block_n > 0)
            printf("  [N=%d]", results[i].block_n);
        if (results[i].ap_type > 0)
            printf("  [AP=%d]", results[i].ap_type);
        printf("\n");
    }
    fprintf(stderr, "[decode] %d 条, %.1f ms (threads=%d, osr=%dx%d, score>=%d)\n",
            count, t1 - t0, o.dec.num_threads, o.dec.time_osr, o.dec.freq_osr,
            o.dec.min_sync_score);

    free(samples);
    return 0;
}

/* ------------------------------------------------------------------------- */
/* gen 命令                                                                   */
/* ------------------------------------------------------------------------- */
static int cmd_gen(int argc, char** argv)
{
    if (argc < 4)
    {
        fprintf(stderr, "用法: ft8_cli gen <message> <out.wav> [freq]\n");
        return 2;
    }
    const char* message = argv[2];
    const char* out_path = argv[3];

    ft8_encode_config_t cfg;
    ft8_encode_config_default(&cfg);
    if (argc > 4)
        cfg.base_freq_hz = (float)atof(argv[4]);

    for (int i = 5; i < argc; ++i)
    {
        if (strcmp(argv[i], "--lead") == 0 && i + 1 < argc)
            cfg.lead_in_sec = (float)atof(argv[++i]);
        else if (strcmp(argv[i], "--tail") == 0 && i + 1 < argc)
            cfg.tail_sec = (float)atof(argv[++i]);
        else if (strcmp(argv[i], "--amp") == 0 && i + 1 < argc)
            cfg.amplitude = (float)atof(argv[++i]);
        else if (strcmp(argv[i], "--rate") == 0 && i + 1 < argc)
            cfg.sample_rate = atoi(argv[++i]);
    }

    int total = ft8_encode_message(message, &cfg, NULL, 0);
    if (total < 0)
    {
        fprintf(stderr, "编码失败: %s\n", ft8_last_error());
        return 1;
    }

    float* signal = (float*)malloc((size_t)total * sizeof(float));
    if (!signal)
        return 1;
    int written = ft8_encode_message(message, &cfg, signal, total);
    if (written < 0)
    {
        fprintf(stderr, "编码失败: %s\n", ft8_last_error());
        free(signal);
        return 1;
    }

    if (save_wav(signal, written, cfg.sample_rate, out_path) != 0)
    {
        fprintf(stderr, "无法写出 WAV: %s\n", out_path);
        free(signal);
        return 1;
    }

    printf("已生成 %s: %d 采样 @ %d Hz, %.2f s\n",
           out_path, written, cfg.sample_rate, (double)written / cfg.sample_rate);
    free(signal);
    return 0;
}

/* ------------------------------------------------------------------------- */
/* 目录列举                                                                   */
/* ------------------------------------------------------------------------- */
static int list_files(const char* dir, char*** out_names)
{
    char** names = NULL;
    int count = 0, cap = 0;

#ifdef _WIN32
    char pattern[1024];
    snprintf(pattern, sizeof(pattern), "%s\\*.wav", dir);
    WIN32_FIND_DATAA fd;
    HANDLE h = FindFirstFileA(pattern, &fd);
    if (h == INVALID_HANDLE_VALUE)
        return 0;
    do
    {
        if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)
            continue;
        if (count == cap)
        {
            cap = cap ? cap * 2 : 64;
            names = (char**)realloc(names, (size_t)cap * sizeof(char*));
        }
        size_t len = strlen(dir) + strlen(fd.cFileName) + 2;
        names[count] = (char*)malloc(len);
        snprintf(names[count], len, "%s/%s", dir, fd.cFileName);
        count++;
    } while (FindNextFileA(h, &fd));
    FindClose(h);
#else
    DIR* d = opendir(dir);
    if (!d)
        return 0;
    struct dirent* e;
    while ((e = readdir(d)) != NULL)
    {
        const char* dot = strrchr(e->d_name, '.');
        if (!dot || strcmp(dot, ".wav") != 0)
            continue;
        if (count == cap)
        {
            cap = cap ? cap * 2 : 64;
            names = (char**)realloc(names, (size_t)cap * sizeof(char*));
        }
        size_t len = strlen(dir) + strlen(e->d_name) + 2;
        names[count] = (char*)malloc(len);
        snprintf(names[count], len, "%s/%s", dir, e->d_name);
        count++;
    }
    closedir(d);
#endif

    *out_names = names;
    return count;
}

/* ------------------------------------------------------------------------- */
/* bench 命令                                                                 */
/* ------------------------------------------------------------------------- */
typedef struct
{
    double ms;
} timing_t;

static int cmp_double(const void* a, const void* b)
{
    double da = *(const double*)a, db = *(const double*)b;
    return (da > db) - (da < db);
}

static int cmd_bench(int argc, char** argv)
{
    if (argc < 3)
    {
        fprintf(stderr, "用法: ft8_cli bench <dir> [选项]\n");
        return 2;
    }
    const char* dir = argv[2];
    options_t o;
    options_default(&o);
    for (int i = 3; i < argc; ++i)
    {
        if (!parse_option(argc, argv, &i, &o))
        {
            fprintf(stderr, "未知选项: %s\n", argv[i]);
            return 2;
        }
    }

    char** files = NULL;
    int nfiles = list_files(dir, &files);
    if (nfiles <= 0)
    {
        fprintf(stderr, "目录中没有 .wav: %s\n", dir);
        return 1;
    }

    long total_expected = 0, total_matched = 0, total_missed = 0, total_extra = 0;
    double* times = (double*)malloc((size_t)nfiles * sizeof(double));
    int ntimed = 0;

    /* 诊断：FT8_SNR_DUMP 指向的文件写入匹配对的 (解码SNR, 参考SNR) */
    const char* dump_path = getenv("FT8_SNR_DUMP");
    FILE* fdump = dump_path ? fopen(dump_path, "w") : NULL;

    /* SNR 标定统计：对 (解码 SNR, 参考 SNR) 做线性回归 */
    double snr_sum_x = 0.0, snr_sum_y = 0.0;
    double snr_sum_xx = 0.0, snr_sum_xy = 0.0, snr_sum_yy = 0.0;
    long snr_calib_n = 0;

    for (int f = 0; f < nfiles; ++f)
    {
        const char* wav = files[f];
        size_t len = strlen(wav);
        char txt[1024];
        snprintf(txt, sizeof(txt), "%.*s.txt", (int)(len - 4), wav);

        FILE* ft = fopen(txt, "r");
        if (!ft)
        {
            printf("[skip] %s (无对照 txt)\n", wav);
            continue;
        }

        /* 读取期望键集合 */
        char* expected[256];
        int expected_snr[256];
        int nexp = 0;
        char line[512];
        while (fgets(line, sizeof(line), ft) && nexp < 256)
        {
            if (line[0] == '\n' || line[0] == '\0')
                continue;
            char key[128];
            parse_ref_line(line, key, sizeof(key));
            if (key[0] == '\0')
                continue;
            expected[nexp] = strdup(key);
            expected_snr[nexp] = parse_ref_snr(line);
            nexp++;
        }
        fclose(ft);

        float* samples = NULL;
        int num = 0, rate = 0;
        if (cli_load_wav(wav, &samples, &num, &rate) != 0)
        {
            fprintf(stderr, "[skip] 无法读取 %s\n", wav);
            for (int i = 0; i < nexp; ++i)
                free(expected[i]);
            continue;
        }
        o.dec.sample_rate = rate;

        ft8_message_result_t results[256];
        int count = 0;
        double best_ms = 1e30;
        for (int r = 0; r < (o.repeat > 0 ? o.repeat : 1); ++r)
        {
            double t0 = now_ms();
            count = ft8_decode_slot(samples, num, &o.dec, results, 256);
            double t1 = now_ms();
            if (t1 - t0 < best_ms)
                best_ms = t1 - t0;
        }
        if (count < 0)
        {
            fprintf(stderr, "[fail] %s: %s\n", wav, ft8_last_error());
            free(samples);
            for (int i = 0; i < nexp; ++i)
                free(expected[i]);
            continue;
        }
        times[ntimed++] = best_ms;

        /* 结果键集合 */
        char* got[256];
        float got_snr[256];
        int ngot = 0;
        for (int i = 0; i < count && i < 256; ++i)
        {
            char key[128];
            make_key(results[i].text, key, sizeof(key));
            if (key[0] == '\0')
                continue;
            got[ngot] = strdup(key);
            got_snr[ngot] = results[i].snr;
            ngot++;
        }

        int matched = 0, missed = 0, extra = 0;
        for (int i = 0; i < nexp; ++i)
        {
            int match_j = -1;
            for (int j = 0; j < ngot; ++j)
            {
                if (strcmp(expected[i], got[j]) == 0)
                {
                    match_j = j;
                    break;
                }
            }
            if (match_j >= 0)
            {
                matched++;
                double x = (double)got_snr[match_j];   /* 解码 SNR（当前标定） */
                double y = (double)expected_snr[i];     /* 参考 SNR */
                if (fdump)
                    fprintf(fdump, "%s\t%.4f %d %s\n", wav, x, expected_snr[i], expected[i]);
                snr_sum_x += x;
                snr_sum_y += y;
                snr_sum_xx += x * x;
                snr_sum_xy += x * y;
                snr_sum_yy += y * y;
                snr_calib_n++;
            }
            else
            {
                missed++;
            }
        }
        for (int j = 0; j < ngot; ++j)
        {
            bool found = false;
            for (int i = 0; i < nexp; ++i)
            {
                if (strcmp(expected[i], got[j]) == 0)
                {
                    found = true;
                    break;
                }
            }
            if (!found)
                extra++;
        }

        printf("%-40s %3d/%-3d  missed %2d  extra %2d  %6.1f ms\n",
               wav, matched, nexp, missed, extra, best_ms);

        total_expected += nexp;
        total_matched += matched;
        total_missed += missed;
        total_extra += extra;

        free(samples);
        for (int i = 0; i < nexp; ++i)
            free(expected[i]);
        for (int i = 0; i < ngot; ++i)
            free(got[i]);
    }

    printf("\n===== 汇总 =====\n");
    printf("文件数        : %d\n", nfiles);
    printf("期望消息数    : %ld\n", total_expected);
    printf("命中          : %ld\n", total_matched);
    printf("漏解 (missed) : %ld (%.1f%%)\n", total_missed,
           total_expected ? 100.0 * total_missed / total_expected : 0.0);
    printf("多余 (extra)  : %ld (%.1f%%)\n", total_extra,
           total_expected ? 100.0 * total_extra / total_expected : 0.0);
    printf("召回率 Recall : %.1f%%\n",
           total_expected ? 100.0 * total_matched / total_expected : 0.0);

    if (ntimed > 0)
    {
        qsort(times, (size_t)ntimed, sizeof(double), cmp_double);
        double sum = 0;
        for (int i = 0; i < ntimed; ++i)
            sum += times[i];
        printf("解码耗时      : avg %.1f ms, P50 %.1f ms, P95 %.1f ms, max %.1f ms\n",
               sum / ntimed, times[ntimed / 2], times[(int)(ntimed * 0.95)],
               times[ntimed - 1]);
    }

    if (snr_calib_n > 1)
    {
        double inv = 1.0 / snr_calib_n;
        double mx = snr_sum_x * inv;
        double my = snr_sum_y * inv;
        double sxx = snr_sum_xx * inv - mx * mx;
        double sxy = snr_sum_xy * inv - mx * my;
        double syy = snr_sum_yy * inv - my * my;
        double a = (sxx > 1e-9) ? (sxy / sxx) : 0.0;
        double b = my - a * mx;
        double r2 = (sxx > 1e-9 && syy > 1e-9) ? (sxy * sxy) / (sxx * syy) : 0.0;
        printf("SNR 标定      : snr_ref = %.3f*raw + %.2f, R2=%.3f (n=%ld)\n",
               a, b, r2, snr_calib_n);
    }

    if (fdump)
        fclose(fdump);

    for (int i = 0; i < nfiles; ++i)
        free(files[i]);
    free(files);
    free(times);
    return 0;
}

/* ------------------------------------------------------------------------- */
/* main                                                                       */
/* ------------------------------------------------------------------------- */
int main(int argc, char** argv)
{
    printf("ft8core %s\n", ft8_version());
    if (argc < 2)
    {
        fprintf(stderr,
                "用法:\n"
                "  ft8_cli decode <wav> [选项]\n"
                "  ft8_cli gen <message> <out.wav> [freq] [选项]\n"
                "  ft8_cli bench <dir> [选项]\n");
        return 2;
    }

    if (strcmp(argv[1], "decode") == 0)
        return cmd_decode(argc, argv);
    if (strcmp(argv[1], "gen") == 0)
        return cmd_gen(argc, argv);
    if (strcmp(argv[1], "bench") == 0)
        return cmd_bench(argc, argv);

    fprintf(stderr, "未知命令: %s\n", argv[1]);
    return 2;
}
