package org.schabi.newpipe.extractor.stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@Tag("offline")
class FramesetTest {
    @Test
    void locatesEveryFrameInSquareAndRectangularStoryboards() {
        for (int columns = 1; columns <= 10; columns++) {
            for (int rows = 1; rows <= 10; rows++) {
                final int perSheet = columns * rows;
                final Frameset frames = frames(2 * perSheet, columns, rows);
                int frame = 0;
                for (int sheet = 0; sheet < 2; sheet++) {
                    for (int row = 0; row < rows; row++) {
                        for (int column = 0; column < columns; column++) {
                            assertArrayEquals(new int[] {sheet, column * 100, row * 50,
                                    (column + 1) * 100, (row + 1) * 50},
                                    frames.getFrameBoundsAt(frame++ * 1000L));
                        }
                    }
                }
            }
        }
    }

    @Test
    void clampsEndPositionsToTheLastExistingFrame() {
        final Frameset fullSheet = frames(6, 3, 2);
        final int[] lastFull = {0, 200, 50, 300, 100};
        assertArrayEquals(lastFull, fullSheet.getFrameBoundsAt(5999));
        assertArrayEquals(lastFull, fullSheet.getFrameBoundsAt(6000));
        assertArrayEquals(lastFull, fullSheet.getFrameBoundsAt(7000));

        final Frameset partialSheet = frames(8, 3, 2);
        assertArrayEquals(new int[] {1, 100, 0, 200, 50},
                partialSheet.getFrameBoundsAt(8000));
        assertArrayEquals(new int[] {0, 0, 0, 100, 50},
                frames(1, 1, 1).getFrameBoundsAt(1000));
    }

    @Test
    void keepsTheFirstFrameFallbackForOutOfRangePositions() {
        final Frameset frames = frames(6, 3, 2);
        final int[] first = {0, 0, 0, 100, 50};
        assertArrayEquals(first, frames.getFrameBoundsAt(-1));
        assertArrayEquals(first, frames.getFrameBoundsAt(7001));
        assertArrayEquals(first, frames.getFrameBoundsAt(Long.MAX_VALUE));
    }

    @Test
    void avoidsOverflowInFrameCountsAndPageSizes() {
        final Frameset frames = new Frameset(List.of("sheet"), 1, 1,
                Integer.MAX_VALUE, 1, 50_000, 50_000);
        assertArrayEquals(new int[] {0, 1, 0, 2, 1}, frames.getFrameBoundsAt(1));
        assertArrayEquals(new int[] {0, 33_646, 42_949, 33_647, 42_950},
                frames.getFrameBoundsAt(Integer.MAX_VALUE + 1L));
    }

    @Test
    void usesTheFallbackForEmptyOrInvalidTimingAndGridMetadata() {
        final int[] first = {0, 0, 0, 100, 50};
        assertArrayEquals(first, frames(0, 3, 2).getFrameBoundsAt(0));
        assertArrayEquals(first, frames(6, 0, 2).getFrameBoundsAt(0));
        assertArrayEquals(first, frames(6, 3, 0).getFrameBoundsAt(0));
        assertArrayEquals(first, new Frameset(List.of("sheet"), 100, 50,
                6, 0, 3, 2).getFrameBoundsAt(0));
    }

    private static Frameset frames(final int count, final int columns, final int rows) {
        return new Frameset(List.of("first", "second"), 100, 50,
                count, 1000, columns, rows);
    }
}
