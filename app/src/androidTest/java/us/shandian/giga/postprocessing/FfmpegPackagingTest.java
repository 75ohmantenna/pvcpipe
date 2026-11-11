package us.shandian.giga.postprocessing;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.github.evermindzz.slimhls.converter.RunFFmpeg;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * The HLS remuxer runs {@code libffmpeg.so} as a program. That only works if the installer
 * extracted it, which depends on how the build packages native libraries.
 */
@RunWith(AndroidJUnit4.class)
public class FfmpegPackagingTest {
    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void ffmpegIsInstalledAsAFile() {
        final File ffmpeg = new File(RunFFmpeg.getFfmpegPath(context));
        assertTrue(ffmpeg + " was not extracted at installation", ffmpeg.isFile());
        assertTrue(ffmpeg + " cannot be executed", ffmpeg.canExecute());
    }

    @Test
    public void ffmpegRuns() throws Exception {
        final String output = BoundedTestProcess.run(
                new ProcessBuilder(RunFFmpeg.getFfmpegPath(context), "-version"),
                context.getCacheDir(), 30000);
        assertTrue(output, output.contains("ffmpeg version"));
    }
}
