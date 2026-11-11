package us.shandian.giga.postprocessing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;

public class BoundedTestProcessTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 20000)
    public void normalChildPreservesOutputAndRemovesFile() throws Exception {
        assertEquals("ffmpeg version fixture", BoundedTestProcess.run(child("normal"),
                temporary.getRoot(), 10000));
        assertEquals(0, temporary.getRoot().list().length);
    }

    @Test(timeout = 20000)
    public void outputLargerThanPipeBufferDoesNotDeadlock() throws Exception {
        assertEquals(64 * 1024, BoundedTestProcess.run(child("verbose"),
                temporary.getRoot(), 10000).length());
        assertEquals(0, temporary.getRoot().list().length);
    }

    @Test(timeout = 20000)
    public void nonzeroChildFailsAndRemovesFile() {
        final AssertionError error = assertThrows(AssertionError.class,
                () -> BoundedTestProcess.run(child("nonzero"), temporary.getRoot(), 10000));
        assertTrue(error.getMessage(), error.getMessage().contains("exited 7"));
        assertEquals(0, temporary.getRoot().list().length);
    }

    @Test(timeout = 20000)
    public void hungChildIsKilledAndReapedWithinDeadline() throws Exception {
        final File output = temporary.newFile();
        final Process process = startHung(output);
        final long start = System.nanoTime();
        final AssertionError error = assertThrows(AssertionError.class,
                () -> BoundedTestProcess.await(process, output, 100));
        assertTrue(error.getMessage(), error.getMessage().contains("exceeded"));
        assertFalse(process.isAlive());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(8));
    }

    @Test(timeout = 20000)
    public void interruptedWaitStillKillsAndReapsChild() throws Exception {
        final File output = temporary.newFile();
        final Process process = startHung(output);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                    () -> BoundedTestProcess.await(process, output, 10000));
            assertFalse(process.isAlive());
        } finally {
            Thread.interrupted();
        }
    }

    private Process startHung(final File output) throws Exception {
        final Process process = child("hung").redirectOutput(output).start();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (System.nanoTime() < deadline) {
                if (Files.readString(output.toPath()).contains("ready")) {
                    return process;
                }
                Thread.sleep(10);
            }
            throw new AssertionError("Child did not reach its controlled stall");
        } catch (Throwable error) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw error;
        }
    }

    private ProcessBuilder child(final String mode) throws Exception {
        return new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                "-cp", new File(Child.class.getProtectionDomain().getCodeSource().getLocation()
                        .toURI()).getPath(), Child.class.getName(), mode);
    }

    public static final class Child {
        private Child() {
        }

        public static void main(final String[] args) throws Exception {
            switch (args[0]) {
                case "hung":
                    System.out.print("ready");
                    System.out.flush();
                    Thread.sleep(60000);
                    break;
                case "nonzero":
                    System.out.print("controlled failure");
                    System.exit(7);
                    break;
                case "verbose":
                    System.out.print("x".repeat(1024 * 1024));
                    break;
                default:
                    System.out.print("ffmpeg version fixture");
            }
        }
    }
}
