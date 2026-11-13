package org.schabi.newpipe.util.external_communication;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import java.io.File;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Latest user share wins, with one preparation and at most one pending preparation. */
final class SharePreviewCoordinator {
    private final Executor worker;
    private final Consumer<Runnable> main;
    private final ArrayDeque<File> discarded = new ArrayDeque<>();
    private Request active;
    private Request pending;
    private boolean draining;

    SharePreviewCoordinator(final Executor worker, final Consumer<Runnable> main) {
        this.worker = worker;
        this.main = main;
    }

    /** Called on the main thread, including for immediate text-only shares. */
    synchronized void cancel() {
        if (active != null) {
            active.detach();
            if (active.file != null) {
                discarded.add(active.file);
                active.file = null;
            }
            active.prepare = null;
            active.deliver = null;
            active.valid = null;
            active = null;
        }
        pending = null;
        schedule();
    }

    // Source validity and lifecycle are read only on the main thread.
    synchronized void submit(@Nullable final LifecycleOwner owner,
                             final BooleanSupplier valid,
                             final Function<BooleanSupplier, File> prepare,
                             final Consumer<File> deliver) {
        cancel();
        final Request request = new Request(owner, valid, prepare, deliver);
        active = request;
        if (owner != null) {
            request.observer = (source, event) -> {
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP
                        || event == Lifecycle.Event.ON_DESTROY) {
                    synchronized (SharePreviewCoordinator.this) {
                        if (active == request) {
                            cancel();
                        }
                    }
                }
            };
            owner.getLifecycle().addObserver(request.observer);
        }
        if (!request.isValid()) {
            cancel();
            return;
        }
        pending = request;
        schedule();
    }

    private void schedule() {
        if (!draining && (pending != null || !discarded.isEmpty())) {
            draining = true;
            worker.execute(this::drain);
        }
    }

    private void drain() {
        while (true) {
            final Request request;
            final Function<BooleanSupplier, File> prepare;
            final File discardedFile;
            synchronized (this) {
                discardedFile = discarded.poll();
                request = discardedFile == null ? pending : null;
                if (discardedFile == null) {
                    pending = null;
                    if (request == null) {
                        draining = false;
                        return;
                    }
                    prepare = request.prepare;
                    request.prepare = null;
                } else {
                    prepare = null;
                }
            }
            // Do not hold the main-thread coordination lock during filesystem calls.
            if (discardedFile != null) {
                discardedFile.delete();
                continue;
            }
            File result = null;
            try {
                result = prepare == null ? null : prepare.apply(() -> {
                    synchronized (this) {
                        return active == request;
                    }
                });
            } catch (final RuntimeException error) {
                Log.w("SharePreviewCoordinator", "Cannot prepare preview", error);
            }
            synchronized (this) {
                if (active != request) {
                    if (result != null) {
                        discarded.add(result);
                    }
                } else {
                    request.file = result;
                    main.accept(() -> finish(request));
                }
            }
        }
    }

    private synchronized void finish(final Request request) {
        if (active != request) {
            return;
        }
        if (!request.isValid()) {
            cancel();
            return;
        }
        final Consumer<File> deliver = request.deliver;
        final File file = request.file;
        // The chooser itself pauses its source. Transfer ownership before launching it.
        request.file = null;
        request.detach();
        request.deliver = null;
        request.valid = null;
        active = null;
        deliver.accept(file);
    }

    private static final class Request {
        private final LifecycleOwner owner;
        private LifecycleEventObserver observer;
        private BooleanSupplier valid;
        private Function<BooleanSupplier, File> prepare;
        private Consumer<File> deliver;
        private File file;

        Request(final LifecycleOwner owner, final BooleanSupplier valid,
                final Function<BooleanSupplier, File> prepare, final Consumer<File> deliver) {
            this.owner = owner;
            this.valid = valid;
            this.prepare = prepare;
            this.deliver = deliver;
        }

        boolean isValid() {
            return valid != null && valid.getAsBoolean() && (owner == null
                    || owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED));
        }

        void detach() {
            if (owner != null && observer != null) {
                owner.getLifecycle().removeObserver(observer);
                observer = null;
            }
        }
    }
}
