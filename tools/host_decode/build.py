#!/usr/bin/env python3
"""构建宿主机解码 CLI（开发基准工具，不参与 Android 构建）。

需要一个支持 C11/VLA 的 gcc 或 clang；MSVC 不支持 VLA，不能用。
Windows 上推荐 w64devkit（免安装、解压即用）。

用法：
    python tools/host_decode/build.py [--gcc PATH]

不传 --gcc 时依次尝试：环境变量 W64DEVKIT\\bin\\gcc.exe -> PATH 里的 gcc。
"""

import argparse
import glob
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
CPP = os.path.join(REPO, "app", "src", "main", "cpp")

SOURCES = [
    os.path.join(HERE, "decode_cli.c"),
    os.path.join(CPP, "ftx_session.c"),
    os.path.join(CPP, "ft8_lib", "ft8", "decode.c"),
    os.path.join(CPP, "ft8_lib", "ft8", "encode.c"),
    os.path.join(CPP, "ft8_lib", "ft8", "message.c"),
    os.path.join(CPP, "ft8_lib", "ft8", "ldpc.c"),
    os.path.join(CPP, "ft8_lib", "ft8", "crc.c"),
    os.path.join(CPP, "ft8_lib", "ft8", "constants.c"),
    os.path.join(CPP, "ft8_lib", "ft8", "text.c"),
    os.path.join(CPP, "ft8_lib", "common", "monitor.c"),
    os.path.join(CPP, "ft8_lib", "fft", "kiss_fft.c"),
    os.path.join(CPP, "ft8_lib", "fft", "kiss_fftr.c"),
]
# JTDX 移植模块（(174,91) LDPC BP/OSD 等）；用 glob 便于后续新增文件自动纳入。
SOURCES += sorted(glob.glob(os.path.join(CPP, "jtdx", "*.c")))


def find_gcc(explicit):
    if explicit:
        return explicit
    env = os.environ.get("W64DEVKIT")
    if env:
        candidate = os.path.join(env, "bin", "gcc.exe")
        if os.path.isfile(candidate):
            return candidate
    return shutil.which("gcc") or shutil.which("clang")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gcc", default=None, help="gcc/clang 可执行文件路径")
    opt = ap.parse_args()

    gcc = find_gcc(opt.gcc)
    if not gcc:
        print("找不到 gcc/clang：用 --gcc 指定，或设 W64DEVKIT 环境变量", file=sys.stderr)
        return 2

    out_dir = os.path.join(HERE, "build")
    os.makedirs(out_dir, exist_ok=True)
    exe = os.path.join(out_dir, "decode_cli.exe")

    cmd = [
        gcc, "-O2", "-std=c11", "-Wall", "-Wextra",
        # message.c 用了 POSIX 的 stpcpy；Android/bionic 默认可见，宿主 mingw 需兼容层补上。
        "-D_GNU_SOURCE",
        "-include", os.path.join(HERE, "host_compat.h"),
        "-I" + CPP,
        "-I" + os.path.join(CPP, "ft8_lib"),
        "-I" + os.path.join(CPP, "ft8_lib", "ft8"),
        "-I" + os.path.join(CPP, "jtdx"),
    ] + SOURCES + ["-lm", "-o", exe]

    print("gcc:", gcc)
    subprocess.run(cmd, check=True)
    print("OK:", exe)
    return 0


if __name__ == "__main__":
    sys.exit(main())
