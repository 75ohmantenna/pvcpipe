package org.schabi.newpipe.fragments.detail;

import androidx.annotation.NonNull;

/**
 * The orientation and fullscreen decisions of the detail screen.
 * <p>
 * It only sees facts about the device and the player, so it can be tested without Android.
 * {@link VideoDetailFragment} reads the facts and carries out what is decided here. The main
 * player UI decides about vertical videos by itself.
 */
final class DetailOrientation {

    /** What the player's screen rotation button does. */
    enum RotationButtonAction {
        TOGGLE_FULLSCREEN,
        REQUEST_PORTRAIT,
        REQUEST_SENSOR_LANDSCAPE
    }

    private DetailOrientation() {
    }

    /**
     * Android TV cannot rotate the screen. On a tablet it is better not to rotate from landscape
     * to portrait every time, so there the button only toggles fullscreen while the device is
     * in landscape or the system orientation is unlocked. Everywhere else the button rotates
     * the activity, and the rotation brings fullscreen with it.
     *
     * @param tv                whether the device is a TV
     * @param tablet            whether the device is a tablet
     * @param orientationLocked whether auto-rotate is off in the system settings
     * @param landscape         whether the device is in landscape orientation
     * @return what the button does
     */
    @NonNull
    static RotationButtonAction onRotationButton(final boolean tv, final boolean tablet,
                                                 final boolean orientationLocked,
                                                 final boolean landscape) {
        if (tv || tablet && (!orientationLocked || landscape)) {
            return RotationButtonAction.TOGGLE_FULLSCREEN;
        }
        return landscape
                ? RotationButtonAction.REQUEST_PORTRAIT
                : RotationButtonAction.REQUEST_SENSOR_LANDSCAPE;
    }

    /**
     * After a rotation to portrait the player may still be in fullscreen. A tablet's fullscreen
     * does not depend on the orientation, and a vertical video stays fullscreen in portrait.
     *
     * @param fullscreen    whether the main player is in fullscreen
     * @param verticalVideo whether the playing video is taller than wide
     * @param tablet        whether the device is a tablet
     * @return whether to leave fullscreen
     */
    static boolean leavesFullscreenInPortrait(final boolean fullscreen,
                                              final boolean verticalVideo,
                                              final boolean tablet) {
        return fullscreen && !verticalVideo && !tablet;
    }

    /**
     * Determines whether expanding the detail screen should enter fullscreen playback.
     *
     * @param landscape  whether the device is in landscape orientation
     * @param playing    whether a player exists and is playing
     * @param fullscreen whether the main player is in fullscreen
     * @param tablet     whether the device is a tablet
     * @return whether the player goes fullscreen when the detail screen has expanded
     */
    static boolean entersFullscreenWhenExpanded(final boolean landscape, final boolean playing,
                                                final boolean fullscreen, final boolean tablet) {
        return landscape && playing && !fullscreen && !tablet;
    }

    /**
     * Opening the main player "directly in fullscreen" works by rotating the activity to
     * landscape, which is only needed, and only done, in portrait with auto-rotate off.
     *
     * @param requested         whether fullscreen was asked for
     * @param landscape         whether the device is in landscape orientation
     * @param orientationLocked whether auto-rotate is off in the system settings
     * @return whether to rotate the activity to landscape before opening the player
     */
    static boolean rotatesToOpenFullscreen(final boolean requested, final boolean landscape,
                                           final boolean orientationLocked) {
        return requested && !landscape && orientationLocked;
    }

    /**
     * With auto-rotate off the user got to landscape on purpose, by the rotation button or by
     * opening the player in fullscreen, so a player that is not playing is started.
     *
     * @param orientationLocked whether auto-rotate is off in the system settings
     * @param playing           whether the player is playing
     * @return whether to start playback now that the device is in landscape
     */
    static boolean startsPlaybackInLandscape(final boolean orientationLocked,
                                             final boolean playing) {
        return orientationLocked && !playing;
    }
}
