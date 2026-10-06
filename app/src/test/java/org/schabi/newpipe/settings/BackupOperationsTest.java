package org.schabi.newpipe.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.schedulers.TestScheduler;

public class BackupOperationsTest {
    @Test
    public void acceptedIoSurvivesViewDisposalAndRejectsOverlap() {
        final TestScheduler io = new TestScheduler();
        final BackupOperations operations = new BackupOperations(io);
        final AtomicInteger writes = new AtomicInteger();
        final var first = operations.submit(writes::incrementAndGet).test();
        first.dispose();
        operations.submit(writes::incrementAndGet).test()
                .assertError(IllegalStateException.class);
        assertEquals(0, writes.get());
        io.triggerActions();
        assertEquals(1, writes.get());
        final var next = operations.submit(writes::incrementAndGet).test();
        io.triggerActions();
        next.assertValue(2);
    }

    @Test
    public void terminalRestartRunsExactlyOnceEvenWhenViewIsDestroyed() {
        final TestScheduler io = new TestScheduler();
        final BackupOperations operations = new BackupOperations(io);
        final AtomicInteger restarts = new AtomicInteger();
        final var restore = operations.submit(() -> true)
                .doOnSuccess(ignored -> restarts.incrementAndGet()).cache();
        restore.test().dispose();
        io.triggerActions();
        restore.test().assertValue(true);
        assertEquals(1, restarts.get());
    }

    @Test
    public void failedOperationReleasesAdmissionForRetry() {
        final TestScheduler io = new TestScheduler();
        final BackupOperations operations = new BackupOperations(io);
        final var failed = operations.submit(() -> {
            throw new java.io.IOException("provider failed");
        }).test();
        io.triggerActions();
        failed.assertError(java.io.IOException.class);
        final var retry = operations.submit(() -> true).test();
        io.triggerActions();
        retry.assertValue(true);
    }
}
