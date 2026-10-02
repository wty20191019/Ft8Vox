/**
 * @file ft8_thread.c
 * @brief 极简线程/互斥量实现：Android 走 POSIX，Windows 主机走 Win32。
 */
#include "ft8_thread.h"

#include <stdlib.h>

#if defined(_WIN32)

#include <windows.h>

struct ft8_thread
{
    HANDLE handle;
};

/* 参数结构需要存活到线程启动，由 trampoline 自行释放 */
struct ft8_win_thread_arg
{
    ft8_thread_fn fn;
    void* arg;
};

static DWORD WINAPI ft8_thread_trampoline(LPVOID param)
{
    struct ft8_win_thread_arg* a = (struct ft8_win_thread_arg*)param;
    a->fn(a->arg);
    free(a);
    return 0;
}

ft8_thread_t* ft8_thread_create(ft8_thread_fn fn, void* arg)
{
    ft8_thread_t* thread = (ft8_thread_t*)malloc(sizeof(ft8_thread_t));
    if (!thread)
        return NULL;

    struct ft8_win_thread_arg* a = (struct ft8_win_thread_arg*)malloc(sizeof(struct ft8_win_thread_arg));
    if (!a)
    {
        free(thread);
        return NULL;
    }
    a->fn = fn;
    a->arg = arg;

    thread->handle = CreateThread(NULL, 0, ft8_thread_trampoline, a, 0, NULL);
    if (!thread->handle)
    {
        free(a);
        free(thread);
        return NULL;
    }
    return thread;
}

void ft8_thread_join(ft8_thread_t* thread)
{
    if (thread && thread->handle)
    {
        WaitForSingleObject(thread->handle, INFINITE);
    }
}

void ft8_thread_free(ft8_thread_t* thread)
{
    if (!thread)
        return;
    if (thread->handle)
        CloseHandle(thread->handle);
    free(thread);
}

struct ft8_mutex
{
    CRITICAL_SECTION cs;
};

ft8_mutex_t* ft8_mutex_create(void)
{
    ft8_mutex_t* m = (ft8_mutex_t*)malloc(sizeof(ft8_mutex_t));
    if (!m)
        return NULL;
    InitializeCriticalSection(&m->cs);
    return m;
}

void ft8_mutex_lock(ft8_mutex_t* m)
{
    if (m)
        EnterCriticalSection(&m->cs);
}

void ft8_mutex_unlock(ft8_mutex_t* m)
{
    if (m)
        LeaveCriticalSection(&m->cs);
}

void ft8_mutex_free(ft8_mutex_t* m)
{
    if (!m)
        return;
    DeleteCriticalSection(&m->cs);
    free(m);
}

int ft8_cpu_count(void)
{
    DWORD n = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS);
    return (n > 0) ? (int)n : 1;
}

#else /* POSIX (Android / Linux / macOS) */

#include <pthread.h>
#include <unistd.h>

struct ft8_thread
{
    pthread_t handle;
};

/* 参数需要存活到线程启动；用一个堆结构传递 */
struct ft8_thread_arg
{
    ft8_thread_fn fn;
    void* arg;
    struct ft8_thread* self;
};

static void* ft8_thread_trampoline(void* param)
{
    struct ft8_thread_arg* a = (struct ft8_thread_arg*)param;
    a->fn(a->arg);
    free(a);
    return NULL;
}

ft8_thread_t* ft8_thread_create(ft8_thread_fn fn, void* arg)
{
    ft8_thread_t* thread = (ft8_thread_t*)malloc(sizeof(ft8_thread_t));
    if (!thread)
        return NULL;

    struct ft8_thread_arg* a = (struct ft8_thread_arg*)malloc(sizeof(struct ft8_thread_arg));
    if (!a)
    {
        free(thread);
        return NULL;
    }
    a->fn = fn;
    a->arg = arg;

    if (pthread_create(&thread->handle, NULL, ft8_thread_trampoline, a) != 0)
    {
        free(a);
        free(thread);
        return NULL;
    }
    return thread;
}

void ft8_thread_join(ft8_thread_t* thread)
{
    if (thread)
    {
        pthread_join(thread->handle, NULL);
    }
}

void ft8_thread_free(ft8_thread_t* thread)
{
    free(thread);
}

struct ft8_mutex
{
    pthread_mutex_t m;
};

ft8_mutex_t* ft8_mutex_create(void)
{
    ft8_mutex_t* m = (ft8_mutex_t*)malloc(sizeof(ft8_mutex_t));
    if (!m)
        return NULL;
    pthread_mutex_init(&m->m, NULL);
    return m;
}

void ft8_mutex_lock(ft8_mutex_t* m)
{
    if (m)
        pthread_mutex_lock(&m->m);
}

void ft8_mutex_unlock(ft8_mutex_t* m)
{
    if (m)
        pthread_mutex_unlock(&m->m);
}

void ft8_mutex_free(ft8_mutex_t* m)
{
    if (!m)
        return;
    pthread_mutex_destroy(&m->m);
    free(m);
}

int ft8_cpu_count(void)
{
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    return (n > 0) ? (int)n : 1;
}

#endif
