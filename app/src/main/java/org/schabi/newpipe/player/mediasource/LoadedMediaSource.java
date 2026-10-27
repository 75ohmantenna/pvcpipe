package org.schabi.newpipe.player.mediasource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.source.ForwardingTimeline;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.WrappingMediaSource;

import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class LoadedMediaSource extends WrappingMediaSource implements ManagedMediaSource {
    private final PlayQueueItem stream;
    private final MediaItem mediaItem;
    private final MediaItemTag ownedTag;
    private final long expireTimestamp;

    /**
     * Uses a {@link WrappingMediaSource} to wrap one child {@link MediaSource}s
     * containing actual media. This wrapper {@link LoadedMediaSource} holds the expiration
     * timestamp as a {@link ManagedMediaSource} to allow explicit playlist management under
     * {@link ManagedMediaSourcePlaylist}.
     *
     * @param source            The child media source with actual media.
     * @param tag               Metadata for the child media source.
     * @param stream            The queue item associated with the media source.
     * @param expireTimestamp   The timestamp when the media source expires and might not be
     *                          available for playback.
     */
    public LoadedMediaSource(@NonNull final MediaSource source,
                             @NonNull final MediaItemTag tag,
                             @NonNull final PlayQueueItem stream,
                             final long expireTimestamp) {
        super(source);
        this.stream = stream;
        this.expireTimestamp = expireTimestamp;

        this.ownedTag = tag.withExtras(this);
        this.mediaItem = ownedTag.asMediaItem();
    }

    public PlayQueueItem getStream() {
        return stream;
    }

    private boolean isExpired() {
        return System.currentTimeMillis() >= expireTimestamp;
    }

    @NonNull
    @Override
    public MediaItem getMediaItem() {
        return mediaItem;
    }

    @Nullable
    @Override
    public Timeline getInitialTimeline() {
        final Timeline timeline = super.getInitialTimeline();
        return timeline == null ? null : withOwnership(timeline);
    }

    @Override
    protected void onChildSourceInfoRefreshed(final Timeline timeline) {
        refreshSourceInfo(withOwnership(timeline));
    }

    private Timeline withOwnership(final Timeline timeline) {
        // ExoPlayer reads prepared media items from windows, not getMediaItem(). Tag each
        // window without changing the child's playback configuration, timing or period UIDs.
        final MediaItem[] items = new MediaItem[timeline.getWindowCount()];
        final Timeline.Window scratch = new Timeline.Window();
        for (int index = 0; index < items.length; index++) {
            items[index] = timeline.getWindow(index, scratch).mediaItem.buildUpon()
                    .setTag(ownedTag).build();
        }
        return new ForwardingTimeline(timeline) {
            @Override
            public Window getWindow(final int windowIndex, final Window window,
                                    final long defaultPositionProjectionUs) {
                super.getWindow(windowIndex, window, defaultPositionProjectionUs);
                window.mediaItem = items[windowIndex];
                return window;
            }
        };
    }

    @Override
    public boolean shouldBeReplacedWith(@NonNull final PlayQueueItem newIdentity,
                                        final boolean isInterruptable) {
        return newIdentity != stream || (isInterruptable && isExpired());
    }

    @Override
    public boolean isStreamEqual(@NonNull final PlayQueueItem otherStream) {
        return this.stream == otherStream;
    }
}
