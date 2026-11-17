# HLS converter

`slimhls-converter-7e7f373dbf-mov-aac.aar` retains the Java wrapper and AAR metadata
from [slimhls-converter at 7e7f373dbfd97e5797fc374315e7da71910bf595](https://github.com/75ohmantenna/slimhls-converter/tree/7e7f373dbfd97e5797fc374315e7da71910bf595).
The four `libffmpeg.so` executables are rebuilt with the components described
below. The initial Android fMP4 failure is recorded with the
[integration results](https://github.com/75ohmantenna/pvcpipe/blob/e7cb3f6b25b55cc47b2b409e9b2f621fe9e311bb/docs/download-preparation.md#android-converter-integration-review--2026-10-30).

The executables use
[upstream FFmpeg commit 38353ad6c3f9912ac0912cfee7763fd18da4badb](https://github.com/FFmpeg/FFmpeg/tree/38353ad6c3f9912ac0912cfee7763fd18da4badb)
and NDK **25.2.9519653**. This source includes the
[MOV encryption metadata fixes for CVE-2026-107678](https://code.ffmpeg.org/FFmpeg/FFmpeg/pulls/24593).
The build disables networking and includes `--enable-demuxer=mov`,
`--enable-demuxer=aac` and `--enable-bsf=h264_mp4toannexb`.
Concat requires the H.264 framing filter when reading MP4 fragments. The build
supports MPEG-TS, fragmented MP4 and raw ADTS input, H.264 and AAC, MP4 output,
and the file and concat protocols. Both 64-bit ABIs use 16 KiB load-segment
alignment.
The wrapper is GPL-3.0; the minimal FFmpeg build reports LGPL-2.1-or-later.
The linked source trees and build recipe provide the corresponding sources.

The app uses this local artifact so ordinary builds do not need an NDK.
`make ci` verifies its SHA-256 before building:

```text
9314320010f1302cfdf5f55bd9107196b9fb281820eabee927f6d91e5febd997
```

Rebuild from a clean checkout with Python 3, Git, Make and the pinned NDK:

```sh
python3 tools/rebuild_hls_converter.py \
  --ndk /path/to/android-sdk/ndk/25.2.9519653 \
  --work /tmp/pvcpipe-hls-converter
```

`--source` and `--base-aar` accept verified local caches. By default, the checked-in
AAR supplies the unchanged wrapper; an original AAR is downloaded only when no
local artifact is available. The recipe checks the source commit and worktree
and the known AAR checksum before building all four
ABIs, then checks ELF alignment and preserves the non-native AAR entries.
FFmpeg embeds configure/toolchain paths, so changing those paths can change binary
hashes. A rebuild at different paths must be reviewed before replacing the pin.

The shipped native executable SHA-256 digests are:

| ABI | SHA-256 |
| --- | --- |
| armeabi-v7a | `9948ace40d7718f16dd74d1d43f39516ba991c8b71320bf3729285c02346f16a` |
| arm64-v8a | `66569d2eedf59d6197982d6b9940e52aa8c251e9ca411197f5e3407ef835307f` |
| x86 | `ee6745d61f28d8041afb3016f28e9f706aefff8f6a32b9fd53cb1a09b2b82ff4` |
| x86_64 | `fffe976a094c1b0428db786777238a9e6f1b967b2a8f707dacd179759556b12e` |

## AAC input follow-up — 2026-11-13

The raw AAC demuxer was the only native feature added in this revision. All four
ABIs were rebuilt with
[the earlier FFmpeg pin](https://github.com/evermind-zz/FFmpeg4slimhls-converter/tree/b4d9fa6cb93a3ef8209508b2fb9cd1dc95f03090)
and the same NDK. Non-native AAR entries were byte-identical to the previous
MOV-capable artifact. Executable growth was 4,552
bytes (ARMv7), 4,744 (ARM64), 4,984 (x86) and 4,992 (x86-64). Config checks retain
MPEG-TS/MOV/concat, MP4 output, AAC/H.264 and the existing bitstream filters;
no encoder or network protocol was added. The recipe verifies ELF alignment.
Host FFmpeg regression conversion is separate from Android executable/device
verification and does not by itself prove a packaged native binary works.

### ARM64 device verification (2026-11-13)

The ARM64 executable from the 2026-11-13 build
(`8952eb17d3f1438b65f19882ef9059b1a677b8953ac2f8f016b8dce13b6ff918`)
was tested on a physical Pixel 6a
(Android 17/API 37). Production-equivalent concat/copy, mandatory audio mapping,
`-vn`, AAC bitstream filtering and MP4 output converted synthetic manifest windows
for ADTS and fMP4 (8.021333 seconds each) and MPEG-TS (8.000000 seconds) into
AAC-only MP4 files with no video. fMP4 included its initialization map. No-audio
and Opus inputs were rejected with exit code 234. Isolated temporary device files
were removed and absence verified, without app data, UI or settings changes.
This is native-executable evidence, not full Java mission/service integration,
Android decoding or live SoundCloud verification.

## FFmpeg security update — 2026-11-17

The new AAR preserves all non-native entries byte for byte. All four binaries
converted MPEG-TS and fragmented MP4 fixture windows to H.264/AAC MP4 and
raw ADTS windows to AAC-only MP4. The x86 builds ran on an API 26 emulator.
The ARM builds ran on a Pixel 6a with Android 17. The checks ran the native
executables directly with temporary files and verified the output codecs and
durations on the host. No app package was installed or changed for these checks.
