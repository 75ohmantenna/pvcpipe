#!/usr/bin/env python3
"""Generate eight-second sine-wave ADTS/fMP4 audio fixtures, not site recordings."""
import json
from pathlib import Path
import subprocess
import tempfile


def run(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL)


def main():
    output = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/media3-engine"
    common = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
              "sine=frequency=440:sample_rate=48000", "-t", "8", "-c:a", "aac", "-b:a", "64k"]
    run(*common, "-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod", "-hls_flags",
        "single_file", "-hls_segment_type", "fmp4", "-hls_segment_filename",
        str(output / "audio-fmp4.mp4"), str(output / "audio-fmp4.m3u8"))
    with tempfile.TemporaryDirectory() as work:
        run(*common, "-f", "segment", "-segment_time", "2", "-segment_format", "adts",
            str(Path(work) / "audio-%02d.aac"))
        playlist = ["#EXTM3U", "#EXT-X-VERSION:4", "#EXT-X-TARGETDURATION:3",
                    "#EXT-X-PLAYLIST-TYPE:VOD"]
        offset = 0
        with (output / "audio-adts.aac").open("wb") as combined:
            for segment in sorted(Path(work).glob("*.aac")):
                info = json.loads(subprocess.check_output([
                    "ffprobe", "-v", "error", "-count_packets", "-show_entries",
                    "stream=nb_read_packets,sample_rate", "-of", "json", str(segment)]))
                stream = info["streams"][0]
                duration = int(stream["nb_read_packets"]) * 1024 / int(stream["sample_rate"])
                data = segment.read_bytes()
                combined.write(data)
                playlist += [f"#EXTINF:{duration:.6f},", f"#EXT-X-BYTERANGE:{len(data)}@{offset}",
                             "audio-adts.aac"]
                offset += len(data)
        (output / "audio-adts.m3u8").write_text("\n".join(playlist + ["#EXT-X-ENDLIST", ""]))


if __name__ == "__main__":
    main()
