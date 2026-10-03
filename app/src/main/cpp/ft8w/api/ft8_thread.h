/**
 * @file ft8_thread.h
 * @brief 极简线程/互斥量抽象，屏蔽 POSIX 与 Win32 差异。
 */
#ifndef FT8_THREAD_H
#define FT8_THREAD_H

#ifdef __cplusplus
extern "C"
{
#endif

typedef struct ft8_thread ft8_thread_t;
typedef void (*ft8_thread_fn)(void* arg);

/** 创建线程，成功返回句柄，失败返回 NULL。 */
ft8_thread_t* ft8_thread_create(ft8_thread_fn fn, void* arg);

/** 等待线程结束。 */
void ft8_thread_join(ft8_thread_t* thread);

/** 释放线程句柄（join 之后调用）。 */
void ft8_thread_free(ft8_thread_t* thread);

typedef struct ft8_mutex ft8_mutex_t;

/** 创建互斥量。 */
ft8_mutex_t* ft8_mutex_create(void);
void ft8_mutex_lock(ft8_mutex_t* mutex);
void ft8_mutex_unlock(ft8_mutex_t* mutex);
void ft8_mutex_free(ft8_mutex_t* mutex);

/** 返回当前逻辑 CPU 核数（至少 1）。 */
int ft8_cpu_count(void);

#ifdef __cplusplus
}
#endif

#endif /* FT8_THREAD_H */
