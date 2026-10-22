package org.schabi.newpipe.extractor.stream;

import java.io.Serializable;
import java.util.List;

/**
 * Class to handle framesets / storyboards which summarize the stream content.
 */
public final class Frameset implements Serializable {

    private final List<String> urls;
    private final int frameWidth;
    private final int frameHeight;
    private final int totalCount;
    private final int durationPerFrame;
    private final int framesPerPageX;
    private final int framesPerPageY;

    /**
     * Creates a new Frameset or set of storyboards.
     * @param urls the URLs to the images with frames / storyboards
     * @param frameWidth the width of a single frame, in pixels
     * @param frameHeight the height of a single frame, in pixels
     * @param totalCount the total count of frames
     * @param durationPerFrame the duration per frame in milliseconds
     * @param framesPerPageX the maximum count of frames per page by x / over the width of the image
     * @param framesPerPageY the maximum count of frames per page by y / over the height
     *                       of the image
     */
    public Frameset(
            final List<String> urls,
            final int frameWidth,
            final int frameHeight,
            final int totalCount,
            final int durationPerFrame,
            final int framesPerPageX,
            final int framesPerPageY) {

        this.urls = urls;
        this.totalCount = totalCount;
        this.durationPerFrame = durationPerFrame;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.framesPerPageX = framesPerPageX;
        this.framesPerPageY = framesPerPageY;
    }

    /**
     * Get the image URLs containing the frames.
     *
     * @return list of URLs to images with frames
     */
    public List<String> getUrls() {
        return urls;
    }

    /**
     * Get the total number of frames.
     *
     * @return total count of frames
     */
    public int getTotalCount() {
        return totalCount;
    }

    /**
     * Get the maximum number of frames per page horizontally.
     *
     * @return maximum frames count by x
     */
    public int getFramesPerPageX() {
        return framesPerPageX;
    }

    /**
     * Get the maximum number of frames per page vertically.
     *
     * @return maximum frames count by y
     */
    public int getFramesPerPageY() {
        return framesPerPageY;
    }

    /**
     * Get the width of a frame in pixels.
     *
     * @return width of a one frame, in pixels
     */
    public int getFrameWidth() {
        return frameWidth;
    }

    /**
     * Get the height of a frame in pixels.
     *
     * @return height of a one frame, in pixels
     */
    public int getFrameHeight() {
        return frameHeight;
    }

    /**
     * Get the duration of a frame in milliseconds.
     *
     * @return duration per frame in milliseconds
     */
    public int getDurationPerFrame() {
        return durationPerFrame;
    }

    /**
     * Returns the information for the frame at stream position.
     *
     * @param position Position in milliseconds
     * @return An <code>int</code>-array containing the bounds and URL where the indexes are
     * specified as follows:
     *
     * <ul>
     *     <li><code>0</code>: Index of the URL</li>
     *     <li><code>1</code>: Left bound</li>
     *     <li><code>2</code>: Top bound</li>
     *     <li><code>3</code>: Right bound</li>
     *     <li><code>4</code>: Bottom bound</li>
     * </ul>
     */
    public int[] getFrameBoundsAt(final long position) {
        if (totalCount <= 0 || durationPerFrame <= 0
                || framesPerPageX <= 0 || framesPerPageY <= 0
                || position < 0 || position > ((totalCount + 1L) * durationPerFrame)) {
            // Return the first frame as fallback
            return new int[] {0, 0, 0, frameWidth, frameHeight};
        }

        final long framesPerStoryboard = (long) framesPerPageX * framesPerPageY;
        final long absoluteFrameNumber = Math.min(position / durationPerFrame, totalCount - 1L);

        final long relativeFrameNumber = absoluteFrameNumber % framesPerStoryboard;

        final int rowIndex = (int) (relativeFrameNumber / framesPerPageX);
        final int columnIndex = (int) (relativeFrameNumber % framesPerPageX);

        return new int[] {
                /* storyboardIndex */ (int) (absoluteFrameNumber / framesPerStoryboard),
                /* left */ columnIndex * frameWidth,
                /* top */ rowIndex * frameHeight,
                /* right */ columnIndex * frameWidth + frameWidth,
                /* bottom */ rowIndex * frameHeight + frameHeight };
    }
}
