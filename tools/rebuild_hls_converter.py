#!/usr/bin/env python3
"""Rebuild the pinned converter with MP4 input support; see app/libs/README.md."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request
import zipfile


FFMPEG_COMMIT = "b4d9fa6cb93a3ef8209508b2fb9cd1dc95f03090"
FFMPEG_REPO = "https://github.com/evermind-zz/FFmpeg4slimhls-converter.git"
BASE_URL = ("https://jitpack.io/com/github/75ohmantenna/slimhls-converter/"
            "7e7f373dbf/slimhls-converter-7e7f373dbf.aar")
BASE_SHA256 = "1ea205c289effc445acacaa1fa00f2e564c001e9ccc155e5201168065b13e01a"
VENDORED_SHA256 = "2743910cd6d4bc5999d8aa7de6ccdb6249bde0d3c7b50335e71fa46972d52074"
ARCHITECTURES = {
    "armeabi-v7a": ("arm", "armv7a-linux-androideabi", 19),
    "arm64-v8a": ("arm64", "aarch64-linux-android", 21),
    "x86": ("x86", "i686-linux-android", 19),
    "x86_64": ("x86_64", "x86_64-linux-android", 21),
}


def run(*command, cwd=None):
    subprocess.run([str(part) for part in command], cwd=cwd, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Verify the checked-in AAR checksum")
    parser.add_argument("--ndk", type=Path,
                        help="Android NDK 25.2.9519653 directory")
    parser.add_argument("--work", type=Path,
                        help="Disposable build/cache directory")
    parser.add_argument("--source", type=Path, help="Existing clean checkout of the pinned source")
    parser.add_argument("--base-aar", type=Path, help="Cached original or vendored AAR (verified)")
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--output", type=Path, default=Path(
        "app/libs/slimhls-converter-7e7f373dbf-mov.aar"))
    args = parser.parse_args()
    if args.verify:
        if hashlib.sha256(args.output.read_bytes()).hexdigest() != VENDORED_SHA256:
            parser.error("Vendored converter AAR checksum mismatch")
        print("Verified pinned HLS converter AAR (four ABIs, MOV/MP4 input)")
        return
    if args.ndk is None or args.work is None:
        parser.error("Rebuilding requires --ndk and --work")
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    ndk = args.ndk.resolve()
    if not re.search(r"Pkg.Revision\s*=\s*25\.2\.9519653\s*$",
                     (ndk / "source.properties").read_text(), re.M):
        parser.error("Use the pinned NDK 25.2.9519653")
    hosts = list((ndk / "toolchains/llvm/prebuilt").glob("*"))
    if len(hosts) != 1:
        parser.error("Expected one NDK host toolchain")
    toolchain = hosts[0] / "bin"
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    source = args.source.resolve() if args.source else work / "source"
    if not source.exists():
        run("git", "init", source)
        run("git", "-C", source, "fetch", "--depth=1", FFMPEG_REPO, FFMPEG_COMMIT)
        run("git", "-C", source, "checkout", "--detach", "FETCH_HEAD")
    head = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"],
                                   text=True).strip()
    dirty = subprocess.check_output(["git", "-C", str(source), "status", "--porcelain"],
                                    text=True).strip()
    if head != FFMPEG_COMMIT or dirty:
        parser.error("FFmpeg source must be clean and match the pinned commit")
    base = args.base_aar.resolve() if args.base_aar else (
        args.output.resolve() if args.output.is_file() else work / "base.aar")
    if not base.exists():
        with urllib.request.urlopen(BASE_URL) as response, base.open("wb") as output:
            shutil.copyfileobj(response, output)
    if hashlib.sha256(base.read_bytes()).hexdigest() not in (BASE_SHA256, VENDORED_SHA256):
        parser.error("Converter AAR checksum mismatch")
    binaries = {}
    for abi, (arch, prefix, api) in ARCHITECTURES.items():
        build = work / abi
        build.mkdir(exist_ok=True)
        compiler = toolchain / f"{prefix}{api}-clang"
        flags = "-Wl,--gc-sections -s"
        if abi in ("arm64-v8a", "x86_64"):
            flags += " -Wl,-z,max-page-size=16384"
        run(source / "configure", f"--prefix={build / 'install'}", "--target-os=android",
            f"--arch={arch}", "--disable-everything", "--disable-zlib", "--disable-doc",
            "--disable-avdevice", "--disable-swscale", "--disable-encoders", "--disable-filters",
            "--disable-indevs", "--disable-outdevs", "--disable-debug", "--disable-autodetect",
            "--disable-asm", "--enable-avformat", "--enable-avcodec", "--enable-avutil",
            "--enable-swresample", "--enable-decoder=h264", "--enable-decoder=aac",
            "--enable-demuxer=mpegts", "--enable-demuxer=concat", "--enable-demuxer=mov",
            "--enable-muxer=mp4", "--enable-parser=h264", "--enable-parser=aac",
            "--enable-protocol=file", "--enable-protocol=concat", "--enable-bsf=aac_adtstoasc",
            "--enable-bsf=h264_mp4toannexb",
            "--enable-ffmpeg", "--enable-small", "--extra-cflags=-Os -fdata-sections -ffunction-sections",
            f"--extra-ldflags={flags}", "--enable-cross-compile", f"--cc={compiler}",
            f"--cxx={toolchain / (prefix + str(api) + '-clang++')}",
            f"--ar={toolchain / 'llvm-ar'}", f"--as={compiler}", f"--ld={compiler}",
            f"--nm={toolchain / 'llvm-nm'}", f"--strip={toolchain / 'llvm-strip'}",
            f"--ranlib={toolchain / 'llvm-ranlib'}", f"--sysroot={hosts[0] / 'sysroot'}", cwd=build)
        run("make", f"-j{args.jobs}", "ffmpeg", cwd=build)
        run(toolchain / "llvm-strip", build / "ffmpeg")
        headers = subprocess.check_output([str(toolchain / "llvm-readelf"), "-lW",
                                           str(build / "ffmpeg")], text=True)
        loads = [line for line in headers.splitlines() if line.strip().startswith("LOAD ")]
        if not loads or (abi in ("arm64-v8a", "x86_64")
                         and any(int(line.split()[-1], 16) < 16384 for line in loads)):
            raise RuntimeError(f"Invalid ELF alignment for {abi}")
        binaries[f"jni/{abi}/libffmpeg.so"] = (build / "ffmpeg").read_bytes()
        print(abi, hashlib.sha256(binaries[f"jni/{abi}/libffmpeg.so"]).hexdigest(), flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(".tmp")
    with zipfile.ZipFile(base) as original, zipfile.ZipFile(temporary, "w") as rebuilt:
        native_entries = {entry.filename for entry in original.infolist()
                          if entry.filename.endswith("/libffmpeg.so")}
        if native_entries != set(binaries):
            raise RuntimeError("Original AAR has unexpected FFmpeg ABIs")
        for entry in original.infolist():
            if entry.filename not in binaries:
                rebuilt.writestr(entry, original.read(entry))
        for name, content in sorted(binaries.items()):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100755 << 16
            rebuilt.writestr(entry, content)
    os.replace(temporary, args.output)
    print(args.output, hashlib.sha256(args.output.read_bytes()).hexdigest(), flush=True)


if __name__ == "__main__":
    main()
