silence.ts is generated four-second mono silence, not a third-party recording.
Regenerate with:
ffmpeg -f lavfi -i anullsrc=r=44100:cl=mono -t 4 -c:a aac -b:a 32k -f mpegts silence.ts
