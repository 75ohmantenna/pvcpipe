package org.schabi.newpipe.player.cast;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.google.android.exoplayer2.MediaItem;

import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.player.history.PlaybackHistory;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;

import java.util.List;
import java.util.Optional;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;

/** Real Player capture, queue recovery and history adapter; controlled engine and SDK delivery. */
public class CastClockAttributionTest extends CastPlaybackTestFixture {
    @Test
    public void metadataBeforeSynchronizationPausePreservesSelectedRecovery() throws Exception {
        pendingCapture(false, 12000);
    }

    @Test
    public void metadataBeforeSynchronizationDisconnectPreservesSelectedRecovery()
            throws Exception {
        pendingCapture(true, 12000);
    }

    @Test
    public void metadataBeforeSynchronizationKeepsUnsetRecovery() throws Exception {
        pendingCapture(false, PlayQueueItem.RECOVERY_UNSET);
    }

    private void pendingCapture(final boolean disconnect, final long recovery) throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            fixture.handoff();
            playback.onPlaybackChanged(1, 45000, 120000, 1);
            fixture.queue.setRecovery(1, recovery);
            fixture.queue.setIndex(1);
            deliverSecond(fixture);
            assertEquals(0, playback.getPosition());
            if (disconnect) {
                playback.stop(false);
            } else {
                playback.pause();
                player.setRecovery();
            }
            assertEquals(recovery, fixture.second.getRecoveryPosition());
            verify(history.record, never()).saveStreamState(any(StreamInfo.class), anyLong());
            when(player.isPlaying()).thenReturn(false);
            fixture.selectSecond();
            if (recovery == PlayQueueItem.RECOVERY_UNSET) {
                verify(player.getExoPlayer()).seekToDefaultPosition(1);
            } else {
                verify(player.getExoPlayer()).seekTo(1, recovery);
            }
            assertEquals(PlayQueueItem.RECOVERY_UNSET, fixture.second.getRecoveryPosition());
        }
    }

    @Test
    public void progressDuringPreparationDoesNotAcceptPreviewClock() throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            fixture.handoff();
            fixture.queue.setIndex(1);
            fixture.queue.setRecovery(1, 12000);
            deliverSecond(fixture);
            fixture.selectSecond();
            player.saveStreamProgressState();
            player.setRecovery();
            assertEquals(12000, fixture.second.getRecoveryPosition());
            verify(history.record, never()).saveStreamState(any(StreamInfo.class), anyLong());
        }
    }

    @Test
    public void pendingSeekIsIntentNotResetIncludingZero() throws Exception {
        for (final long target : new long[]{27000, 0}) {
            try (InitialPreparation fixture = new InitialPreparation();
                    HistoryCapture history = new HistoryCapture()) {
                fixture.handoff();
                fixture.queue.setIndex(1);
                deliverSecond(fixture);
                playback.seek(target);
                playback.pause();
                assertEquals(target, fixture.second.getRecoveryPosition());
                verify(history.record).saveStreamState(fixture.info, target);
                player.setRecovery();
                assertEquals(target, fixture.second.getRecoveryPosition());
                fixture.selectSecond();
                io.triggerActions();
                verify(playback.getSession()).load(media, target, playback.getSpeed(), false,
                        "Video", null);
            }
            clearInvocations(playback.getSession());
        }
    }

    @Test
    public void establishedZeroAndExtrapolatedPositionAreAccepted() throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            fixture.handoff();
            when(player.getExoPlayer().getCurrentMediaItemIndex()).thenReturn(0);
            playback.onPlaybackChanged(2, 0, 120000, 1);
            player.saveStreamProgressState();
            assertEquals(0, fixture.first.getRecoveryPosition());
            verify(history.record).saveStreamState(fixture.info, 0);
            playback.onPlaybackChanged(1, 45000, 120000, 1);
            now.addAndGet(2000);
            player.saveStreamProgressState();
            assertEquals(47000, fixture.first.getRecoveryPosition());
            verify(history.record).saveStreamState(fixture.info, 47000);
        }
    }

    @Test
    public void establishedClockSurvivesSameOccurrenceReplacement() throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            fixture.handoff();
            when(player.getExoPlayer().getCurrentMediaItemIndex()).thenReturn(0);
            playback.onPlaybackChanged(1, 45000, 120000, 1);
            fixture.deliverSuccess(newTag());
            playback.pause();
            assertEquals(45000, fixture.first.getRecoveryPosition());
            verify(history.record).saveStreamState(fixture.info, 45000);
        }
    }

    @Test
    public void invalidDisconnectDoesNotOverridePendingLocalResume() throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            fixture.handoff();
            fixture.queue.setRecovery(1, 12000);
            fixture.queue.setIndex(1);
            deliverSecond(fixture);
            doCallRealMethod().when(player).restoreLocalAfterCasting(anyLong(),
                    org.mockito.ArgumentMatchers.anyBoolean());
            clearInvocations(player.getExoPlayer());
            playback.stop(false);
            verify(player.getExoPlayer(), never()).seekTo(anyLong());
            fixture.selectSecond();
            assertEquals(12000, fixture.second.getRecoveryPosition());
        }
    }

    @Test
    public void cancelledReplacementAndLateCallbackCannotCheckpointReset() throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            fixture.handoff();
            fixture.queue.setRecovery(1, 12000);
            fixture.queue.setIndex(1);
            deliverSecond(fixture);
            fixture.selectSecond();
            fixture.queue.setRecovery(1, 12000);
            fixture.deliverFailure(fixture.second);
            io.triggerActions();
            playback.onPlaybackChanged(1, 99000, 120000, 1);
            player.setRecovery();
            player.saveStreamProgressState();
            assertEquals(12000, fixture.second.getRecoveryPosition());
            verify(history.record, never()).saveStreamState(any(StreamInfo.class), anyLong());
        }
    }

    @Test
    public void distinctMetadataDoesNotReceiveResetClockThenAcceptsItsObservedClock()
            throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            fixture.handoff();
            playback.onPlaybackChanged(1, 45000, 120000, 1);
            final StreamInfo second = mock(StreamInfo.class);
            when(second.getUrl()).thenReturn("https://example.invalid/second");
            when(second.getDuration()).thenReturn(120L);
            when(second.getName()).thenReturn("Second");
            when(second.getThumbnails()).thenReturn(List.of());
            final PlayQueueItem occurrence = new PlayQueueItem(second);
            fixture.queue.remove(1);
            fixture.queue.append(List.of(occurrence));
            fixture.queue.setIndex(1);
            fixture.queue.setRecovery(1, 12000);
            final MediaItemTag tag = newTag();
            when(tag.getStreamUrl()).thenReturn("https://example.invalid/second");
            when(tag.getMaybeStreamInfo()).thenReturn(Optional.of(second));
            ownSource(tag, occurrence);
            final MediaItem engineItem = new MediaItem.Builder()
                    .setUri(second.getUrl()).setTag(tag).build();
            when(player.getExoPlayer().getCurrentMediaItem()).thenReturn(engineItem);
            player.onEvents(player.getExoPlayer(),
                    mock(com.google.android.exoplayer2.Player.Events.class));
            playback.pause();
            assertEquals(12000, occurrence.getRecoveryPosition());
            verify(history.record, never()).saveStreamState(any(StreamInfo.class), anyLong());
            player.onPlaybackSynchronize(occurrence, false);
            io.triggerActions();
            playback.onPlaybackChanged(2, 23000, 120000, 1);
            player.saveStreamProgressState();
            assertEquals(23000, occurrence.getRecoveryPosition());
            verify(history.record).saveStreamState(second, 23000);
            verify(history.record, never()).saveStreamState(fixture.info, 23000);
        }
    }

    private void deliverSecond(final InitialPreparation fixture) {
        final MediaItemTag tag = newTag();
        ownSource(tag, fixture.second);
        fixture.deliverSuccess(tag);
    }

    @Test
    public void syntheticReadyClockCannotAuthorizeCaptureButBufferedReceiverTimeCan()
            throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            fixture.handoff();
            fixture.queue.setIndex(1);
            fixture.queue.setRecovery(1, 12000);
            deliverSecond(fixture);
            fixture.selectSecond();
            io.triggerActions();
            final FCastPlaybackState receiver = new FCastPlaybackState(playback,
                    () -> kotlin.Unit.INSTANCE);
            when(playback.getSession().hasObservedPosition())
                    .thenAnswer(call -> receiver.getHasObservedPosition());
            receiver.load(new org.fcast.sender_sdk.Source.Url("http://192.0.2.1/b", "video/mp4"),
                    10000, 1, true);
            receiver.emit();
            receiver.playbackStateChanged(org.fcast.sender_sdk.PlaybackState.PLAYING);
            receiver.durationChanged(120);
            player.saveStreamProgressState();
            player.setRecovery();
            assertEquals(12000, fixture.second.getRecoveryPosition());
            verify(history.record, never()).saveStreamState(any(StreamInfo.class), anyLong());
            receiver.playbackStateChanged(org.fcast.sender_sdk.PlaybackState.BUFFERING);
            receiver.timeChanged(0);
            player.saveStreamProgressState();
            assertEquals(0, fixture.second.getRecoveryPosition());
            verify(history.record).saveStreamState(fixture.info, 0);
        }
    }

    @Test
    public void explicitPendingSeekSurvivesSyntheticLoadAndReceiverSwitch() throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            fixture.handoff();
            when(player.getExoPlayer().getCurrentMediaItemIndex()).thenReturn(0);
            playback.onPlaybackChanged(1, 45000, 120000, 1);
            playback.connect("192.0.2.2", 46899);
            playback.onPlaybackChanged(1, 99000, 120000, 1);
            playback.seek(27000);
            playback.pause();
            assertEquals(27000, fixture.first.getRecoveryPosition());
            playback.onConnectionChanged();
            io.triggerActions();
            when(playback.getSession().hasObservedPosition()).thenReturn(false);
            playback.onPlaybackChanged(3, 0, 0, 1);
            player.saveStreamProgressState();
            assertEquals(27000, fixture.first.getRecoveryPosition());
            verify(history.record, org.mockito.Mockito.times(2))
                    .saveStreamState(fixture.info, 27000);
            verify(playback.getSession()).load(media, 27000, playback.getSpeed(), false,
                    "Video", null);
        }
    }

    @Test
    public void stateOnlyCallbackAfterSeekDoesNotRestorePreSeekReceiverSample()
            throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            fixture.handoff();
            when(player.getExoPlayer().getCurrentMediaItemIndex()).thenReturn(0);
            final FCastPlaybackState receiver = new FCastPlaybackState(playback,
                    () -> kotlin.Unit.INSTANCE);
            when(playback.getSession().hasObservedPosition())
                    .thenAnswer(call -> receiver.getHasObservedPosition());
            receiver.load(new org.fcast.sender_sdk.Source.Url("http://192.0.2.1/a", "video/mp4"),
                    10000, 1, true);
            receiver.playbackStateChanged(org.fcast.sender_sdk.PlaybackState.BUFFERING);
            receiver.timeChanged(10);
            final FCastSession session = playback.getSession();
            org.mockito.Mockito.doAnswer(call -> {
                receiver.seek(call.getArgument(0));
                return null;
            }).when(session).seek(anyLong());
            playback.seek(27000);
            receiver.durationChanged(120);
            playback.pause();
            assertEquals(27000, fixture.first.getRecoveryPosition());
            verify(history.record).saveStreamState(fixture.info, 27000);
        }
    }

    @Test
    public void disconnectBeforeAggregateMetadataDoesNotSeekBWithEstablishedAClock()
            throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            fixture.handoff();
            playback.onPlaybackChanged(1, 45000, 120000, 1);
            fixture.queue.setIndex(1);
            fixture.queue.setRecovery(1, 12000);
            final MediaItemTag tag = newTag();
            ownSource(tag, fixture.second);
            final MediaItem engineItem = new MediaItem.Builder()
                    .setUri("https://example.invalid/video").setTag(tag).build();
            when(player.getExoPlayer().getCurrentMediaItem()).thenReturn(engineItem);
            doCallRealMethod().when(player).restoreLocalAfterCasting(anyLong(),
                    org.mockito.ArgumentMatchers.anyBoolean());
            clearInvocations(player.getExoPlayer());
            playback.stop(false);
            assertEquals(12000, fixture.second.getRecoveryPosition());
            verify(player.getExoPlayer(), never()).seekTo(anyLong());
            verify(history.record, never()).saveStreamState(any(StreamInfo.class), anyLong());
        }
    }

    @Test
    public void sourceReloadAndCloseCannotStoreResetClock() throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            fixture.handoff();
            fixture.queue.setIndex(1);
            fixture.queue.setRecovery(1, 12000);
            deliverSecond(fixture);
            setPlayer("playbackSources",
                    mock(org.schabi.newpipe.player.resolver.PlaybackSources.class));
            doCallRealMethod().when(player).setPlaybackQuality(any());
            doCallRealMethod().when(player).setAudioTrack(any());
            player.setPlaybackQuality("720p");
            player.setAudioTrack("track");
            playback.close();
            playback.onPlaybackChanged(1, 99000, 120000, 1);
            assertEquals(12000, fixture.second.getRecoveryPosition());
            verify(history.record, never()).saveStreamState(any(StreamInfo.class), anyLong());
        }
    }

    @Test
    public void disabledHistoryStillKeepsPendingRecoveryAndAcceptsExplicitSeek() throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture(false)) {
            fixture.handoff();
            fixture.queue.setIndex(1);
            fixture.queue.setRecovery(1, 12000);
            deliverSecond(fixture);
            player.saveStreamProgressState();
            assertEquals(12000, fixture.second.getRecoveryPosition());
            playback.seek(27000);
            playback.pause();
            assertEquals(27000, fixture.second.getRecoveryPosition());
            verify(history.record, never()).saveStreamState(any(StreamInfo.class), anyLong());
        }
    }

    @Test
    public void playHereBeforeSynchronizationResumesSelectedRecoveryNotPreview() throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            fixture.handoff();
            fixture.queue.setIndex(1);
            fixture.queue.setRecovery(1, 12000);
            deliverSecond(fixture);
            setPlayer("playbackAudio",
                    mock(org.schabi.newpipe.player.audio.PlaybackAudio.class));
            doCallRealMethod().when(player).play();
            doCallRealMethod().when(player).restoreLocalAfterCasting(anyLong(),
                    org.mockito.ArgumentMatchers.anyBoolean());
            final com.google.android.exoplayer2.ExoPlayer exo = player.getExoPlayer();
            final java.util.concurrent.atomic.AtomicLong local =
                    new java.util.concurrent.atomic.AtomicLong(10000);
            when(exo.getCurrentPosition()).thenAnswer(call -> local.get());
            when(exo.getContentPosition()).thenAnswer(call -> local.get());
            org.mockito.Mockito.doAnswer(call -> {
                local.set(call.getArgument(1));
                return null;
            }).when(exo).seekTo(org.mockito.ArgumentMatchers.anyInt(), anyLong());
            playback.stop(true);
            assertEquals(12000, local.get());
            assertEquals(12000, fixture.second.getRecoveryPosition());
            verify(exo).play();
            verify(history.record).saveStreamState(fixture.info, 12000);
        }
    }

    @Test
    public void synchronizedResumeSurvivesNavigationDuringResolution() throws Exception {
        synchronizedResumeSurvivesNavigation(false, true);
    }

    @Test
    public void synchronizedResumeSurvivesNavigationAfterLoadBeforeReceiverTime() throws Exception {
        synchronizedResumeSurvivesNavigation(true, true);
    }

    @Test
    public void synchronizedResumeSurvivesNavigationWithHistoryDisabled() throws Exception {
        synchronizedResumeSurvivesNavigation(true, false);
    }

    private void synchronizedResumeSurvivesNavigation(final boolean submitted,
                                                      final boolean historyEnabled)
            throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture(historyEnabled)) {
            final com.google.android.exoplayer2.ExoPlayer exo = player.getExoPlayer();
            final java.util.concurrent.atomic.AtomicLong local =
                    new java.util.concurrent.atomic.AtomicLong(10000);
            final java.util.concurrent.atomic.AtomicInteger index =
                    new java.util.concurrent.atomic.AtomicInteger(0);
            when(exo.getCurrentPosition()).thenAnswer(call -> local.get());
            when(exo.getContentPosition()).thenAnswer(call -> local.get());
            when(exo.getCurrentMediaItemIndex()).thenAnswer(call -> index.get());
            when(exo.getCurrentTimeline().getWindowCount()).thenReturn(3);
            org.mockito.Mockito.doAnswer(call -> {
                index.set(call.getArgument(0));
                local.set(call.getArgument(1));
                return null;
            }).when(exo).seekTo(org.mockito.ArgumentMatchers.anyInt(), anyLong());
            org.mockito.Mockito.doAnswer(call -> {
                index.set(call.getArgument(0));
                local.set(0);
                return null;
            }).when(exo).seekToDefaultPosition(org.mockito.ArgumentMatchers.anyInt());
            doCallRealMethod().when(player).playNext();
            doCallRealMethod().when(player).selectQueueItem(any());
            final PlayQueueItem third = new PlayQueueItem(fixture.info);
            fixture.queue.append(List.of(third));
            fixture.handoff();
            playback.onPlaybackChanged(1, 45000, 120000, 1);
            fixture.queue.setRecovery(1, 12000);
            player.playNext();
            fixture.selectSecond();
            assertEquals(12000, local.get());
            assertEquals(PlayQueueItem.RECOVERY_UNSET, fixture.second.getRecoveryPosition());
            deliverSecond(fixture);
            if (submitted) {
                io.triggerActions();
                verify(playback.getSession()).load(media, 12000, playback.getSpeed(), true,
                        "Video", null);
            }
            clearInvocations(history.record);
            player.playNext();
            assertEquals(12000, fixture.second.getRecoveryPosition());
            if (historyEnabled) {
                verify(history.record).saveStreamState(fixture.info, 12000);
            } else {
                verify(history.record, never()).saveStreamState(any(StreamInfo.class), anyLong());
            }
            player.onPlaybackSynchronize(third, false);
            final MediaItemTag thirdTag = newTag();
            ownSource(thirdTag, third);
            fixture.deliverSuccess(thirdTag);
            player.selectQueueItem(fixture.second);
            clearInvocations(playback.getSession());
            fixture.selectSecond();
            assertEquals(12000, local.get());
            assertEquals(PlayQueueItem.RECOVERY_UNSET, fixture.second.getRecoveryPosition());
            deliverSecond(fixture);
            io.triggerActions();
            verify(playback.getSession()).load(media, 12000, playback.getSpeed(), true,
                    "Video", null);
        }
    }

    @Test
    public void synchronizedDefaultZeroIsOwnedBeforeReceiverTime() throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            fixture.handoff();
            fixture.queue.setIndex(1);
            when(player.getExoPlayer().getCurrentMediaItemIndex()).thenReturn(0);
            final com.google.android.exoplayer2.ExoPlayer exo = player.getExoPlayer();
            org.mockito.Mockito.doAnswer(call -> {
                when(exo.getCurrentMediaItemIndex()).thenReturn(1);
                when(exo.getCurrentPosition()).thenReturn(0L);
                return null;
            }).when(exo).seekToDefaultPosition(1);
            fixture.selectSecond();
            verify(exo).seekToDefaultPosition(1);
            deliverSecond(fixture);
            player.saveStreamProgressState();
            assertEquals(0, fixture.second.getRecoveryPosition());
            verify(history.record).saveStreamState(fixture.info, 0);
        }
    }

    @Test
    public void initialHandoffKeepsOwnedLocalCheckpointWithoutReceiverTime() throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            when(player.getExoPlayer().getCurrentMediaItemIndex()).thenReturn(0);
            fixture.handoff();
            playback.pause();
            assertEquals(10000, fixture.first.getRecoveryPosition());
            verify(history.record).saveStreamState(fixture.info, 10000);
        }
    }

    @Test
    public void localProgressThenHandoffThenNextKeepsLatestLocalClock() throws Exception {
        localProgressThenHandoff(false);
    }

    @Test
    public void localProgressThenHandoffThenCloseKeepsLatestLocalClock() throws Exception {
        localProgressThenHandoff(true);
    }

    private void localProgressThenHandoff(final boolean close) throws Exception {
        try (InitialPreparation fixture = new InitialPreparation();
                HistoryCapture history = new HistoryCapture()) {
            final com.google.android.exoplayer2.ExoPlayer exo = player.getExoPlayer();
            when(exo.getCurrentMediaItemIndex()).thenReturn(0);
            when(exo.getContentPosition()).thenReturn(10000L);
            player.saveStreamProgressState();
            assertEquals(10000, fixture.first.getRecoveryPosition());
            when(exo.getCurrentPosition()).thenReturn(60000L);
            when(exo.getContentPosition()).thenReturn(60000L);
            fixture.handoff();
            if (close) {
                playback.close();
            } else {
                doCallRealMethod().when(player).playNext();
                player.playNext();
            }
            assertEquals(60000, fixture.first.getRecoveryPosition());
            verify(history.record).saveStreamState(fixture.info, 60000);
        }
    }

    private final class HistoryCapture implements AutoCloseable {
        private final MockedStatic<PreferenceManager> preferences =
                mockStatic(PreferenceManager.class);
        private final MockedConstruction<HistoryRecordManager> records =
                mockConstruction(HistoryRecordManager.class, (manager, context) -> {
                    when(manager.saveStreamState(any(StreamInfo.class), anyLong()))
                            .thenReturn(Completable.complete());
                    when(manager.onViewed(any(StreamInfo.class))).thenReturn(Maybe.empty());
                });
        private final HistoryRecordManager record;

        HistoryCapture() throws Exception {
            this(true);
        }

        HistoryCapture(final boolean enabled) throws Exception {
            final SharedPreferences prefs = mock(SharedPreferences.class);
            when(player.getContext().getString(R.string.enable_watch_history_key))
                    .thenReturn("history");
            when(prefs.getBoolean("history", true)).thenReturn(enabled);
            final android.content.Context context = player.getContext();
            preferences.when(() -> PreferenceManager.getDefaultSharedPreferences(context))
                    .thenReturn(prefs);
            setPlayer("playbackHistory", new PlaybackHistory(player.getContext()));
            record = records.constructed().get(0);
            doCallRealMethod().when(player).saveStreamProgressState();
            doCallRealMethod().when(player).setRecovery();
            doCallRealMethod().when(player).getCurrentPosition();
            doCallRealMethod().when(player).getDuration();
        }

        @Override
        public void close() {
            records.close();
            preferences.close();
        }
    }
}
