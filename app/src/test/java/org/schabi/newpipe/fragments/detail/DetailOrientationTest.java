package org.schabi.newpipe.fragments.detail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.schabi.newpipe.fragments.detail.DetailOrientation.RotationButtonAction.REQUEST_PORTRAIT;
import static org.schabi.newpipe.fragments.detail.DetailOrientation.RotationButtonAction.REQUEST_SENSOR_LANDSCAPE;
import static org.schabi.newpipe.fragments.detail.DetailOrientation.RotationButtonAction.TOGGLE_FULLSCREEN;

import org.junit.Test;

public class DetailOrientationTest {
    private static final boolean[] BOTH = {false, true};

    @Test
    public void rotationButtonRotatesAPhone() {
        for (final boolean locked : BOTH) {
            assertEquals(REQUEST_SENSOR_LANDSCAPE,
                    DetailOrientation.onRotationButton(false, false, locked, false));
            assertEquals(REQUEST_PORTRAIT,
                    DetailOrientation.onRotationButton(false, false, locked, true));
        }
    }

    @Test
    public void rotationButtonOnlyTogglesFullscreenOnATv() {
        for (final boolean tablet : BOTH) {
            for (final boolean locked : BOTH) {
                for (final boolean landscape : BOTH) {
                    assertEquals(TOGGLE_FULLSCREEN,
                            DetailOrientation.onRotationButton(true, tablet, locked, landscape));
                }
            }
        }
    }

    @Test
    public void rotationButtonOnATabletRotatesOnlyFromLockedPortrait() {
        assertEquals(TOGGLE_FULLSCREEN,
                DetailOrientation.onRotationButton(false, true, false, false));
        assertEquals(TOGGLE_FULLSCREEN,
                DetailOrientation.onRotationButton(false, true, false, true));
        assertEquals(TOGGLE_FULLSCREEN,
                DetailOrientation.onRotationButton(false, true, true, true));
        assertEquals(REQUEST_SENSOR_LANDSCAPE,
                DetailOrientation.onRotationButton(false, true, true, false));
    }

    @Test
    public void portraitLeavesFullscreenUnlessVerticalVideoOrTablet() {
        assertTrue(DetailOrientation.leavesFullscreenInPortrait(true, false, false));
        assertFalse(DetailOrientation.leavesFullscreenInPortrait(true, true, false));
        assertFalse(DetailOrientation.leavesFullscreenInPortrait(true, false, true));
        for (final boolean vertical : BOTH) {
            for (final boolean tablet : BOTH) {
                assertFalse(DetailOrientation.leavesFullscreenInPortrait(false, vertical, tablet));
            }
        }
    }

    @Test
    public void expandingEntersFullscreenOnlyForAPlayingPhonePlayerInLandscape() {
        for (final boolean landscape : BOTH) {
            for (final boolean playing : BOTH) {
                for (final boolean fullscreen : BOTH) {
                    for (final boolean tablet : BOTH) {
                        assertEquals(landscape && playing && !fullscreen && !tablet,
                                DetailOrientation.entersFullscreenWhenExpanded(
                                        landscape, playing, fullscreen, tablet));
                    }
                }
            }
        }
        assertTrue(DetailOrientation.entersFullscreenWhenExpanded(true, true, false, false));
    }

    @Test
    public void openingInFullscreenRotatesOnlyFromLockedPortrait() {
        assertTrue(DetailOrientation.rotatesToOpenFullscreen(true, false, true));
        assertFalse(DetailOrientation.rotatesToOpenFullscreen(true, false, false));
        assertFalse(DetailOrientation.rotatesToOpenFullscreen(true, true, true));
        for (final boolean landscape : BOTH) {
            for (final boolean locked : BOTH) {
                assertFalse(DetailOrientation.rotatesToOpenFullscreen(false, landscape, locked));
            }
        }
    }

    @Test
    public void landscapeStartsAStoppedPlayerOnlyWhenTheOrientationIsLocked() {
        assertTrue(DetailOrientation.startsPlaybackInLandscape(true, false));
        assertFalse(DetailOrientation.startsPlaybackInLandscape(true, true));
        assertFalse(DetailOrientation.startsPlaybackInLandscape(false, false));
        assertFalse(DetailOrientation.startsPlaybackInLandscape(false, true));
    }
}
