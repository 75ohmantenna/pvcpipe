package org.schabi.newpipe.fragments.detail;

/**
 * The rules for one scrolling view inside the detail screen's pager when "scroll only below
 * player" is in play: whether the view scrolls on its own, and which height it had before the
 * pager started sizing it.
 * <p>
 * It holds no views, so it can be tested without Android.
 */
public final class PagerScrollPolicy {
    /**
     * Marks "no height recorded". It is neither WRAP_CONTENT (-2) nor MATCH_PARENT (-1) and must
     * never be set on layout params.
     */
    public static final int UNSET = -3;

    private int originalHeight = UNSET;

    /**
     * @param landscape             whether the device is in landscape orientation
     * @param scrollOnlyBelowPlayer the user's "scroll only below player" setting
     * @return whether the view scrolls by itself under a fixed player, instead of scrolling
     * together with the whole screen
     */
    public static boolean scrollsBelowPlayer(final boolean landscape,
                                             final boolean scrollOnlyBelowPlayer) {
        return !landscape && scrollOnlyBelowPlayer;
    }

    /**
     * Remembers the height the view had before its first resize.
     *
     * @param currentHeight the view's current layout height
     * @param newHeight     the height the pager asks for
     * @return the height to set on the view
     */
    public int resizeTo(final int currentHeight, final int newHeight) {
        if (originalHeight == UNSET) {
            originalHeight = currentHeight;
        }
        return newHeight;
    }

    /**
     * Forgets the remembered height.
     *
     * @return the height to give back to the view, or {@link #UNSET} if it was never resized
     */
    public int takeOriginalHeight() {
        final int height = originalHeight;
        originalHeight = UNSET;
        return height;
    }
}
