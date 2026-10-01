#!/usr/bin/env python3
"""跑 ft8_lib/test/wav 语料，统计解码 recall 与假阳性。

与 ft8_lib/utils/run_tests.py 同口径（同样是 set 比对、忽略 SNR/DT），
区别是：递归整个测试目录、调用宿主机 decode_cli、可透传解码参数。

用法：
    python tools/host_decode/bench.py [--root DIR] [--exe PATH] [--args "..."] [--quiet]

默认 root = app/src/main/cpp/ft8_lib/test/wav
默认 exe  = tools/host_decode/build/decode_cli.exe
只有同时存在同名 .txt 的 .wav 才计入统计（语料里 7 个 6.4 kHz 的 websdr_test14..20 没有 .txt）。
"""

import argparse
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))


def parse(line):
    """照搬 ft8_lib/utils/run_tests.py 的解析：取 freq/dest/source/report。"""
    fields = line.strip().split()
    if len(fields) < 5:
        return None
    freq = fields[3]
    dest = fields[5] if len(fields) > 5 else ""
    source = fields[6] if len(fields) > 6 else ""
    report = fields[7] if len(fields) > 7 else ""
    if dest and dest[0] == "<" and dest[-1] == ">":
        dest = "<...>"
    if source and source[0] == "<" and source[-1] == ">":
        source = "<...>"
    return " ".join([dest, source, report])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.join(REPO, "app", "src", "main", "cpp", "ft8_lib", "test", "wav"))
    ap.add_argument("--exe", default=os.path.join(HERE, "build", "decode_cli.exe"))
    ap.add_argument("--args", default="", help="透传给 decode_cli 的额外参数，如 '--deep --ldpc 20'")
    ap.add_argument("--quiet", action="store_true", help="只打印总计")
    opt = ap.parse_args()

    if not os.path.isfile(opt.exe):
        print("找不到解码器：%s（先跑 tools/host_decode/build.cmd）" % opt.exe, file=sys.stderr)
        return 2

    cases = []
    for dirpath, _dirs, files in os.walk(opt.root):
        for name in files:
            if not name.endswith(".wav"):
                continue
            wav = os.path.join(dirpath, name)
            txt = wav[:-4] + ".txt"
            if os.path.isfile(txt):
                cases.append((wav, txt))
    cases.sort()

    extra_args = opt.args.split()

    n_total = n_decoded = n_extra = n_missed = 0
    for wav, txt in cases:
        cmd = [opt.exe, wav] + extra_args
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        out = proc.stdout.decode("utf-8", errors="replace").splitlines()
        got = {parse(x) for x in out if x.strip()}
        got.discard(None)
        exp = {parse(x) for x in open(txt, encoding="utf-8", errors="replace").read().splitlines() if x.strip()}
        exp.discard(None)

        extra = got - exp
        missed = exp - got
        n_total += len(exp)
        n_decoded += len(got & exp)
        n_extra += len(extra)
        n_missed += len(missed)

        if (extra or missed) and not opt.quiet:
            rel = os.path.relpath(wav, opt.root)
            print("%-28s %2d/%2d" % (rel, len(got & exp), len(exp)))
            if extra:
                print("    extra :", sorted(extra))
            if missed:
                print("    missed:", sorted(missed))

    recall = (n_total - n_missed) / n_total if n_total else 0.0
    print("files=%d expected=%d hit=%d extra=%d missed=%d recall=%.1f%%"
          % (len(cases), n_total, n_decoded, n_extra, n_missed, 100.0 * recall))
    return 0


if __name__ == "__main__":
    sys.exit(main())
