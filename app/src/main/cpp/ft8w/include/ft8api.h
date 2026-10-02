/**
 * @file ft8api.h
 * @brief FT8 编解码 C 层对外统一 API。
 *
 * 设计目标：
 *  - 对上层（JNI / Kotlin / CLI）只暴露本头文件；
 *  - 所有影响编解码行为的参数集中在配置结构体中；
 *  - 提供「一次性整时隙解码」与「流式会话解码」两套接口；
 *  - 输入输出使用扁平结构体，便于 JNI 零解析封送。
 *
 * 许可证：本库基于 ft8_lib（MIT, Copyright (c) 2018 Kārlis Goba）改造，
 * 详见工程根目录 NOTICE。
 */
#ifndef FT8API_H
#define FT8API_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C"
{
#endif

/* ------------------------------------------------------------------------- */
/* 版本与常量                                                                 */
/* ------------------------------------------------------------------------- */

#define FT8API_VERSION_MAJOR 0
#define FT8API_VERSION_MINOR 1
#define FT8API_VERSION_PATCH 0

/** 单条解码文本最大长度（含结尾 NUL） */
#define FT8API_MAX_TEXT 48
/** 单条消息最大字段数 */
#define FT8API_MAX_FIELDS 3
/** FT8 音调数 */
#define FT8API_NUM_TONES 79

/** 返回码 */
typedef enum
{
    FT8_OK            = 0,
    FT8_ERR_ARG       = -1, /**< 参数非法 */
    FT8_ERR_NOMEM     = -2, /**< 内存不足 */
    FT8_ERR_IO        = -3, /**< 输入输出错误 */
    FT8_ERR_NO_OUT    = -4, /**< 输出缓冲不足 */
    FT8_ERR_ENCODE    = -5, /**< 消息无法打包 */
    FT8_ERR_STATE     = -6, /**< 会话状态错误 */
} ft8_error_t;

/* ------------------------------------------------------------------------- */
/* 解码配置                                                                   */
/* ------------------------------------------------------------------------- */

/**
 * 解码配置。所有字段均可通过 JNI 从 Kotlin 暴露给用户界面。
 * 建议先用 ft8_decode_config_default() 填充默认值，再覆盖需要的字段。
 */
typedef struct
{
    /* --- DSP / 瀑布 --- */
    float f_min_hz;        /**< 分析频率下限 (Hz)，默认 200 */
    float f_max_hz;        /**< 分析频率上限 (Hz)，默认 3000 */
    int   sample_rate;     /**< 采样率 (Hz)，默认 12000 */
    int   time_osr;        /**< 时间过采样 1/2/4，默认 2（越大越精细越慢） */
    int   freq_osr;        /**< 频率过采样 1/2/4，默认 2 */

    /* --- 候选搜索 --- */
    int   max_candidates;  /**< 最大候选数，默认 140 */
    int   min_sync_score;  /**< Costas 同步最低分阈值，默认 10（越低越灵敏越慢） */
    int   ldpc_iterations; /**< LDPC 迭代上限，默认 25 */

    /* --- 增强解码（P1/P2 逐步启用） --- */
    int   decode_depth;    /**< 多遍信号消除的遍数（P1）：≤0 时启用消除默认 3；默认 0 */
    int   ap_mode;         /**< AP 提示位掩码，0=关闭；默认 0 */
    const char* my_call;   /**< 本台呼号（AP 用，可空） */
    const char* his_call;  /**< 对方呼号（AP 用，可空） */
    const char* his_grid;  /**< 对方网格（AP 用，可空） */
    bool  enable_subtract; /**< 是否启用多遍信号消除，默认 false */

    /* --- 执行 --- */
    int   num_threads;         /**< 解码线程数，0/1=串行，>1=并行；默认 1 */
    bool  return_duplicates;   /**< 是否返回重复消息，默认 false */

    /* --- P2 新增（追加于末尾，保持既有字段偏移/ JNI 兼容） --- */
    int   osd_depth;           /**< OSD 兜底译码阶数：0=关闭，1..3=翻转深度；默认 2 */
    bool  enable_llr_refine;   /**< P2.3：BP 失败候选用时域精化 LLR 重解；默认 false */
} ft8_decode_config_t;

/** 用默认值填充解码配置。 */
void ft8_decode_config_default(ft8_decode_config_t* cfg);

/* ------------------------------------------------------------------------- */
/* 解码输出                                                                   */
/* ------------------------------------------------------------------------- */

/** 单条解码结果。 */
typedef struct
{
    char     text[FT8API_MAX_TEXT]; /**< 解码文本，NUL 结尾 */
    float    snr;                   /**< 估计信噪比 (dB) */
    float    dt;                    /**< 相对时隙起点的时间偏移 (s) */
    float    freq;                  /**< 音频频率 (Hz) */
    int      score;                 /**< Costas 同步分 */
    int      ldpc_errors;           /**< LDPC 残余错误数，0 表示通过 */
    int      pass;                  /**< 第几遍解出（0 基） */
    int      ap_type;               /**< AP 类型，0 表示未使用 AP */
    uint16_t crc;                   /**< CRC-14 值（可用于去重/哈希） */
} ft8_message_result_t;

/* ------------------------------------------------------------------------- */
/* 一次性解码                                                                 */
/* ------------------------------------------------------------------------- */

/**
 * 对一整段音频（通常为一个 15s 时隙）执行解码，阻塞直到完成。
 *
 * @param samples     单声道浮点音频，范围约 [-1, 1]
 * @param num_samples 采样点数
 * @param cfg         解码配置（不可为 NULL）
 * @param out         结果数组（可为 NULL 仅查询条数）
 * @param max_out     out 数组容量
 * @return 解码到的消息条数(>=0)；负数见 ft8_error_t
 */
int ft8_decode_slot(const float* samples, int num_samples,
                    const ft8_decode_config_t* cfg,
                    ft8_message_result_t* out, int max_out);

/* ------------------------------------------------------------------------- */
/* 流式会话解码（实时音频）                                                   */
/* ------------------------------------------------------------------------- */

typedef struct ft8_decode_session ft8_decode_session_t;

/** 创建流式解码会话（内部会拷贝 cfg 及其字符串字段）。 */
ft8_decode_session_t* ft8_decode_session_create(const ft8_decode_config_t* cfg);

/** 复位会话，复用缓冲区准备下一个时隙。 */
void ft8_decode_session_reset(ft8_decode_session_t* session);

/**
 * 送入一段音频（长度任意）。返回已累积的采样点数，负数为错误。
 * 内部按符号块组织并做 STFT。
 */
int ft8_decode_session_feed(ft8_decode_session_t* session,
                            const float* samples, int num_samples);

/**
 * 时隙结束时调用，对已累积的音频执行解码。
 * @return 解码到的消息条数(>=0)；负数见 ft8_error_t
 */
int ft8_decode_session_finalize(ft8_decode_session_t* session,
                                ft8_message_result_t* out, int max_out);

/** 释放会话。 */
void ft8_decode_session_free(ft8_decode_session_t* session);

/* ------------------------------------------------------------------------- */
/* 编码                                                                       */
/* ------------------------------------------------------------------------- */

/** 编码配置。 */
typedef struct
{
    int   sample_rate;   /**< 波形采样率 (Hz)，默认 12000 */
    float base_freq_hz;  /**< 音调 0 的音频基准频率 (Hz)，默认 1000 */
    float amplitude;     /**< 幅度 0..1，默认 0.5 */
    float symbol_bt;     /**< GFSK 平滑系数，FT8 默认 2.0 */
    float lead_in_sec;   /**< 前导静音秒数（VOX 起控保护），默认 0 */
    float tail_sec;      /**< 尾部静音秒数（VOX 释放保护），默认 0 */
} ft8_encode_config_t;

/** 用默认值填充编码配置。 */
void ft8_encode_config_default(ft8_encode_config_t* cfg);

/**
 * 将文本消息编码为单声道浮点音频。
 *
 * @param message     文本消息（如 "CQ JA1ABC PM95"）
 * @param cfg         编码配置（不可为 NULL）
 * @param out         输出缓冲；为 NULL 时函数返回所需采样点数
 * @param max_samples out 缓冲容量
 * @return 实际（或所需）采样点数(>=0)；负数见 ft8_error_t
 */
int ft8_encode_message(const char* message, const ft8_encode_config_t* cfg,
                       float* out, int max_samples);

/**
 * 仅将文本消息编码为 79 个 FSK 音调（0..7），用于调试/校验。
 * @param tones 至少 FT8API_NUM_TONES 字节
 * @return FT8_OK 或错误码
 */
int ft8_encode_tones(const char* message, uint8_t* tones);

/* ------------------------------------------------------------------------- */
/* 呼号哈希表（解码非标准呼号时使用，可跨时隙保留）                           */
/* ------------------------------------------------------------------------- */

/** 清空呼号哈希表。 */
void ft8_hash_clear(void);

/* ------------------------------------------------------------------------- */
/* 其它                                                                       */
/* ------------------------------------------------------------------------- */

/** 返回库版本字符串，如 "0.1.0"。 */
const char* ft8_version(void);

/** 返回最近一次错误的可读描述。 */
const char* ft8_last_error(void);

#ifdef __cplusplus
}
#endif

#endif /* FT8API_H */
