# HLS converter

`slimhls-converter-7e7f373dbf-mov-aac.aar` retains the Java wrapper and AAR metadata
from [slimhls-converter at 7e7f373dbfd97e5797fc374315e7da71910bf595](https://github.com/75ohmantenna/slimhls-converter/tree/7e7f373dbfd97e5797fc374315e7da71910bf595).
Only its four `libffmpeg.so` executables are replaced. The original binaries had
MPEG-TS and concat demuxers but no MOV/MP4 demuxer: real Android fMP4 downloads
failed with exit status 183 even though host FFmpeg conversion passed.

The rebuilt executables use the same
[FFmpeg source commit b4d9fa6cb93a3ef8209508b2fb9cd1dc95f03090](https://github.com/evermind-zz/FFmpeg4slimhls-converter/tree/b4d9fa6cb93a3ef8209508b2fb9cd1dc95f03090)
and NDK **25.2.9519653**, adding `--enable-demuxer=mov`, `--enable-demuxer=aac` and
`--enable-bsf=h264_mp4toannexb`.
Concat requires the H.264 framing filter when reading MP4 fragments. MPEG-TS/concat and raw ADTS AAC input,
H.264/AAC, MP4 output and file/concat protocols remain enabled. Both 64-bit ABIs
retain 16 KiB load-segment alignment. No new network protocol or encoder is enabled.
The wrapper is GPL-3.0; the minimal FFmpeg build reports LGPL-2.1-or-later.
The linked source trees and build recipe provide the corresponding sources.

The app uses this local artifact so ordinary builds do not need an NDK.
`make ci` verifies its SHA-256 before building:

```text
bfb1710ee391b4862cda0d56395a505d32c15935c58b8e9cc5ae671ec6848307
```

Rebuild from a clean checkout with Python 3, Git, Make and the pinned NDK:

```sh
python3 tools/rebuild_hls_converter.py \
  --ndk /path/to/android-sdk/ndk/25.2.9519653 \
  --work /tmp/pvcpipe-hls-converter
```

`--source` and `--base-aar` accept verified local caches. By default, the checked-in
AAR supplies the unchanged wrapper; an original AAR is downloaded only when no
local artifact is available. The recipe checks the source commit/cleanliness
and the known AAR checksum before building all four
ABIs, then checks ELF alignment and preserves the non-native AAR entries.
FFmpeg embeds configure/toolchain paths, so changing those paths can change binary
hashes. A rebuild at different paths must be reviewed before replacing the pin.

The shipped native executable SHA-256 digests are:

| ABI | SHA-256 |
| --- | --- |
| armeabi-v7a | `d95154c4a7962345b7ea6ea7ee2a615d6613b3f15ac26866e7e4d4b326c478c4` |
| arm64-v8a | `8952eb17d3f1438b65f19882ef9059b1a677b8953ac2f8f016b8dce13b6ff918` |
| x86 | `9c172522785137d139201438475136b35fca8feae42f91504a7a1f80f6c01f24` |
| x86_64 | `0f8211ed943f49dc462f023262fde1ce80224bf95e2af528e5a78c7cbd762819` |

## AAC input follow-up — 2026-11-13

The raw AAC demuxer is the only native feature added in this revision. All four
ABIs were rebuilt with the pinned source and NDK; non-native AAR entries remain
byte-identical to the previous MOV-capable artifact. Executable growth is 4,552
bytes (ARMv7), 4,744 (ARM64), 4,984 (x86) and 4,992 (x86-64). Config checks retain
MPEG-TS/MOV/concat, MP4 output, AAC/H.264 and the existing bitstream filters;
no encoder or network protocol was added. The recipe verifies ELF alignment.
Host FFmpeg regression conversion is separate from Android executable/device
verification and does not by itself prove a packaged native binary works.

### ARM64 device verification (2026-11-13)

The exact ARM64 executable digest above was tested on a physical Pixel 6a
(Android 17/API 37). Production-equivalent concat/copy, mandatory audio mapping,
`-vn`, AAC bitstream filtering and MP4 output converted synthetic manifest windows
for ADTS and fMP4 (8.021333 seconds each) and MPEG-TS (8.000000 seconds) into
AAC-only MP4 files with no video. fMP4 included its initialization map. No-audio
and Opus inputs were rejected with exit code 234. Isolated temporary device files
were removed and absence verified, without app data, UI or settings changes.
This is native-executable evidence, not full Java mission/service integration,
Android decoding or live SoundCloud verification.
