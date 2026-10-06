package org.schabi.newpipe.settings;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** Application-owned backup IO; disposal detaches UI observers without interrupting a restore. */
final class BackupOperations {
    static final BackupOperations INSTANCE = new BackupOperations(Schedulers.from(
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "BackupIO");
                thread.setDaemon(true);
                return thread;
            })));
    private final Scheduler scheduler;
    private final AtomicBoolean running = new AtomicBoolean();

    BackupOperations(final Scheduler ioScheduler) {
        scheduler = ioScheduler;
    }

    <T> Single<T> submit(final Callable<T> operation) {
        return Single.defer(() -> {
            if (!running.compareAndSet(false, true)) {
                return Single.error(new IllegalStateException("A backup operation is in progress"));
            }
            return Single.fromCallable(operation).subscribeOn(scheduler)
                    .doOnSuccess(ignored -> running.set(false))
                    .doOnError(error -> running.set(false));
        }).cache();
    }
}
