silence.ts contains four seconds of generated mono silence.
Regenerate it in this directory.
ffmpeg -f lavfi -i anullsrc=r=44100:cl=mono -t 4 -c:a aac -b:a 32k -f mpegts silence.ts

The Kick fixtures contain eight seconds of synthetic media generated with
FFmpeg 7.1.5 and H.264/AAC. They exercise the same playlist shapes as the service.
kick-ts and kick-fmp4 contain 320x180 video at 30 fps and 48 kHz mono audio.
kick-low contains distinct 160x90 video at 30 fps and the same audio source.
kick-audio contains only the 48kHz mono sine audio.

Run these commands from the repository root.
ffmpeg -hide_banner -y -f lavfi -i testsrc2=size=320x180:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 8 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 60 -c:a aac -b:a 64k -f hls -hls_time 2 -hls_playlist_type vod -hls_flags single_file -hls_segment_filename app/src/androidTest/assets/media3-engine/kick-ts.ts app/src/androidTest/assets/media3-engine/kick-ts.m3u8
ffmpeg -hide_banner -y -f lavfi -i testsrc2=size=320x180:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 8 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 60 -c:a aac -b:a 64k -f hls -hls_time 2 -hls_playlist_type vod -hls_flags single_file -hls_segment_type fmp4 -hls_segment_filename app/src/androidTest/assets/media3-engine/kick-fmp4.mp4 app/src/androidTest/assets/media3-engine/kick-fmp4.m3u8
ffmpeg -hide_banner -y -f lavfi -i testsrc2=size=160x90:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 8 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 60 -c:a aac -b:a 64k -f hls -hls_time 2 -hls_playlist_type vod -hls_flags single_file -hls_segment_filename app/src/androidTest/assets/media3-engine/kick-low.ts app/src/androidTest/assets/media3-engine/kick-low.m3u8
ffmpeg -hide_banner -y -f lavfi -i sine=frequency=440:sample_rate=48000 -t 8 -c:a aac -b:a 64k -f hls -hls_time 2 -hls_playlist_type vod -hls_flags single_file -hls_segment_filename app/src/androidTest/assets/media3-engine/kick-audio.ts app/src/androidTest/assets/media3-engine/kick-audio.m3u8

kick-ts video starts at 1.421333 s (MPEGTS 127920). Audio starts at 1.400000 s
(MPEGTS 126000). The loopback harness uses the audio timestamp origin for
its English and French WebVTT segments. Single-file playlists use
EXT-X-BYTERANGE. kick-fmp4 also uses EXT-X-MAP.

audio-adts and audio-fmp4 contain eight-second, 48 kHz sine waves for AAC-only
HLS conversion tests. Both use byte windows, and fMP4 has an initialization map.
Run this command from the repository root to regenerate them.
python3 tools/generate_hls_audio_fixtures.py
Host tests use kick-audio for MPEG-TS audio and kick-ts to check video exclusion.
