package org.schabi.newpipe.util.external_communication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 26, manifest = Config.NONE)
public class SharePreviewCoordinatorTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();
    private final ArrayDeque<Runnable> worker = new ArrayDeque<>();
    private final ArrayDeque<Runnable> main = new ArrayDeque<>();
    private final SharePreviewCoordinator coordinator =
            new SharePreviewCoordinator(worker::add, main::add);
    private final List<File> delivered = new ArrayList<>();

    @Test
    public void repeatedTapsKeepOnlyOnePendingPreparation() {
        final AtomicInteger prepared = new AtomicInteger();
        for (int i = 0; i < 1000; i++) {
            coordinator.submit(null, () -> true, active -> {
                prepared.incrementAndGet();
                return null;
            }, delivered::add);
        }
        assertEquals(1, worker.size());
        assertEquals(0, prepared.get());
        worker.remove().run();
        assertEquals(1, prepared.get());
        assertTrue(delivered.isEmpty());
        main.remove().run();
        assertEquals(1, delivered.size());
    }

    @Test
    public void newerShareFencesQueuedMainCallbackAndDeletesOnlyUnpublishedFile() throws Exception {
        final File old = temporary.newFile();
        final File latest = temporary.newFile();
        coordinator.submit(null, () -> true, active -> old, delivered::add);
        worker.remove().run();
        coordinator.submit(null, () -> true, active -> latest, delivered::add);
        assertTrue(old.exists()); // no filesystem work on main
        worker.remove().run();
        assertFalse(old.exists());
        while (!main.isEmpty()) {
            main.remove().run();
        }
        assertEquals(List.of(latest), delivered);
        coordinator.cancel();
        assertTrue(latest.exists());
    }

    @Test
    public void replacementDuringPreparationCancelsProducerAndKeepsOnlyLatest() throws Exception {
        final File old = temporary.newFile();
        coordinator.submit(null, () -> true, active -> {
            assertTrue(active.getAsBoolean());
            coordinator.submit(null, () -> true, newer -> null, delivered::add);
            assertFalse(active.getAsBoolean());
            return old;
        }, file -> {
            throw new AssertionError("stale launch");
        });
        worker.remove().run();
        assertFalse(old.exists());
        main.remove().run();
        assertEquals(1, delivered.size());
    }

    @Test
    public void pausedSourceDiscardsResultAndObserver() throws Exception {
        final Owner owner = new Owner();
        final File file = temporary.newFile();
        coordinator.submit(owner, () -> true, active -> file, delivered::add);
        worker.remove().run();
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        main.remove().run();
        worker.remove().run();
        assertFalse(file.exists());
        assertTrue(delivered.isEmpty());
        assertEquals(0, owner.registry.getObserverCount());
    }

    @Test
    public void chooserPauseCannotDeletePublishedPreview() throws Exception {
        final Owner owner = new Owner();
        final File file = temporary.newFile();
        coordinator.submit(owner, () -> true, active -> file, result -> {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
            delivered.add(result);
        });
        worker.remove().run();
        main.remove().run();
        assertEquals(List.of(file), delivered);
        assertEquals(0, owner.registry.getObserverCount());
        assertTrue(file.exists());
    }

    @Test
    public void sameViewNewContentInvalidatesPendingShare() throws Exception {
        final AtomicBoolean valid = new AtomicBoolean(true);
        final File file = temporary.newFile();
        coordinator.submit(null, valid::get, active -> file, delivered::add);
        worker.remove().run();
        valid.set(false);
        main.remove().run();
        worker.remove().run();
        assertFalse(file.exists());
        assertTrue(delivered.isEmpty());
    }

    @Test
    public void textOnlyCancellationDoesNotLaunchPreviousPreview() {
        coordinator.submit(null, () -> true, active -> {
            throw new AssertionError("canceled preparation");
        }, delivered::add);
        coordinator.cancel();
        worker.remove().run();
        assertTrue(main.isEmpty());
        assertTrue(delivered.isEmpty());
    }

    @Test
    public void failedPreparationFallsBackAndDoesNotWedgeWorker() {
        coordinator.submit(null, () -> true, active -> {
            throw new IllegalArgumentException("fixture");
        }, delivered::add);
        worker.remove().run();
        main.remove().run();
        coordinator.submit(null, () -> true, active -> null, delivered::add);
        worker.remove().run();
        main.remove().run();
        assertEquals(2, delivered.size());
    }

    @Test
    public void inactiveOwnerDoesNotEnqueueAndRemovesObserver() {
        final Owner owner = new Owner();
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        coordinator.submit(owner, () -> true, active -> null, delivered::add);
        assertTrue(worker.isEmpty());
        assertEquals(0, owner.registry.getObserverCount());
    }

    private static final class Owner implements LifecycleOwner {
        private final LifecycleRegistry registry = new LifecycleRegistry(this);

        Owner() {
            registry.setCurrentState(Lifecycle.State.RESUMED);
        }

        @Override
        public Lifecycle getLifecycle() {
            return registry;
        }
    }
}
