package us.shandian.giga.get;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import org.junit.Test;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import us.shandian.giga.util.Utility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DownloadMissionLifecycleTest {
    private DownloadMission mission() {
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        when(storage.existsAsFile()).thenReturn(true);
        when(storage.canWrite()).thenReturn(true);
        final DownloadMission mission = new DownloadMission(
                new String[]{"https://example.test/video"},
                storage, 'v', null);
        mission.mHandler = mock(Handler.class);
        when(mission.mHandler.obtainMessage(anyInt(), any())).thenReturn(mock(Message.class));
        return mission;
    }

    @Test
    public void publishEveryWorkerBeforeAnyWorkerCanFail() {
        try (var logging = mockStatic(Log.class)) {
            final DownloadMission original = mission();
            final DownloadMission mission = spy(original);
            mission.blocks = new int[]{0, 0};
            final AtomicInteger started = new AtomicInteger();
            doAnswer(call -> new Thread() {
                @Override public synchronized void start() {
                    assertEquals(2, mission.threads.length);
                    for (final Thread worker : mission.threads) {
                        assertNotNull(worker);
                    }
                    started.incrementAndGet();
                    mission.notifyError(DownloadMission.ERROR_FILE_CREATION, null);
                }
            }).when(mission).createDownloadWorker(anyInt());
            mission.start();
            assertEquals(2, started.get());
            assertFalse(mission.running);
        }
    }

    @Test
    public void concurrentStartsReserveOnlyOneInitializer() throws Exception {
        final DownloadMission mission = spy(mission());
        final AtomicInteger created = new AtomicInteger();
        doAnswer(call -> {
            created.incrementAndGet();
            return new Thread();
        })
                .when(mission).createInitializer();
        final CountDownLatch start = new CountDownLatch(1);
        final Thread first = new Thread(() -> {
            await(start);
            mission.start();
        });
        final Thread second = new Thread(() -> {
            await(start);
            mission.start();
        });
        first.start(); second.start(); start.countDown();
        first.join(5000); second.join(5000);
        assertFalse(first.isAlive()); assertFalse(second.isAlive());
        assertEquals(1, created.get());
    }

    @Test
    public void pauseCancelsRestartWhileOldWorkerStillOwnsState() throws Exception {
        final DownloadMission mission = spy(mission());
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread old = new Thread(() -> {
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (final InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        old.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
        mission.threads = new Thread[]{old};
        mission.start();
        assertFalse(mission.running);
        mission.pause();
        release.countDown(); old.join(5000);
        verify(mission, never()).createInitializer();
        assertFalse(mission.running);
    }

    @Test
    public void resettingStaleRecoveryStateKeepsItsOwnerForHandoff() {
        try (var logging = mockStatic(Log.class)) {
            final DownloadMission mission = spy(mission());
            final Thread[] owners = new Thread[]{Thread.currentThread()};
            mission.threads = owners;
            mission.running = true;
            doAnswer(call -> new Thread()).when(mission).createInitializer();
            mission.resetState(false, false, DownloadMission.ERROR_NOTHING);
            assertSame(owners, mission.threads);
            mission.recoveryFinished();
            verify(mission).createInitializer();
        }
    }

    @Test
    public void completionSavesFinalCheckpointBeforeNotifyingManager() {
        final DownloadMission mission = mission();
        final File checkpoint = new File("checkpoint");
        mission.metadata = checkpoint;
        mission.current = mission.urls.length;
        try (var utility = mockStatic(Utility.class)) {
            utility.when(() -> Utility.writeToFile(checkpoint, mission)).thenReturn(true);
            when(mission.mHandler.obtainMessage(
                    eq(us.shandian.giga.service.DownloadManagerService.MESSAGE_FINISHED),
                    same(mission))).thenAnswer(call -> {
                        utility.verify(() -> Utility.writeToFile(checkpoint, mission));
                        return mock(Message.class);
                    });
            mission.notifyFinished();
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (final InterruptedException e) {
            throw new AssertionError(e);
        }
    }
}
