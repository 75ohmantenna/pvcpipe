package us.shandian.giga.postprocessing;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Test-only subprocess runner. File output avoids filling an unread pipe before the deadline. */
public final class BoundedTestProcess {
    private BoundedTestProcess() {
    }

    public static String run(final ProcessBuilder builder, final File directory,
                             final long timeoutMillis) throws Exception {
        final File output = File.createTempFile("packaging-process-", ".log", directory);
        try {
            final Process process = builder.redirectErrorStream(true)
                    .redirectOutput(output).start();
            return await(process, output, timeoutMillis);
        } finally {
            if (!output.delete() && output.exists()) {
                throw new IOException("Could not remove process output " + output);
            }
        }
    }

    static String await(final Process process, final File output, final long timeoutMillis)
            throws Exception {
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Process exceeded " + timeoutMillis + " ms");
            }
            // Diagnostic output only: do not load an arbitrarily large file into test memory.
            final byte[] bytes = new byte[64 * 1024];
            final int count;
            try (FileInputStream input = new FileInputStream(output)) {
                count = input.read(bytes);
            }
            final String text = new String(bytes, 0, Math.max(0, count), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new AssertionError("Process exited " + process.exitValue() + ": " + text);
            }
            return text;
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (process.isAlive() && System.nanoTime() < deadline) {
                    try {
                        process.waitFor(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (process.isAlive()) {
                    throw new AssertionError("Process did not terminate after forced cleanup");
                }
            } finally {
                try {
                    process.getInputStream().close();
                    process.getErrorStream().close();
                    process.getOutputStream().close();
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }
}
