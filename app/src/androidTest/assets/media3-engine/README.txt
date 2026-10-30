silence.ts is generated four-second mono silence, not a third-party recording.
Regenerate with:
ffmpeg -f lavfi -i anullsrc=r=44100:cl=mono -t 4 -c:a aac -b:a 32k -f mpegts silence.ts

2026-10-30: Kick protocol/media fixtures are generated test patterns and sine waves,
not recordings from Kick or another site. ffmpeg 7.1.5, libx264/AAC, eight seconds.
kick-ts and kick-fmp4 contain 320x180/30fps video and 48kHz mono audio.
kick-low contains distinct 160x90/30fps video and the same audio source.
kick-audio contains only the 48kHz mono sine audio.

Regenerate from the repository root:
ffmpeg -hide_banner -y -f lavfi -i testsrc2=size=320x180:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 8 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 60 -c:a aac -b:a 64k -f hls -hls_time 2 -hls_playlist_type vod -hls_flags single_file -hls_segment_filename app/src/androidTest/assets/media3-engine/kick-ts.ts app/src/androidTest/assets/media3-engine/kick-ts.m3u8
ffmpeg -hide_banner -y -f lavfi -i testsrc2=size=320x180:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 8 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 60 -c:a aac -b:a 64k -f hls -hls_time 2 -hls_playlist_type vod -hls_flags single_file -hls_segment_type fmp4 -hls_segment_filename app/src/androidTest/assets/media3-engine/kick-fmp4.mp4 app/src/androidTest/assets/media3-engine/kick-fmp4.m3u8
ffmpeg -hide_banner -y -f lavfi -i testsrc2=size=160x90:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 8 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 60 -c:a aac -b:a 64k -f hls -hls_time 2 -hls_playlist_type vod -hls_flags single_file -hls_segment_filename app/src/androidTest/assets/media3-engine/kick-low.ts app/src/androidTest/assets/media3-engine/kick-low.m3u8
ffmpeg -hide_banner -y -f lavfi -i sine=frequency=440:sample_rate=48000 -t 8 -c:a aac -b:a 64k -f hls -hls_time 2 -hls_playlist_type vod -hls_flags single_file -hls_segment_filename app/src/androidTest/assets/media3-engine/kick-audio.ts app/src/androidTest/assets/media3-engine/kick-audio.m3u8

kick-ts video starts at 1.421333s (MPEGTS 127920); audio starts at 1.400000s
(MPEGTS 126000), not zero. The loopback engine harness authors English/French
segmented WebVTT against this measured audio timestamp origin.
The single-file playlists exercise EXT-X-BYTERANGE; kick-fmp4 also uses EXT-X-MAP.
