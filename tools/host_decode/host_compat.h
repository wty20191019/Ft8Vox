// 宿主构建专用兼容层（**不参与 Android 构建**）。
//
// 通过 gcc 的 -include 强制包含进每个编译单元（见 build.py）。
// 目前只处理一件事：mingw-w64 的 <string.h> 不声明 POSIX 的 stpcpy，
// 而 message.c 用到了它（Android/bionic 默认可见）。这里用宏重定向到自带实现，
// 避免依赖具体 UCRT 版本是否导出该符号。
#ifndef FT8VOX_HOST_COMPAT_H
#define FT8VOX_HOST_COMPAT_H

#include <string.h>

#if defined(__MINGW32__) || defined(__MINGW64__)
static inline char* host_stpcpy(char* dst, const char* src)
{
    while ((*dst = *src) != '\0')
    {
        ++dst;
        ++src;
    }
    return dst;
}
#define stpcpy host_stpcpy
#endif

#endif // FT8VOX_HOST_COMPAT_H
