package org.schabi.newpipe.player.cast;

import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackParameters;

import org.fcast.sender_sdk.PlaybackState;
import org.fcast.sender_sdk.Source;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.player.ui.PlayerUiList;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import kotlin.Unit;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;

public class CastPlaybackTest {
    private final AtomicLong now = new AtomicLong(1000);
    private MockedStatic<SystemClock> clock;
    private MockedConstruction<FCastSession> sessions;
    private CastPlayback playback;
    private Player player;
    private MockedConstruction<CastMediaResolver> resolvers;
    private final TestScheduler io = new TestScheduler();
    private final CastMedia media = mock(CastMedia.class);

    @Before
    public void setUp() throws Exception {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> io);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        resolvers = mockConstruction(CastMediaResolver.class, (resolver, context) ->
                when(resolver.resolve(any(), any(), any(InetAddress.class))).thenReturn(media));
        clock = mockStatic(SystemClock.class);
        clock.when(SystemClock::elapsedRealtime).thenAnswer(invocation -> now.get());
        sessions = mockConstruction(FCastSession.class, (session, arguments) -> {
            when(session.getConnectionState()).thenReturn(2);
            when(session.getLocalAddress()).thenReturn(
                    InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 1}));
        });
        final Context context = mock(Context.class);
        final PowerManager power = mock(PowerManager.class);
        when(context.getSystemService(Context.POWER_SERVICE)).thenReturn(power);
        when(power.newWakeLock(anyInt(), anyString()))
                .thenReturn(mock(PowerManager.WakeLock.class));
        player = mock(Player.class);
        when(player.getContext()).thenReturn(context);
        final ExoPlayer exo = mock(ExoPlayer.class);
        when(exo.getCurrentPosition()).thenReturn(10000L);
        when(player.getExoPlayer()).thenReturn(exo);
        when(player.getCurrentPosition()).thenReturn(10000L);
        when(player.UIs()).thenReturn(mock(PlayerUiList.class));
        final StreamInfo info = mock(StreamInfo.class);
        when(info.getDuration()).thenReturn(120L);
        when(info.getName()).thenReturn("Video");
        when(info.getThumbnails()).thenReturn(List.of());
        when(player.getCurrentStreamInfo()).thenReturn(Optional.of(info));
        playback = new CastPlayback(player);
        when(player.getPlaybackSpeed()).thenAnswer(invocation ->
                playback.isActive() ? playback.getSpeed() : 1.5f);
        set("active", true);
        final MediaItemTag tag = newTag();
        set("loadedTag", tag);
        when(player.getCurrentMetadata()).thenReturn(tag);
        final PlayQueueItem item = mock(PlayQueueItem.class);
        when(player.getCurrentItem()).thenReturn(item);
        set("loadedItem", item);
    }

    @After
    public void tearDown() {
        playback.close();
        sessions.close();
        resolvers.close();
        clock.close();
        RxAndroidPlugins.reset();
        RxJavaPlugins.reset();
    }

    @Test
    public void remoteClockFollowsSpeedStopsWhilePausedAndClampsAtEnd() {
        playback.onPlaybackChanged(1, 10000, 120000, 1.5);
        now.addAndGet(2000);
        assertEquals(13000, playback.getPosition());
        playback.pause();
        now.addAndGet(5000);
        assertEquals(13000, playback.getPosition());
        playback.onPlaybackChanged(1, 119000, 120000, 2);
        now.addAndGet(2000);
        assertEquals(120000, playback.getPosition());
    }

    @Test
    public void receiverSpeedsMustRemainPositiveAndFiniteAfterFloatConversion() {
        playback.onPlaybackChanged(1, 10000, 120000, 1.5);
        for (final double value : new double[]{Double.MIN_VALUE, Double.MAX_VALUE,
                Double.NaN, Double.POSITIVE_INFINITY, 0, -1}) {
            playback.onPlaybackChanged(1, 10000, 120000, value);
            assertEquals(1.5f, playback.getSpeed(), 0f);
            assertEquals(1.5f, playback.getParameters().speed, 0f);
        }
        playback.onPlaybackChanged(1, 10000, 120000, 2);
        now.addAndGet(1000);
        assertEquals(12000, playback.getPosition());
    }

    @Test
    public void seekingInsideCompletedMediaMakesTheSelectedPositionResumable() {
        playback.onPlaybackChanged(1, 120000, 120000, 1);
        playback.onEnded();
        playback.seek(42000);
        playback.onPlaybackChanged(2, 42000, 120000, 1);
        playback.play();
        assertEquals(42000, playback.getPosition());
        assertTrue(playback.getPlayWhenReady());
        now.addAndGet(1000);
        assertEquals(43000, playback.getPosition());
    }

    @Test
    public void initialIdleDoesNotDetachButTerminalLegacyIdleReleasesPlayback() {
        final FCastPlaybackState receiver = receiver(true);
        receiver.load(source("own"), 10000, 1, true);
        receiver.emit();
        receiver.playbackStateChanged(PlaybackState.IDLE);
        assertTrue(playback.isActive());
        assertTrue(playback.isPreparing());

        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.timeChanged(42);
        receiver.durationChanged(120);
        receiver.timeChanged(0);
        receiver.durationChanged(0);
        receiver.playbackStateChanged(PlaybackState.IDLE);
        assertFalse(playback.isActive());
        assertFalse(playback.isPreparing());
        verify(player, never()).onCastingEnded();
        verify(playback.getSession()).disconnect(false);
        verify(playback.getSession(), never()).disconnect(true);
    }

    @Test
    public void alreadyPlayingBeforeLoadBecomesReadyOnAdvancingTimeWithoutAnotherStateCallback() {
        final FCastPlaybackState receiver = receiver(true);
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.load(source("own"), 10000, 1, true);
        receiver.emit();
        assertTrue(playback.isPreparing());
        receiver.timeChanged(11);
        receiver.durationChanged(120);
        assertFalse(playback.isPreparing());
        assertTrue(playback.getPlayWhenReady());
        assertEquals(11000, playback.getPosition());
    }

    @Test
    public void legacyNaturalIdleCompletesOnceWithoutRelinquishingReplayOwnership() {
        final FCastPlaybackState receiver = receiver(true);
        receiver.load(source("own"), 10000, 1, true);
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.durationChanged(60);
        receiver.timeChanged(59.5);
        receiver.playbackStateChanged(PlaybackState.IDLE);
        receiver.playbackStateChanged(PlaybackState.IDLE);
        assertTrue(playback.isActive());
        assertFalse(playback.getPlayWhenReady());
        assertEquals(60000, playback.getPosition());
        verify(player).onCastingEnded();
        verify(playback.getSession(), never()).disconnect(anyBoolean());
    }

    @Test
    public void androidV2PlayingNearEndThenPausedAtDurationCompletesOnce() {
        final FCastPlaybackState receiver = receiver(true);
        receiver.load(source("own"), 10000, 1, true);
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.durationChanged(60);
        receiver.timeChanged(59);
        receiver.timeChanged(60);
        receiver.playbackStateChanged(PlaybackState.PAUSED);
        receiver.playbackStateChanged(PlaybackState.PAUSED);
        receiver.playbackStateChanged(PlaybackState.IDLE);
        assertTrue(playback.isActive());
        assertFalse(playback.getPlayWhenReady());
        assertEquals(60000, playback.getPosition());
        verify(player).onCastingEnded();
        verify(playback.getSession(), never()).disconnect(anyBoolean());
    }

    @Test
    public void sdkV3WithoutSourceEchoPausedAtDurationCompletesOnce() {
        final FCastPlaybackState receiver = receiver(false);
        receiver.setLegacyCompletionWithoutEcho(true);
        receiver.load(source("own"), 10000, 1, true);
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.durationChanged(60);
        receiver.timeChanged(59);
        receiver.timeChanged(60);
        receiver.playbackStateChanged(PlaybackState.PAUSED);
        receiver.playbackStateChanged(PlaybackState.PAUSED);
        receiver.playbackStateChanged(PlaybackState.IDLE);
        assertTrue(playback.isActive());
        assertFalse(playback.getPlayWhenReady());
        assertEquals(60000, playback.getPosition());
        verify(player).onCastingEnded();
        verify(playback.getSession(), never()).disconnect(anyBoolean());
    }

    @Test
    public void sdkV3WithoutSourceEchoPausedAtDurationRepeatsQueueOnce() throws Exception {
        final StreamInfo info = new StreamInfo(0, "https://example.invalid/repeat",
                "https://example.invalid/repeat", StreamType.VIDEO_STREAM, "repeat", "Repeat", 0);
        final SinglePlayQueue queue = new SinglePlayQueue(info);
        queue.append(List.of(mock(PlayQueueItem.class)));
        queue.setIndex(1);
        final Field castField = Player.class.getDeclaredField("casting");
        castField.setAccessible(true);
        castField.set(player, playback);
        final Field queueField = Player.class.getDeclaredField("playQueue");
        queueField.setAccessible(true);
        queueField.set(player, queue);
        when(player.getRepeatMode()).thenReturn(REPEAT_MODE_ALL);
        doCallRealMethod().when(player).onCastingEnded();

        final FCastPlaybackState receiver = receiver(false);
        receiver.setLegacyCompletionWithoutEcho(true);
        receiver.load(source("own"), 10000, 1, true);
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.durationChanged(60);
        receiver.timeChanged(59);
        receiver.timeChanged(60);
        receiver.playbackStateChanged(PlaybackState.PAUSED);
        receiver.playbackStateChanged(PlaybackState.PAUSED);
        receiver.playbackStateChanged(PlaybackState.IDLE);
        assertEquals(0, queue.getIndex());
        assertEquals(0, playback.getPosition());
        assertTrue(playback.isActive());
        assertTrue(playback.isPreparing());
        assertTrue(playback.getPlayWhenReady());
        verify(player).onCastingEnded();
    }

    @Test
    public void sdkV3WithOwnSourceEchoRequiresExplicitEndInsteadOfPausedAtDuration() {
        final FCastPlaybackState receiver = receiver(false);
        receiver.setLegacyCompletionWithoutEcho(true);
        receiver.load(source("own"), 10000, 1, true);
        receiver.sourceChanged(source("own"));
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.durationChanged(60);
        receiver.timeChanged(60);
        receiver.playbackStateChanged(PlaybackState.PAUSED);
        verify(player, never()).onCastingEnded();
        receiver.playbackStateChanged(PlaybackState.ENDED);
        verify(player).onCastingEnded();
    }

    @Test
    public void v4WithoutOwnSourceEchoRequiresExplicitEndInsteadOfPausedAtDuration() {
        final FCastPlaybackState receiver = receiver(false);
        receiver.load(source("own"), 10000, 1, true);
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.durationChanged(60);
        receiver.timeChanged(60);
        receiver.playbackStateChanged(PlaybackState.PAUSED);
        assertTrue(playback.isActive());
        assertFalse(playback.getPlayWhenReady());
        verify(player, never()).onCastingEnded();
        receiver.playbackStateChanged(PlaybackState.ENDED);
        verify(player).onCastingEnded();
    }

    @Test
    public void foreignSourceCannotAdvanceOrCompleteCurrentVideoAndIsNotStopped() {
        final FCastPlaybackState receiver = receiver(false);
        receiver.load(source("own"), 10000, 1, true);
        receiver.sourceChanged(source("own"));
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.timeChanged(42);
        receiver.durationChanged(120);
        receiver.sourceChanged(source("foreign"));
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.timeChanged(300);
        receiver.durationChanged(600);
        receiver.playbackStateChanged(PlaybackState.ENDED);
        assertFalse(playback.isActive());
        assertEquals(42000, playback.getPosition());
        assertEquals(120000, playback.getDuration());
        verify(player).restoreLocalAfterCasting(42000, false);
        verify(player, never()).onCastingEnded();
        verify(playback.getSession()).disconnect(false);
        verify(playback.getSession(), never()).disconnect(true);
        verify(playback.getSession(), never()).pause();
    }

    @Test
    public void foreignSourceCancelsQualityPreparationWithoutStoppingReplacement() {
        final FCastPlaybackState receiver = receiver(false);
        receiver.load(source("own"), 10000, 1, true);
        receiver.sourceChanged(source("own"));
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.timeChanged(42);
        receiver.durationChanged(120);
        final MediaItemTag replacement = newTag();
        when(player.getCurrentMetadata()).thenReturn(replacement);
        playback.onSourceChanged();
        clearInvocations(playback.getSession());
        receiver.sourceChanged(source("foreign"));
        io.triggerActions();
        assertFalse(playback.isActive());
        assertFalse(playback.isPreparing());
        verify(player).restoreLocalAfterCasting(42000, false);
        verify(player, never()).onCastingEnded();
        verify(playback.getSession()).disconnect(false);
        verify(playback.getSession(), never()).disconnect(true);
        verify(playback.getSession(), never()).load(
                any(), anyLong(), anyFloat(), anyBoolean(), anyString(), any());
    }

    @Test
    public void v4LoadWithoutOwnSourceEchoBecomesReadyAndDetectsForeignReplacement() {
        final FCastPlaybackState receiver = receiver(false);
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.load(source("own"), 10000, 1, true);
        receiver.emit();
        receiver.sourceChanged(source("previous"));
        assertTrue(playback.isActive());
        assertTrue(playback.isPreparing());
        receiver.timeChanged(11);
        receiver.durationChanged(120);
        assertFalse(playback.isPreparing());
        assertEquals(11000, playback.getPosition());
        receiver.sourceChanged(source("foreign"));
        receiver.timeChanged(300);
        receiver.playbackStateChanged(PlaybackState.ENDED);
        assertFalse(playback.isActive());
        assertEquals(11000, playback.getPosition());
        verify(player, never()).onCastingEnded();
        verify(playback.getSession()).disconnect(false);
        verify(playback.getSession(), never()).disconnect(true);
    }

    @Test
    public void sdkV3WithoutInitialOrPlayUpdateEchoBecomesReadyFromPlayingAndTimeUpdates() {
        final FCastPlaybackState receiver = receiver(false);
        receiver.setLegacyCompletionWithoutEcho(true);
        receiver.load(source("first"), 10000, 1, true);
        receiver.emit();
        assertTrue(playback.isPreparing());
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.timeChanged(11);
        receiver.durationChanged(120);
        assertFalse(playback.isPreparing());
        assertTrue(playback.getPlayWhenReady());
        assertEquals(11000, playback.getPosition());

        receiver.load(source("next"), 42000, 1, true);
        receiver.emit();
        assertTrue(playback.isPreparing());
        receiver.timeChanged(43);
        assertTrue(playback.isActive());
        assertFalse(playback.isPreparing());
        assertTrue(playback.getPlayWhenReady());
        assertEquals(43000, playback.getPosition());
        verify(playback.getSession(), never()).disconnect(anyBoolean());
    }

    @Test
    public void pendingSourceIgnoresOldAcknowledgementsAndClockUntilItsOwnAcknowledgement() {
        final FCastPlaybackState receiver = receiver(false);
        receiver.load(source("first"), 10000, 1, true);
        receiver.sourceChanged(source("first"));
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.timeChanged(42);
        receiver.durationChanged(120);

        receiver.load(source("next"), 65000, 1, true);
        receiver.emit();
        receiver.sourceChanged(source("first"));
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.timeChanged(90);
        receiver.durationChanged(999);
        receiver.playbackStateChanged(PlaybackState.ENDED);
        receiver.stopped();
        assertTrue(playback.isActive());
        assertTrue(playback.isPreparing());
        assertEquals(65000, playback.getPosition());
        assertEquals(120000, playback.getDuration());
        verify(player, never()).onCastingEnded();

        receiver.sourceChanged(source("next"));
        receiver.playbackStateChanged(PlaybackState.PLAYING);
        receiver.timeChanged(66);
        receiver.durationChanged(120);
        assertTrue(playback.isActive());
        assertFalse(playback.isPreparing());
        assertEquals(66000, playback.getPosition());
        verify(playback.getSession(), never()).disconnect(anyBoolean());
    }

    @Test
    public void receiverChangesFreezeTheRemotePositionAndKeepPlaybackIntent() {
        playback.onPlaybackChanged(1, 10000, 120000, 1.5);
        now.addAndGet(2000);
        playback.connect("other-receiver.example", 46899);
        now.addAndGet(5000);
        assertTrue(playback.isActive());
        assertTrue(playback.isPreparing());
        assertTrue(playback.getPlayWhenReady());
        assertEquals(13000, playback.getPosition());
        assertEquals(120000, playback.getDuration());
        playback.pause();
        assertFalse(playback.getPlayWhenReady());
        assertEquals(13000, playback.getPosition());
    }

    @Test
    public void receiverProgressDoesNotInterruptThePlayersScrubState() throws Exception {
        final Field state = Player.class.getDeclaredField("currentState");
        state.setAccessible(true);
        state.setInt(player, Player.STATE_PAUSED_SEEK);
        doCallRealMethod().when(player).onCastingStateChanged(anyInt());
        doCallRealMethod().when(player).getCurrentState();
        playback.onPlaybackChanged(1, 42000, 120000, 1);
        assertEquals(Player.STATE_PAUSED_SEEK, player.getCurrentState());
        assertEquals(42000, playback.getPosition());
        assertTrue(playback.getPlayWhenReady());
    }

    @Test
    public void oldReceiverUpdatesCannotPopulateTheNextQueuesPendingSource() {
        playback.onPlaybackChanged(1, 42000, 120000, 1);
        playback.onPlayerReset();
        playback.onPlaybackChanged(1, 60000, 120000, 1);
        assertEquals(0, playback.getPosition());
        assertEquals(0, playback.getDuration());
        assertTrue(playback.isPreparing());
    }

    @Test
    public void oneItemRepeatAllRestartsWithoutWaitingForNewMetadata() throws Exception {
        final StreamInfo info = new StreamInfo(0, "https://example.invalid/repeat",
                "https://example.invalid/repeat", StreamType.VIDEO_STREAM, "repeat", "Repeat", 0);
        final Field castField = Player.class.getDeclaredField("casting");
        castField.setAccessible(true);
        castField.set(player, playback);
        final Field queueField = Player.class.getDeclaredField("playQueue");
        queueField.setAccessible(true);
        queueField.set(player, new SinglePlayQueue(info));
        when(player.getRepeatMode()).thenReturn(REPEAT_MODE_ALL);
        doCallRealMethod().when(player).onCastingEnded();
        playback.onPlaybackChanged(1, 120000, 120000, 1);
        playback.onEnded();
        assertEquals(0, playback.getPosition());
        assertTrue(playback.getPlayWhenReady());
        assertTrue(playback.isActive());
        assertFalse(playback.isPreparing());
    }

    @Test
    public void bufferingDoesNotTurnAPausedLoadIntoAPlayingOne() {
        playback.onPlaybackChanged(2, 42000, 120000, 1);
        playback.onPlaybackChanged(3, 42000, 120000, 1);
        assertTrue(playback.isPreparing());
        assertFalse(playback.getPlayWhenReady());
    }

    @Test
    public void controlsDuringReceiverSwitchOnlyChangeThePendingLoad() {
        playback.onPlaybackChanged(1, 42000, 120000, 1);
        playback.connect("other-receiver.example", 46899);
        final FCastSession session = playback.getSession();
        when(session.getConnectionState()).thenReturn(1);
        clearInvocations(session);
        playback.pause();
        playback.play();
        playback.seek(65000);
        playback.setSpeed(2);
        verify(session, never()).pause();
        verify(session, never()).resume();
        verify(session, never()).seek(anyLong());
        verify(session, never()).setSpeed(anyFloat());
        assertTrue(playback.getPlayWhenReady());
        assertEquals(65000, playback.getPosition());
        assertEquals(2, playback.getParameters().speed, 0);
    }

    @Test
    public void previousReceiverCallbacksCannotCancelOrCompleteASwitch() {
        playback.onPlaybackChanged(1, 42000, 120000, 1);
        playback.connect("other-receiver.example", 46899);
        playback.onPlaybackChanged(1, 90000, 120000, 1);
        playback.onEnded();
        playback.onStopped();
        now.addAndGet(5000);
        assertTrue(playback.isActive());
        assertTrue(playback.isPreparing());
        assertEquals(42000, playback.getPosition());
        verify(player, never()).onCastingEnded();
    }

    @Test
    public void qualityPreparationKeepsNewSeekPauseAndSpeedForTheLoad() {
        playback.onPlaybackChanged(1, 42000, 120000, 1);
        final MediaItemTag replacement = newTag();
        when(player.getCurrentMetadata()).thenReturn(replacement);
        playback.onSourceChanged();
        assertTrue(playback.isPreparing());
        verify(playback.getSession()).pause();
        verify(player).onCastingStateChanged(Player.STATE_BUFFERING);
        playback.seek(65000);
        playback.pause();
        playback.setSpeed(2);
        playback.onPlaybackChanged(1, 90000, 120000, 1);
        io.triggerActions();
        verify(playback.getSession()).load(media, 65000, 2, false, "Video", null);
        assertEquals(65000, playback.getPosition());
        assertFalse(playback.getPlayWhenReady());
    }

    @Test
    public void initialHandoffExposesTheActualPlaybackSpeedImmediately() throws Exception {
        set("active", false);
        set("loadedTag", null);
        playback.onSourceChanged();
        io.triggerActions();
        assertTrue(playback.isActive());
        assertEquals(1.5f, playback.getParameters().speed, 0);
        verify(player).onPlaybackParametersChanged(new PlaybackParameters(1.5f));
    }

    @Test
    public void pausingAfterCompletionWhileNextQueueLoadsDoesNotResumeIt() {
        playback.onPlaybackChanged(1, 120000, 120000, 1);
        playback.onEnded();
        playback.onPlayerReset();
        playback.pause();
        final MediaItemTag replacement = newTag();
        when(player.getCurrentMetadata()).thenReturn(replacement);
        playback.onSourceChanged();
        io.triggerActions();
        verify(playback.getSession()).load(media, 10000, 1, false, "Video", null);
        assertFalse(playback.getPlayWhenReady());
    }

    @Test
    public void pausedReplacementSyntheticPlayingPreservesIntentWithoutCorrectiveCallback() {
        // Actual policy/controller; the existing mock session is only the transport boundary.
        // Keep this regression on the original policy API for unchanged-baseline reproduction.
        final FCastPlaybackState receiver = receiver(false);
        receiver.setLegacyCompletionWithoutEcho(true);
        receiver.load(source("previous"), 42000, 1, false);
        receiver.sourceChanged(source("previous"));
        receiver.durationChanged(120);
        receiver.playbackStateChanged(PlaybackState.PAUSED);
        final MediaItemTag replacement = newTag();
        when(player.getCurrentMetadata()).thenReturn(replacement);
        playback.onSourceChanged();
        io.triggerActions();
        verify(playback.getSession()).load(media, 42000, 1, false, "Video", null);
        receiver.load(source("replacement"), 42000, 1, false);
        receiver.emit();
        receiver.sourceChanged(source("replacement"));
        clearInvocations(player, playback.getSession());

        receiver.playbackStateChanged(PlaybackState.PLAYING);
        // Do not feed the corrective Paused callback back into either real component.
        verify(playback.getSession()).pause();
        assertFalse(playback.getPlayWhenReady());
        assertFalse(playback.isPreparing());
        verify(player).onCastingStateChanged(Player.STATE_PAUSED);
        verify(player, never()).onCastingStateChanged(Player.STATE_PLAYING);
        now.addAndGet(5000);
        assertEquals(42000, playback.getPosition());

        final MediaItemTag anotherReplacement = newTag();
        when(player.getCurrentMetadata()).thenReturn(anotherReplacement);
        playback.onSourceChanged();
        clearInvocations(playback.getSession());
        io.triggerActions();
        verify(playback.getSession()).load(media, 42000, 1, false, "Video", null);
        playback.connect("other-receiver.example", 46899);
        now.addAndGet(5000);
        assertFalse(playback.getPlayWhenReady());
        assertTrue(playback.isPreparing());
        assertEquals(42000, playback.getPosition());
        clearInvocations(playback.getSession());
        playback.onConnectionChanged();
        io.triggerActions();
        verify(playback.getSession()).load(media, 42000, 1, false, "Video", null);
    }

    @Test
    public void legacyPausedReplacementDoesNotNeedACorrectivePausedCallback() {
        assertPausedStartupRetainsIntent(true, false, false);
    }

    @Test
    public void sdkV3NoEchoPausedReplacementDoesNotNeedACorrectivePausedCallback() {
        assertPausedStartupRetainsIntent(false, true, false);
    }

    @Test
    public void v4NoEchoPausedReplacementDoesNotNeedACorrectivePausedCallback() {
        assertPausedStartupRetainsIntent(false, false, false);
    }

    @Test
    public void v4SourceEchoPausedReplacementDoesNotNeedACorrectivePausedCallback() {
        assertPausedStartupRetainsIntent(false, false, true);
    }

    @Test
    public void firstSourceEchoPreservesPauseDespiteAnEarlierPausedObservation() {
        final StartupReceiver receiver = new StartupReceiver(false, true, true);
        playback.onPlaybackChanged(2, 42000, 120000, 1);
        beginReplacement();
        io.triggerActions();
        // Before learning source echoes, the policy must accept no-echo playback evidence.
        receiver.state.playbackStateChanged(PlaybackState.PAUSED);
        receiver.acknowledge();
        clearInvocations(player, playback.getSession());
        receiver.state.playbackStateChanged(PlaybackState.PLAYING);
        verify(playback.getSession()).pause();
        assertPausedClockAndControls();

        receiver.state.playbackStateChanged(PlaybackState.PLAYING);
        clearInvocations(playback.getSession());
        receiver.acknowledge();
        receiver.state.playbackStateChanged(PlaybackState.PLAYING);
        assertTrue(playback.getPlayWhenReady());
        verify(playback.getSession(), never()).pause();
    }

    @Test
    public void resumeBeforeFirstSourceEchoOverridesTheOriginalPausedLoad() {
        final StartupReceiver receiver = new StartupReceiver(false, true, true);
        playback.onPlaybackChanged(2, 42000, 120000, 1);
        beginReplacement();
        io.triggerActions();
        receiver.state.playbackStateChanged(PlaybackState.PAUSED);
        playback.play();
        receiver.acknowledge();
        clearInvocations(player, playback.getSession());
        receiver.state.playbackStateChanged(PlaybackState.PLAYING);
        assertTrue(playback.getPlayWhenReady());
        now.addAndGet(2000);
        assertEquals(44000, playback.getPosition());
        verify(playback.getSession(), never()).pause();
    }

    @Test
    public void remotePlayingAfterAnEstablishedRebufferIsNotStartupPauseEvidence() {
        final StartupReceiver receiver = new StartupReceiver(false, false, true);
        receiver.establishPreviousSource(true);
        receiver.state.playbackStateChanged(PlaybackState.BUFFERING);
        playback.pause();
        clearInvocations(player, playback.getSession());
        receiver.state.playbackStateChanged(PlaybackState.PLAYING);
        assertTrue(playback.getPlayWhenReady());
        now.addAndGet(2000);
        assertEquals(44000, playback.getPosition());
        verify(player).onCastingStateChanged(Player.STATE_PLAYING);
        verify(playback.getSession(), never()).pause();
    }

    @Test
    public void explicitControlsDuringAcknowledgedBufferingUpdateStartupIntent() {
        final StartupReceiver receiver = new StartupReceiver(false, false, true);
        receiver.establishPreviousSource(true);
        beginReplacement();
        io.triggerActions();
        receiver.acknowledge();
        receiver.state.playbackStateChanged(PlaybackState.BUFFERING);
        playback.pause();
        clearInvocations(player, playback.getSession());
        receiver.state.playbackStateChanged(PlaybackState.PLAYING);
        verify(playback.getSession()).pause();
        assertPausedClockAndControls();

        beginReplacement();
        io.triggerActions();
        receiver.acknowledge();
        receiver.state.playbackStateChanged(PlaybackState.BUFFERING);
        playback.play();
        clearInvocations(player, playback.getSession());
        receiver.state.playbackStateChanged(PlaybackState.PLAYING);
        assertTrue(playback.getPlayWhenReady());
        assertFalse(playback.isPreparing());
        now.addAndGet(2000);
        assertEquals(44000, playback.getPosition());
        verify(player).onCastingStateChanged(Player.STATE_PLAYING);
        verify(playback.getSession(), never()).pause();
    }

    @Test
    public void explicitResumeAndPauseDuringPreparationUseTheLatestIntent() {
        for (final boolean[] mode : startupModes()) {
            final StartupReceiver receiver = new StartupReceiver(mode[0], mode[1], mode[2]);
            receiver.establishPreviousSource(false);
            beginReplacement();
            clearInvocations(playback.getSession());
            playback.play();
            verify(playback.getSession(), never()).resume();
            io.triggerActions();
            verify(playback.getSession()).load(media, 42000, 1, true, "Video", null);

            // Preparation has sent Play, but its matching source/state is still pending.
            playback.pause();
            verify(playback.getSession()).pause();
            receiver.acknowledge();
            clearInvocations(player);
            receiver.state.playbackStateChanged(PlaybackState.PLAYING);
            assertPausedClockAndControls();
            verify(playback.getSession(), never()).resume();
        }
    }

    @Test
    public void explicitPauseThenResumeDuringPreparationCancelsStartupPause() {
        for (final boolean[] mode : startupModes()) {
            final StartupReceiver receiver = new StartupReceiver(mode[0], mode[1], mode[2]);
            receiver.establishPreviousSource(true);
            beginReplacement();
            clearInvocations(playback.getSession());
            playback.pause();
            verify(playback.getSession(), never()).pause();
            io.triggerActions();
            verify(playback.getSession()).load(media, 42000, 1, false, "Video", null);

            playback.play();
            verify(playback.getSession()).resume();
            receiver.acknowledge();
            clearInvocations(player, playback.getSession());
            receiver.state.playbackStateChanged(PlaybackState.PLAYING);
            assertTrue(playback.getPlayWhenReady());
            assertFalse(playback.isPreparing());
            now.addAndGet(2000);
            assertEquals(44000, playback.getPosition());
            verify(player).onCastingStateChanged(Player.STATE_PLAYING);
            verify(playback.getSession(), never()).pause();
        }
    }

    private void assertPausedStartupRetainsIntent(final boolean legacy,
                                                  final boolean v3,
                                                  final boolean sourceEcho) {
        final StartupReceiver receiver = new StartupReceiver(legacy, v3, sourceEcho);
        receiver.establishPreviousSource(false);
        beginReplacement();
        io.triggerActions();
        verify(playback.getSession()).load(media, 42000, 1, false, "Video", null);
        receiver.acknowledge();
        clearInvocations(player, playback.getSession());

        // Observe startup Playing despite requesting a paused replacement. SDK-v3 source
        // echoes synthesize this callback; legacy/v4 modes exercise a receiver observation.
        // Intentionally never deliver the receiver's corrective Paused callback.
        receiver.state.playbackStateChanged(PlaybackState.PLAYING);
        verify(playback.getSession()).pause();
        assertPausedClockAndControls();

        beginReplacement();
        clearInvocations(playback.getSession());
        io.triggerActions();
        verify(playback.getSession()).load(media, 42000, 1, false, "Video", null);
        receiver.acknowledge();
        clearInvocations(player);
        receiver.state.playbackStateChanged(PlaybackState.PLAYING);
        assertPausedClockAndControls();

        playback.connect("other-receiver.example", 46899);
        assertFalse(playback.getPlayWhenReady());
        assertTrue(playback.isPreparing());
        now.addAndGet(2000);
        assertEquals(42000, playback.getPosition());
        // The mocked transport has no connection lifecycle; model the new session policy.
        receiver.resetConnection();
        clearInvocations(playback.getSession());
        playback.onConnectionChanged();
        io.triggerActions();
        verify(playback.getSession()).load(media, 42000, 1, false, "Video", null);
        receiver.acknowledge();
        clearInvocations(player);
        receiver.state.playbackStateChanged(PlaybackState.PLAYING);
        assertPausedClockAndControls();

        // Startup enforcement is one-shot, not a permanent veto of remote controls.
        receiver.state.playbackStateChanged(PlaybackState.PLAYING);
        assertTrue(playback.getPlayWhenReady());
        assertFalse(playback.isPreparing());
        now.addAndGet(2000);
        assertEquals(44000, playback.getPosition());
        receiver.state.timeChanged(44);
        receiver.state.playbackStateChanged(PlaybackState.PAUSED);
        clearInvocations(player);
        now.addAndGet(2000);
        assertEquals(44000, playback.getPosition());
        assertFalse(playback.getPlayWhenReady());
        receiver.state.playbackStateChanged(PlaybackState.PLAYING);
        assertTrue(playback.getPlayWhenReady());
        now.addAndGet(1000);
        assertEquals(45000, playback.getPosition());
    }

    private void beginReplacement() {
        final MediaItemTag replacement = newTag();
        when(player.getCurrentMetadata()).thenReturn(replacement);
        playback.onSourceChanged();
        assertTrue(playback.isPreparing());
    }

    private void assertPausedClockAndControls() {
        assertTrue(playback.isActive());
        assertFalse(playback.isPreparing());
        assertFalse(playback.getPlayWhenReady());
        assertEquals(42000, playback.getPosition());
        now.addAndGet(5000);
        assertEquals(42000, playback.getPosition());
        verify(player).onCastingStateChanged(Player.STATE_PAUSED);
        verify(player, never()).onCastingStateChanged(Player.STATE_PLAYING);
    }

    private static boolean[][] startupModes() {
        return new boolean[][]{
                {true, false, false},
                {false, true, false},
                {false, true, true},
                {false, false, false},
                {false, false, true}
        };
    }

    /**
     * Real playback controller and policy; only Android/SDK transport and media resolution
     * are mocked. Forward session commands at the same policy boundary as FCastSession.
     */
    private final class StartupReceiver {
        private final boolean legacy;
        private final boolean v3;
        private final boolean sourceEcho;
        private final FCastPlaybackState state;
        private Source pendingSource;
        private int loads;

        private StartupReceiver(final boolean legacy, final boolean v3,
                                final boolean sourceEcho) {
            this.legacy = legacy;
            this.v3 = v3;
            this.sourceEcho = sourceEcho;
            state = receiver(legacy);
            state.setLegacyCompletionWithoutEcho(v3);
            doAnswer(invocation -> {
                pendingSource = source("startup-" + ++loads);
                state.load(pendingSource, invocation.getArgument(1),
                        ((Float) invocation.getArgument(2)).doubleValue(),
                        invocation.getArgument(3));
                state.emit();
                return null;
            }).when(playback.getSession()).load(
                    any(), anyLong(), anyFloat(), anyBoolean(), anyString(), any());
            doAnswer(invocation -> {
                state.setPlayWhenReady(false);
                return null;
            }).when(playback.getSession()).pause();
            doAnswer(invocation -> {
                state.setPlayWhenReady(true);
                return null;
            }).when(playback.getSession()).resume();
        }

        private void establishPreviousSource(final boolean playing) {
            pendingSource = source("previous");
            state.load(pendingSource, 42000, 1, playing);
            acknowledge();
            state.durationChanged(120);
            state.playbackStateChanged(playing ? PlaybackState.PLAYING : PlaybackState.PAUSED);
            clearInvocations(player, playback.getSession());
        }

        private void acknowledge() {
            if (sourceEcho) {
                state.sourceChanged(pendingSource);
            }
        }

        private void resetConnection() {
            state.reset();
            state.setLegacyReceiver(legacy);
            state.setLegacyCompletionWithoutEcho(v3);
        }
    }

    @Test
    public void disconnectReturnsTheLatestReceiverPositionAndOnlyPlaysOnRequest() {
        playback.onPlaybackChanged(1, 42000, 120000, 1);
        now.addAndGet(1000);
        playback.stop(true);
        assertFalse(playback.isActive());
        verify(player).restoreLocalAfterCasting(43000, true);
        verify(playback.getSession()).disconnect(true);
    }

    @Test
    public void qualityChangeAfterCompletionDoesNotRestartTheVideo() {
        playback.onPlaybackChanged(1, 120000, 120000, 1);
        playback.onEnded();
        final MediaItemTag replacement = newTag();
        when(player.getCurrentMetadata()).thenReturn(replacement);
        playback.onSourceChanged();
        io.triggerActions();
        verify(playback.getSession()).load(media, 120000, 1, false, "Video", null);
        assertFalse(playback.getPlayWhenReady());
    }

    @Test
    public void disconnectCancelsPendingPreparationBeforeItCanLoadTheReceiver() {
        playback.onPlaybackChanged(1, 42000, 120000, 1);
        final MediaItemTag replacement = newTag();
        when(player.getCurrentMetadata()).thenReturn(replacement);
        playback.onSourceChanged();
        playback.stop(false);
        io.triggerActions();
        assertFalse(playback.isActive());
        verify(resolvers.constructed().get(0)).close();
        verify(playback.getSession(), never()).load(
                any(), anyLong(), anyFloat(), anyBoolean(), anyString(), any());
    }

    @Test
    public void rebuildingSourcesForTheSameQueueItemDoesNotResetTheRemoteClock() {
        playback.onPlaybackChanged(1, 42000, 120000, 1);
        now.addAndGet(1000);
        playback.onQueueItemChanged(player.getCurrentItem());
        assertEquals(43000, playback.getPosition());
        assertEquals(120000, playback.getDuration());
        assertFalse(playback.isPreparing());
        verify(playback.getSession(), never()).pause();
    }

    @Test
    public void anotherQueueOccurrenceInvalidatesOldCallbacksEvenForTheSameVideo() {
        playback.onPlaybackChanged(1, 42000, 120000, 1);
        playback.onQueueItemChanged(mock(PlayQueueItem.class));
        playback.onPlaybackChanged(1, 90000, 120000, 1);
        playback.onEnded();
        assertEquals(0, playback.getPosition());
        assertEquals(0, playback.getDuration());
        assertTrue(playback.isPreparing());
        verify(player, never()).onCastingEnded();
    }

    private FCastPlaybackState receiver(final boolean legacy) {
        final FCastPlaybackState receiver = new FCastPlaybackState(playback, () -> {
            playback.getSession().pause();
            return Unit.INSTANCE;
        });
        receiver.setLegacyReceiver(legacy);
        return receiver;
    }

    private static Source source(final String capability) {
        return new Source.Url("http://192.0.2.1:8080/" + capability, "video/mp4");
    }

    private static MediaItemTag newTag() {
        final MediaItemTag tag = mock(MediaItemTag.class);
        when(tag.getStreamUrl()).thenReturn("https://example.invalid/video");
        return tag;
    }

    private void set(final String name, final Object value) throws Exception {
        final Field field = CastPlayback.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(playback, value);
    }
}
