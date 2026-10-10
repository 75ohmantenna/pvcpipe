package org.schabi.newpipe.fragments.detail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.ViewGroup;

import org.junit.Test;

public class PagerScrollPolicyTest {
    @Test
    public void contentScrollsBelowThePlayerOnlyInPortraitWithTheSettingOn() {
        assertTrue(PagerScrollPolicy.scrollsBelowPlayer(false, true));
        assertFalse(PagerScrollPolicy.scrollsBelowPlayer(true, true));
        assertFalse(PagerScrollPolicy.scrollsBelowPlayer(false, false));
        assertFalse(PagerScrollPolicy.scrollsBelowPlayer(true, false));
    }

    @Test
    public void unsetIsNotAHeightAViewCanHave() {
        assertTrue(PagerScrollPolicy.UNSET != ViewGroup.LayoutParams.MATCH_PARENT);
        assertTrue(PagerScrollPolicy.UNSET != ViewGroup.LayoutParams.WRAP_CONTENT);
        assertTrue(PagerScrollPolicy.UNSET < 0);
    }

    @Test
    public void viewThatWasNeverResizedHasNothingToRestore() {
        final PagerScrollPolicy policy = new PagerScrollPolicy();

        assertEquals(PagerScrollPolicy.UNSET, policy.takeOriginalHeight());
    }

    @Test
    public void firstResizeRemembersTheHeightTheViewStartedWith() {
        final PagerScrollPolicy policy = new PagerScrollPolicy();

        assertEquals(900, policy.resizeTo(ViewGroup.LayoutParams.MATCH_PARENT, 900));
        // Later resizes see the pager's own earlier height, which must not replace the original.
        assertEquals(700, policy.resizeTo(900, 700));
        assertEquals(650, policy.resizeTo(700, 650));

        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, policy.takeOriginalHeight());
    }

    @Test
    public void restoringForgetsTheOriginalSoTheNextResizeRecordsAgain() {
        final PagerScrollPolicy policy = new PagerScrollPolicy();
        policy.resizeTo(ViewGroup.LayoutParams.WRAP_CONTENT, 800);
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, policy.takeOriginalHeight());

        assertEquals(PagerScrollPolicy.UNSET, policy.takeOriginalHeight());

        policy.resizeTo(1200, 600);
        assertEquals(1200, policy.takeOriginalHeight());
    }
}
