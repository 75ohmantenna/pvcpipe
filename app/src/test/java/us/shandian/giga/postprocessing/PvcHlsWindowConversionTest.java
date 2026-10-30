package us.shandian.giga.postprocessing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;

import com.github.evermindzz.hlsdownloader.parser.HlsParser;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.streams.io.SharpStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.MissionRecoveryInfo;
import us.shandian.giga.io.ChunkFileInputStream;
import us.shandian.giga.io.FileStream;
import us.shandian.giga.preprocessing.HlsDownloadPlan;
import us.shandian.giga.preprocessing.PvcHlsPreProcessor;

/** Exercises the same staging, decryption and combiner path as production conversion. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class PvcHlsWindowConversionTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void consumesExactWindowsAndKeepsRepeatedPhysicalResourceOpen() throws Exception {
        final DownloadMission parsed = prepare("#EXTM3U\n#EXT-X-TARGETDURATION:1\n"
                + "#EXTINF:1,\n#EXT-X-BYTERANGE:4@2\nshared.ts\n"
                + "#EXTINF:1,\n#EXT-X-BYTERANGE:3\nshared.ts\n"
                + "#EXTINF:1,\n#EXT-X-BYTERANGE:2@1\nother.ts\n#EXT-X-ENDLIST\n");
        assertEquals(2, parsed.urls.length);
        assertWindowBytes((HlsDownloadPlan) parsed.pvcArbitraryData, "ABCD", "XYZ", "12");
    }

    @Test
    public void serializedPlanConsumesTheSameWindows() throws Exception {
        final ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        try (ObjectOutputStream objects = new ObjectOutputStream(serialized)) {
            objects.writeObject(rangePlan());
        }
        final HlsDownloadPlan restored;
        try (ObjectInputStream objects = new ObjectInputStream(
                new ByteArrayInputStream(serialized.toByteArray()))) {
            restored = (HlsDownloadPlan) objects.readObject();
        }
        assertWindowBytes(restored, "ABCD", "XYZ", "12");
    }

    @Test
    public void legacyCheckpointMigratesOnceWithoutChangingPhysicalLayout() throws Exception {
        final DownloadMission original = new DownloadMission(new String[]{
                "https://media.test/shared.ts", "https://media.test/shared.ts",
                "https://media.test/other.ts"}, null, 'v', new PvcFromHlsRemuxer(workParent()));
        original.pvcArbitraryData = Arrays.asList(media("shared.ts", null),
                media("shared.ts", null), media("other.ts", null));
        original.offsets = new long[]{0, 13, 26};
        original.recoveryInfo = new MissionRecoveryInfo[3];
        original.current = 2;
        final DownloadMission mission = (DownloadMission) roundTrip(original);
        final String[] urls = mission.urls;
        final long[] offsets = mission.offsets;
        final MissionRecoveryInfo[] recovery = mission.recoveryInfo;
        final FileStream first = source("xxABCDXYZtail");
        final FileStream second = source("xxABCDXYZtail");
        final FileStream third = source("q12tail");
        try (FileStream output = output()) {
            convertAndInspect(mission, new SharpStream[]{first, second, third}, output,
                    "xxABCDXYZtail", "xxABCDXYZtail", "q12tail");
            final Object migrated = mission.pvcArbitraryData;
            assertTrue(migrated instanceof HlsDownloadPlan);
            convertAndInspect(mission, new SharpStream[]{first, second, third}, output,
                    "xxABCDXYZtail", "xxABCDXYZtail", "q12tail");
            assertSame(migrated, mission.pvcArbitraryData);
            assertSame(urls, mission.urls);
            assertSame(offsets, mission.offsets);
            assertSame(recovery, mission.recoveryInfo);
            assertEquals(2, mission.current);
        } finally {
            first.close();
            second.close();
            third.close();
        }
    }

    @Test
    public void allPhysicalReadsFinishBeforeInPlaceOutputStarts() throws Exception {
        final File storage = temporary.newFile();
        Files.writeString(storage.toPath(), "xxABCDXYZtailq12tail");
        try (SharpStream first = new ChunkFileInputStream(new FileStream(storage), 0, 13, null);
             SharpStream second = new ChunkFileInputStream(new FileStream(storage), 13, 20, null);
             FileStream output = new FileStream(storage)) {
            convertAndInspect(mission(rangePlan()), new SharpStream[]{first, second}, output,
                    "ABCD", "XYZ", "12");
            output.rewind();
            assertEquals('c', output.read());
            assertFalse(first.isClosed());
            assertFalse(second.isClosed());
        }
    }

    @Test
    public void explicitIvLegacyEncryptedCheckpointStillDecrypts() throws Exception {
        final byte[] key = new byte[16];
        final byte[] iv = new byte[16];
        iv[14] = 1;
        iv[15] = 44;
        final HlsParser.EncryptionInfo encryption = new HlsParser.EncryptionInfo("AES-128",
                URI.create("https://media.test/key"), "0000000000000000000000000000012c");
        encryption.setKey(key);
        final Object restored = roundTrip(List.of(media("shared.ts", encryption)));
        try (FileStream input = source(encrypt("legacy sequence300", key, iv));
             FileStream output = output()) {
            convertAndInspect(mission(restored), new SharpStream[]{input}, output,
                    "legacy sequence300");
            assertFalse(input.isClosed());
        }
    }


    @Test
    public void rejectsTruncatedOutOfBoundsOverflowAndInvalidResourceWindows() throws Exception {
        for (final HlsDownloadPlan.Window window : Arrays.asList(
                new HlsDownloadPlan.Window(0, 10, 4),
                malformedWindow(0, Long.MAX_VALUE, 2),
                new HlsDownloadPlan.Window(1, 0, 1),
                malformedWindow(-1, 0, 1),
                malformedWindow(0, -1, 1),
                malformedWindow(0, 1, -1),
                malformedWindow(0, 0, -2))) {
            try (FileStream input = source("xxABCDXYZtail"); FileStream output = output()) {
                final DownloadMission mission = mission(new HlsDownloadPlan(List.of(
                        new HlsDownloadPlan.Segment(media("shared.ts", null), window, null))));
                assertThrows(IOException.class, () -> PvcFromHlsRemuxer.convert(mission,
                        new SharpStream[]{input}, output, workParent(), arguments -> {
                            throw new AssertionError("Invalid window reached FFmpeg");
                        }));
                assertEquals(0, output.length());
                assertFalse(input.isClosed());
            }
        }
        // Reported physical bounds can still hide a truncated backing source. Reading fails,
        // not ordinary EOF, even after the declared window has passed the initial bounds check.
        final File bytes = temporary.newFile();
        Files.writeString(bytes.toPath(), "abc");
        try (FileStream input = new FileStream(bytes) {
            @Override
            public long available() {
                return 8;
            }
        }; FileStream output = output()) {
            final DownloadMission mission = mission(plan(0, 0, 8));
            assertThrows(IOException.class, () -> PvcFromHlsRemuxer.convert(mission,
                    new SharpStream[]{input}, output, workParent(), arguments -> 0));
            assertEquals(0, output.length());
        }
    }

    @Test
    public void slicesCiphertextBeforeDecryptingSequence300AndEncryptedMap() throws Exception {
        final byte[] key = new byte[16];
        Arrays.fill(key, (byte) 19);
        final byte[] iv = new byte[16];
        iv[14] = 1;
        iv[15] = 44;
        final HlsParser.EncryptionInfo encryption = new HlsParser.EncryptionInfo("AES-128",
                URI.create("https://media.test/key"), "0000000000000000000000000000012c");
        encryption.setKey(key);
        final byte[] encryptedMap = encrypt("INIT", key, iv);
        final byte[] encryptedMedia = encrypt("sequence300", key, iv);
        final ByteArrayOutputStream physical = new ByteArrayOutputStream();
        physical.write(new byte[]{99, 98});
        physical.write(encryptedMap);
        physical.write(encryptedMedia);
        physical.write(new byte[]{97, 96});
        final HlsDownloadPlan.Initialization initialization = new HlsDownloadPlan.Initialization(
                media("shared.ts", encryption), new HlsDownloadPlan.Window(0, 2,
                encryptedMap.length));
        final HlsDownloadPlan plan = new HlsDownloadPlan(List.of(new HlsDownloadPlan.Segment(
                media("shared.ts", encryption), new HlsDownloadPlan.Window(0,
                2 + encryptedMap.length, encryptedMedia.length), initialization)));
        try (FileStream input = source(physical.toByteArray()); FileStream output = output()) {
            convertAndInspect(mission(plan), new SharpStream[]{input}, output, "INITsequence300");
            assertFalse(input.isClosed());
        }
    }

    @Test
    public void implicitIvLegacyCheckpointFailsRatherThanInventingSequence() throws Exception {
        final HlsParser.EncryptionInfo encryption = new HlsParser.EncryptionInfo("AES-128",
                URI.create("https://media.test/key"), null);
        encryption.setKey(new byte[16]);
        final DownloadMission mission = mission(List.of(media("shared.ts", encryption)));
        final long[] offsets = {42};
        mission.offsets = offsets;
        try (FileStream input = source(new byte[16]); FileStream output = output()) {
            final IOException failure = assertThrows(IOException.class,
                    () -> PvcFromHlsRemuxer.convert(mission, new SharpStream[]{input}, output,
                            workParent(), arguments -> 0));
            assertTrue(failure.getMessage().contains("explicit 128-bit IV"));
            assertSame(offsets, mission.offsets);
            assertEquals(0, output.length());
        }
    }

    @Test
    public void nonzeroFfmpegStatusCannotCopyPartialOutput() throws Exception {
        try (FileStream input = source("ABCD"); FileStream output = source("untouched")) {
            final IOException failure = assertThrows(IOException.class,
                    () -> PvcFromHlsRemuxer.convert(mission(plan(0, 0, 4)),
                            new SharpStream[]{input}, output, workParent(), arguments -> {
                                final File partial = new File(arguments[arguments.length - 1]);
                                Files.writeString(partial.toPath(), "partial");
                                return 7;
                            }));
            assertTrue(failure.getMessage().contains("FFmpeg exited with status 7"));
            output.rewind();
            final byte[] retained = new byte[9];
            assertEquals(9, output.read(retained));
            assertArrayEquals("untouched".getBytes(StandardCharsets.UTF_8), retained);
            assertFalse(output.isClosed());
            assertFalse(input.isClosed());
        }
    }

    @Test
    public void interruptedConversionNeverCopiesOutput() throws Exception {
        try (FileStream input = source("ABCD"); FileStream output = output()) {
            assertThrows(InterruptedException.class,
                    () -> PvcFromHlsRemuxer.convert(mission(plan(0, 0, 4)),
                            new SharpStream[]{input}, output, workParent(), arguments -> {
                                throw new InterruptedException("cancelled");
                            }));
            assertEquals(0, output.length());
            assertFalse(input.isClosed());
        }
    }

    @Test
    public void realHostFfmpegConvertsTransportStreamWindows() throws Exception {
        proveRealConversion("kick-ts.ts", "kick-ts.m3u8");
    }

    @Test
    public void realHostFfmpegConvertsFragmentedMp4WindowsWithMap() throws Exception {
        proveRealConversion("kick-fmp4.mp4", "kick-fmp4.m3u8");
    }

    @Test
    public void realHostFfmpegFailureLeavesOutputUnchanged() throws Exception {
        try (FileStream input = source("not transport stream media");
             FileStream output = source("untouched")) {
            final IOException failure = assertThrows(IOException.class,
                    () -> PvcFromHlsRemuxer.convert(mission(plan(0, 0, -1)),
                            new SharpStream[]{input}, output, workParent(), arguments -> {
                                final List<String> command = new ArrayList<>();
                                command.add("ffmpeg");
                                command.addAll(Arrays.asList(arguments));
                                return run(command).status;
                            }));
            assertTrue(failure.getMessage().contains("FFmpeg exited with status"));
            assertEquals(9, output.length());
            output.rewind();
            assertEquals('u', output.read());
        }
    }

    private void proveRealConversion(final String name, final String playlist) throws Exception {
        final File fixture = fixture(name);
        final DownloadMission prepared = prepare(Files.readString(fixture(playlist).toPath()));
        assertEquals(1, prepared.urls.length);
        final File completed = temporary.newFile("completed-" + name + ".mp4");
        try (FileStream source = new FileStream(fixture);
             FileStream output = new FileStream(completed)) {
            PvcFromHlsRemuxer.convert(prepared,
                    new SharpStream[]{source}, output, workParent(), arguments -> {
                        final List<String> command = new ArrayList<>();
                        command.add("ffmpeg");
                        command.addAll(Arrays.asList(arguments));
                        return run(command).status;
                    });
            assertFalse(source.isClosed());
            assertFalse(output.isClosed());
        }
        final CommandResult probe = run(Arrays.asList("ffprobe", "-v", "error",
                "-show_entries", "stream=codec_type:format=duration", "-of",
                "default=noprint_wrappers=1", completed.getAbsolutePath()));
        assertEquals(probe.text, 0, probe.status);
        System.out.println("HLS host conversion " + name + ":\n" + probe.text);
        assertTrue(probe.text, probe.text.contains("codec_type=audio"));
        assertTrue(probe.text, probe.text.contains("codec_type=video"));
        final String duration = Arrays.stream(probe.text.split("\\R"))
                .filter(line -> line.startsWith("duration="))
                .map(line -> line.substring("duration=".length())).findFirst().orElseThrow();
        assertEquals(8.0, Double.parseDouble(duration), 0.25);
    }

    private static File fixture(final String name) {
        final File fromModule = new File("src/androidTest/assets/media3-engine", name);
        final File fromRoot = new File("app/src/androidTest/assets/media3-engine", name);
        final File result = fromModule.isFile() ? fromModule : fromRoot;
        assertTrue("Missing generated fixture " + result, result.isFile());
        return result;
    }

    private CommandResult run(final List<String> command) throws IOException, InterruptedException {
        final File log = temporary.newFile();
        final Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log).start();
        try {
            assertTrue("Host media command timed out: " + command.get(0),
                    process.waitFor(30, TimeUnit.SECONDS));
            return new CommandResult(process.exitValue(), Files.readString(log.toPath()));
        } finally {
            process.destroyForcibly();
        }
    }

    private static final class CommandResult {
        private final int status;
        private final String text;

        CommandResult(final int status, final String text) {
            this.status = status;
            this.text = text;
        }
    }

    private void assertWindowBytes(final HlsDownloadPlan plan, final String... expected)
            throws Exception {
        try (FileStream shared = source("xxABCDXYZtail"); FileStream other = source("q12tail");
             FileStream output = output()) {
            convertAndInspect(mission(plan), new SharpStream[]{shared, other}, output, expected);
            assertFalse(shared.isClosed());
            assertFalse(other.isClosed());
            assertFalse(output.isClosed());
            shared.rewind();
            assertEquals('x', shared.read());
        }
    }

    private void convertAndInspect(final DownloadMission mission, final SharpStream[] sources,
                                   final SharpStream output, final String... expected)
            throws Exception {
        PvcFromHlsRemuxer.convert(mission, sources, output, workParent(), arguments -> {
            final File completed = new File(arguments[arguments.length - 1]);
            for (int index = 0; index < expected.length; index++) {
                assertEquals(expected[index], Files.readString(new File(completed.getParentFile(),
                        "segment_" + index + ".ts").toPath()));
            }
            for (final SharpStream source : sources) {
                assertFalse(source.isClosed());
            }
            Files.writeString(completed.toPath(), "completed");
            return 0;
        });
    }

    private File workParent() {
        return new File(temporary.getRoot(), "work");
    }

    private FileStream output() throws IOException {
        return new FileStream(temporary.newFile());
    }

    private FileStream source(final String text) throws IOException {
        return source(text.getBytes(StandardCharsets.UTF_8));
    }

    private FileStream source(final byte[] bytes) throws IOException {
        final File file = temporary.newFile();
        Files.write(file.toPath(), bytes);
        return new FileStream(file);
    }

    private static DownloadMission mission(final Object data) {
        final DownloadMission mission = mock(DownloadMission.class);
        mission.pvcArbitraryData = data;
        return mission;
    }

    private static Object roundTrip(final Object value) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            return input.readObject();
        }
    }

    private static HlsDownloadPlan.Window malformedWindow(final int resource, final long offset,
                                                           final long length) {
        // Emulate malformed deserialized metadata: constructors reject these values, but a
        // checkpoint read does not invoke constructors. The consumer must still defend itself.
        final HlsDownloadPlan.Window window = mock(HlsDownloadPlan.Window.class);
        when(window.getResourceIndex()).thenReturn(resource);
        when(window.getOffset()).thenReturn(offset);
        when(window.getLength()).thenReturn(length);
        return window;
    }

    private static DownloadMission prepare(final String manifest) throws Exception {
        final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
                new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(ResponseBody.create(
                                MediaType.get("application/vnd.apple.mpegurl"), manifest)).build())
                .build();
        final Constructor<PvcHlsPreProcessor> constructor =
                PvcHlsPreProcessor.class.getDeclaredConstructor(OkHttpClient.class);
        constructor.setAccessible(true);
        final DownloadMission mission = mission(null);
        mission.urls = new String[]{"https://media.test/playlist.m3u8"};
        mission.recoveryInfo = new MissionRecoveryInfo[1];
        constructor.newInstance(client).modifyMission(mission);
        return mission;
    }

    private static HlsDownloadPlan rangePlan() {
        return new HlsDownloadPlan(Arrays.asList(
                new HlsDownloadPlan.Segment(media("shared.ts", null),
                        new HlsDownloadPlan.Window(0, 2, 4), null),
                new HlsDownloadPlan.Segment(media("shared.ts", null),
                        new HlsDownloadPlan.Window(0, 6, 3), null),
                new HlsDownloadPlan.Segment(media("other.ts", null),
                        new HlsDownloadPlan.Window(1, 1, 2), null)));
    }

    private static HlsDownloadPlan plan(final int resource, final long offset, final long length) {
        return new HlsDownloadPlan(List.of(new HlsDownloadPlan.Segment(media("shared.ts", null),
                new HlsDownloadPlan.Window(resource, offset, length), null)));
    }

    private static HlsParser.Segment media(final String name,
                                           final HlsParser.EncryptionInfo encryption) {
        return new HlsParser.Segment(URI.create("https://media.test/" + name), 2, "", encryption);
    }

    private static byte[] encrypt(final String text, final byte[] key, final byte[] iv)
            throws Exception {
        final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }
}
