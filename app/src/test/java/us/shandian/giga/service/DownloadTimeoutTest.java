package us.shandian.giga.service;

import android.app.ActivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.app.Application;
import android.content.Intent;

import androidx.core.app.ServiceCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import org.schabi.newpipe.player.helper.LockManager;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import us.shandian.giga.get.DownloadMission;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class DownloadTimeoutTest {
    private DownloadManager manager(final DownloadMission... missions) {
        final DownloadManager manager = mock(DownloadManager.class, CALLS_REAL_METHODS);
        ReflectionHelpers.setField(manager, "mMissionsPending",
                new ArrayList<>(List.of(missions)));
        ReflectionHelpers.setField(manager, "mHandler", mock(Handler.class));
        ReflectionHelpers.setField(manager, "serviceAttached", true);
        return manager;
    }

    @Test
    public void timeoutStopsServiceWithoutWaitingForDestructionAndIsIdempotent() {
        final DownloadManagerService service =
                mock(DownloadManagerService.class, CALLS_REAL_METHODS);
        final DownloadManager manager = mock(DownloadManager.class);
        ReflectionHelpers.setField(service, "mManager", manager);
        doNothing().when(service).cancelHlsPreparation();
        doNothing().when(service).stopSelf();
        try (var compat = mockStatic(ServiceCompat.class)) {
            service.onTimeout(1, 1);
            service.onTimeout(1, 1);
            verify(manager).suspendForTimeout();
            verify(service).stopSelf();
            compat.verify(() -> ServiceCompat.stopForeground(service,
                    ServiceCompat.STOP_FOREGROUND_REMOVE));
        } finally {
            ReflectionHelpers.setStaticField(DownloadManagerService.class,
                    "timeoutRecoveryManager", null);
        }
    }

    @Test
    public void slowCheckpointCannotBlockTimeoutAndNoAdmissionReopensInBackground()
            throws Exception {
        final DownloadMission mission = mock(DownloadMission.class);
        mission.checkpointReady = true;
        mission.enqueued = true;
        final DownloadManager manager = manager(mission);
        final CountDownLatch pausing = new CountDownLatch(1);
        final CountDownLatch finishPause = new CountDownLatch(1);
        doAnswer(call -> {
            pausing.countDown();
            assertTrue(finishPause.await(5, TimeUnit.SECONDS));
            return null;
        }).when(mission).pause();
        try (var activity = mockStatic(ActivityManager.class)) {
            activity.when(() -> ActivityManager.getMyMemoryState(any())).thenAnswer(call -> {
                final ActivityManager.RunningAppProcessInfo process = call.getArgument(0);
                process.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;
                return null;
            });
            manager.suspendForTimeout();
            assertTrue(pausing.await(5, TimeUnit.SECONDS));
            assertTrue(manager.isTimeoutSuspended());
            manager.resumeMission(mission);
            manager.startAllMissions();
            manager.handleConnectivityState(DownloadManager.NetworkState.Operating, false);
            assertFalse(manager.runMissions());
        } finally {
            finishPause.countDown();
        }
        final CompletableFuture<Void> pause = ReflectionHelpers.getField(manager, "timeoutPause");
        pause.get(5, TimeUnit.SECONDS);
        verify(mission, never()).start();
        try (var activity = mockStatic(ActivityManager.class)) {
            activity.when(() -> ActivityManager.getMyMemoryState(any())).thenAnswer(call -> {
                final ActivityManager.RunningAppProcessInfo process = call.getArgument(0);
                process.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                return null;
            });
            manager.resumeMission(mission);
            verify(mission).start();
            assertFalse(manager.isTimeoutSuspended());
        }
    }

    @Test
    public void timeoutDoesNotInterruptDestructivePostprocessing() throws Exception {
        final DownloadMission converting = mock(DownloadMission.class);
        org.mockito.Mockito.when(converting.isPsRunning()).thenReturn(true);
        final DownloadManager manager = manager(converting);
        manager.suspendForTimeout();
        final CompletableFuture<Void> pause = ReflectionHelpers.getField(manager, "timeoutPause");
        pause.get(5, TimeUnit.SECONDS);
        verify(converting, never()).pause();
        final Handler next = mock(Handler.class);
        manager.attachHandler(next);
        org.junit.Assert.assertSame(next, converting.mHandler);
        assertTrue(manager.isTimeoutSuspended());
    }
    @Test
    public void detachedManagerStillRecordsFinishedMissions() {
        final DownloadMission mission = mock(DownloadMission.class);
        final DownloadManager manager = manager(mission);
        org.mockito.Mockito.doReturn(true).when(manager).setFinished(mission);
        manager.attachHandler(null);
        final Message finished = mission.mHandler.obtainMessage(
                DownloadManagerService.MESSAGE_FINISHED, mission);
        finished.sendToTarget();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(manager).setFinished(mission);
    }

    @Test
    public void eligibleBoundResumeRestoresStartedServiceBeforeForeground() {
        final DownloadManagerService service =
                mock(DownloadManagerService.class, CALLS_REAL_METHODS);
        final DownloadManager manager = mock(DownloadManager.class);
        ReflectionHelpers.setField(service, "mManager", manager);
        ReflectionHelpers.setField(service, "needsStartedLifetime", true);
        ReflectionHelpers.setField(service, "timedOut", true);
        ReflectionHelpers.setField(service, "mLock", mock(LockManager.class));
        org.mockito.Mockito.doReturn("org.schabi.newpipe").when(service).getPackageName();
        org.mockito.Mockito.doReturn(null).when(service).startService(any(Intent.class));
        doNothing().when(service).startForeground(org.mockito.Mockito.anyInt(), any());
        service.updateForegroundState(true);
        final var order = org.mockito.Mockito.inOrder(service);
        order.verify(service).startService(any(Intent.class));
        order.verify(service).startForeground(org.mockito.Mockito.anyInt(), any());
        assertFalse(ReflectionHelpers.getField(service, "needsStartedLifetime"));
    }

    @Test
    public void acceptedIntentQueuedBeforeTimeoutIsRegisteredWithoutReopeningAdmission() {
        final DownloadManagerService service =
                mock(DownloadManagerService.class, CALLS_REAL_METHODS);
        ReflectionHelpers.setField(service, "mManager", mock(DownloadManager.class));
        ReflectionHelpers.setField(service, "mHandler", new Handler(Looper.getMainLooper()));
        doNothing().when(service).cancelHlsPreparation();
        doNothing().when(service).stopSelf();
        doNothing().when(service).startMission(any(), org.mockito.Mockito.anyBoolean());
        final Intent intent = new Intent(Intent.ACTION_RUN);
        try (var compat = mockStatic(ServiceCompat.class)) {
            service.onStartCommand(intent, 0, 1);
            service.onTimeout(1, 1);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            verify(service).startMission(intent, false);
        } finally {
            ReflectionHelpers.setStaticField(DownloadManagerService.class,
                    "timeoutRecoveryManager", null);
        }
    }

}
