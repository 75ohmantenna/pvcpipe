package us.shandian.giga.get;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class DownloadMissionProgressTest {
    private DownloadMission mission() {
        return new DownloadMission(new String[]{"https://example.test/media"}, null, 'v', null);
    }

    private DownloadMission restore(final DownloadMission mission) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(mission);
        }
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            final DownloadMission loaded = (DownloadMission) input.readObject();
            loaded.restoreProgress();
            return loaded;
        }
    }

    @Test
    public void coldReplayDropsOnlyUnreleasedWorkerProgress() throws Exception {
        final DownloadMission mission = mission();
        mission.length = DownloadMission.BLOCK_SIZE * 3L;
        // Two workers have resumed valid prefixes, but their new writes have not been released.
        mission.blocks = new int[]{-1, 187, 311};
        mission.notifyProgress(DownloadMission.BLOCK_SIZE + 187 + 311);
        mission.notifyProgress(8192);
        mission.notifyProgress(16384);

        final DownloadMission loaded = restore(mission);

        assertEquals(DownloadMission.BLOCK_SIZE + 187 + 311, loaded.done);
        assertArrayEquals(new int[]{-1, 187, 311}, loaded.blocks);
        assertEquals(DownloadMission.BLOCK_SIZE + 187 + 311 + 8192 + 16384, mission.done);
        // Replaying those uncheckpointed writes advances logical progress exactly once.
        loaded.notifyProgress(8192 + 16384);
        assertEquals(mission.done, loaded.done);
    }

    @Test
    public void coldReplayKeepsEarlierResourcesAndExcludesReservedSpace() throws Exception {
        final DownloadMission mission = mission();
        mission.urls = new String[]{mission.urls[0], mission.urls[0]};
        mission.offsets = new long[]{4096, 4096 + 123};
        mission.current = 1;
        mission.length = DownloadMission.BLOCK_SIZE + 137L;
        mission.blocks = new int[]{-1, 43};
        mission.notifyProgress(123 + DownloadMission.BLOCK_SIZE + 43 + 64);

        final DownloadMission loaded = restore(mission);

        assertEquals(123 + DownloadMission.BLOCK_SIZE + 43, loaded.done);
        assertArrayEquals(mission.offsets, loaded.offsets);
        assertArrayEquals(mission.blocks, loaded.blocks);
    }

    @Test
    public void alignedCompletedBlocksIncludeOnlyTheShortFinalBlock() throws Exception {
        final DownloadMission mission = mission();
        mission.length = DownloadMission.BLOCK_SIZE + 137L;
        mission.blocks = new int[]{-1, -1};
        mission.notifyProgress(mission.length);

        assertEquals(mission.length, restore(mission).done);
    }

    @Test
    public void coldFallbackKeepsItsSavedPrefixNotItsInFlightWrites() throws Exception {
        final DownloadMission mission = mission();
        mission.length = DownloadMission.BLOCK_SIZE;
        mission.blocks = new int[0];
        mission.fallbackResumeOffset = 187;
        mission.notifyProgress(187 + 8192);

        final DownloadMission loaded = restore(mission);

        assertEquals(187, loaded.done);
        assertEquals(187, loaded.fallbackResumeOffset);
    }

    @Test
    public void coldUninitializedResourceRetainsOnlyEarlierResources() throws Exception {
        final DownloadMission mission = mission();
        mission.urls = new String[]{mission.urls[0], mission.urls[0]};
        mission.offsets = new long[]{4096, 4096 + 123};
        mission.current = 1;
        mission.notifyProgress(123);

        assertEquals(123, restore(mission).done);
    }

    @Test
    public void restorationDoesNotRewritePostprocessingProgress() throws Exception {
        final DownloadMission mission = mission();
        mission.current = mission.urls.length;
        mission.blocks = new int[]{-1, -1};
        mission.length = DownloadMission.BLOCK_SIZE * 2L;
        mission.notifyProgress(187);
        mission.psState = 1;

        assertEquals(187, restore(mission).done);
    }
}
