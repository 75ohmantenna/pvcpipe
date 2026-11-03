package us.shandian.giga.get;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.util.Log;

import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public class DownloadMissionRecoverTest {
    @Test
    public void serializedAacMissionRecoversOnlyMatchingProgressiveFormatAndBitrate()
            throws Exception {
        final AudioStream original = audio("old.aac", MediaFormat.AAC, 128,
                DeliveryMethod.PROGRESSIVE_HTTP);
        final MissionRecoveryInfo descriptor = roundTrip(new MissionRecoveryInfo(original));
        final AudioStream renewed = audio("renewed.aac", MediaFormat.AAC, 128,
                DeliveryMethod.PROGRESSIVE_HTTP);
        final DownloadMission mission = mission(descriptor);

        resolve(mission, descriptor, List.of(
                audio("old-label.m4a", MediaFormat.M4A, 128, DeliveryMethod.PROGRESSIVE_HTTP),
                audio("wrong-bitrate.aac", MediaFormat.AAC, 192, DeliveryMethod.PROGRESSIVE_HTTP),
                audio("playlist.m3u8", MediaFormat.AAC, 128, DeliveryMethod.HLS), renewed));

        assertArrayEquals(new String[]{renewed.getContent()}, mission.urls);
        assertSame(descriptor, mission.recoveryInfo[0]);
        assertEquals(MediaFormat.AAC, descriptor.getFormat());
        assertEquals(128, descriptor.getDesiredBitrate());
        assertEquals('a', descriptor.getKind());
        verify(mission).writeThisToFile();
        verify(mission).recoveryFinished();
        verify(mission, never()).resetState(anyBoolean(), anyBoolean(), anyInt());
    }

    @Test
    public void staleM4aDescriptorDoesNotAliasNewRawAacOrChangeRecoveryMetadata()
            throws Exception {
        final MissionRecoveryInfo descriptor = roundTrip(new MissionRecoveryInfo(
                audio("mislabelled.aac", MediaFormat.M4A, 128,
                        DeliveryMethod.PROGRESSIVE_HTTP)));
        assertNoMatch(descriptor, audio("corrected.aac", MediaFormat.AAC, 128,
                DeliveryMethod.PROGRESSIVE_HTTP));
        assertEquals(MediaFormat.M4A, descriptor.getFormat());
        assertEquals(128, descriptor.getDesiredBitrate());
    }

    @Test
    public void aacDescriptorDoesNotAdoptAnotherBitrate() throws Exception {
        final MissionRecoveryInfo descriptor = roundTrip(new MissionRecoveryInfo(
                audio("old.aac", MediaFormat.AAC, 128, DeliveryMethod.PROGRESSIVE_HTTP)));
        assertNoMatch(descriptor, audio("different.aac", MediaFormat.AAC, 192,
                DeliveryMethod.PROGRESSIVE_HTTP));
        assertEquals(MediaFormat.AAC, descriptor.getFormat());
        assertEquals(128, descriptor.getDesiredBitrate());
    }

    private static void assertNoMatch(final MissionRecoveryInfo descriptor,
                                      final AudioStream candidate) throws Exception {
        final DownloadMission mission = mission(descriptor);
        mission.done = 187;
        mission.fallbackResumeOffset = 187;
        mission.length = 1000;

        resolve(mission, descriptor, List.of(candidate));

        assertEquals(0, mission.urls.length);
        assertSame(descriptor, mission.recoveryInfo[0]);
        assertEquals(187, mission.done);
        assertEquals(187, mission.fallbackResumeOffset);
        assertEquals(1000, mission.length);
        assertNull(descriptor.getValidateCondition());
        verify(mission).notifyError(DownloadMission.ERROR_RESOURCE_GONE, null);
        verify(mission, never()).recoveryFinished();
        verify(mission, never()).writeThisToFile();
        verify(mission, never()).resetState(anyBoolean(), anyBoolean(), anyInt());
    }

    private static DownloadMission mission(final MissionRecoveryInfo descriptor) {
        final DownloadMission mission = mock(DownloadMission.class);
        mission.storage = mock(StoredFileHelper.class);
        mission.urls = new String[]{"https://media.example/expired.aac"};
        mission.recoveryInfo = new MissionRecoveryInfo[]{descriptor};
        mission.blocks = new int[]{0};
        mission.running = true;
        return mission;
    }

    private static void resolve(final DownloadMission mission,
                                final MissionRecoveryInfo descriptor,
                                final List<AudioStream> streams) throws Exception {
        final StreamExtractor extractor = mock(StreamExtractor.class);
        when(extractor.getAudioStreams()).thenReturn(streams);
        final DownloadMissionRecover recovery = new DownloadMissionRecover(mission,
                DownloadMission.ERROR_HTTP_FORBIDDEN);
        final Field extractorField = DownloadMissionRecover.class.getDeclaredField("mExtractor");
        extractorField.setAccessible(true);
        extractorField.set(recovery, extractor);
        final Field recoveryField = DownloadMissionRecover.class.getDeclaredField("mRecovery");
        recoveryField.setAccessible(true);
        recoveryField.set(recovery, descriptor);
        final Method resolve = DownloadMissionRecover.class.getDeclaredMethod("resolveStream");
        resolve.setAccessible(true);
        try (var logging = mockStatic(Log.class)) {
            resolve.invoke(recovery);
        }
    }

    private static MissionRecoveryInfo roundTrip(final MissionRecoveryInfo descriptor)
            throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(descriptor);
        }
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            return (MissionRecoveryInfo) input.readObject();
        }
    }

    private static AudioStream audio(final String path, final MediaFormat format,
                                     final int bitrate, final DeliveryMethod delivery) {
        return new AudioStream.Builder().setId(path)
                .setContent("https://media.example/" + path, true).setMediaFormat(format)
                .setAverageBitrate(bitrate).setDeliveryMethod(delivery).build();
    }
}
