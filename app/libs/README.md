# HLS converter

`slimhls-converter-7e7f373dbf-mov.aar` retains the Java wrapper and AAR metadata
from [slimhls-converter at 7e7f373dbfd97e5797fc374315e7da71910bf595](https://github.com/75ohmantenna/slimhls-converter/tree/7e7f373dbfd97e5797fc374315e7da71910bf595).
Only its four `libffmpeg.so` executables are replaced. The original binaries had
MPEG-TS and concat demuxers but no MOV/MP4 demuxer: real Android fMP4 downloads
failed with exit status 183 even though host FFmpeg conversion passed.

The rebuilt executables use the same
[FFmpeg source commit b4d9fa6cb93a3ef8209508b2fb9cd1dc95f03090](https://github.com/evermind-zz/FFmpeg4slimhls-converter/tree/b4d9fa6cb93a3ef8209508b2fb9cd1dc95f03090)
and NDK **25.2.9519653**, adding `--enable-demuxer=mov` and `--enable-bsf=h264_mp4toannexb`.
Concat requires the H.264 framing filter when reading MP4 fragments. MPEG-TS/concat input,
H.264/AAC, MP4 output and file/concat protocols remain enabled. Both 64-bit ABIs
retain 16 KiB load-segment alignment. No new network protocol or encoder is enabled.
The wrapper is GPL-3.0; the minimal FFmpeg build reports LGPL-2.1-or-later.
The linked source trees and build recipe provide the corresponding sources.

The app uses this local artifact so ordinary builds do not need an NDK.
`make ci` verifies its SHA-256 before building:

```text
2743910cd6d4bc5999d8aa7de6ccdb6249bde0d3c7b50335e71fa46972d52074
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
| armeabi-v7a | `17d4a9ab3de1b2dc388dd1fd210d821e7141b14d0270d2ebbe01c8462e8962b7` |
| arm64-v8a | `5c75f122878e673d64bce00c3e381a31c2564817ea5c01b809bc1e6256a0ad9b` |
| x86 | `9e16497fe68499eaada74d2ffbd66d8c27254242fe8cf91888b0ee1ba7af948e` |
| x86_64 | `c9f3f0cbee2098f9bbd6f0d865ac9247c6a714740c49cbfbe4e4fbc185515f16` |
