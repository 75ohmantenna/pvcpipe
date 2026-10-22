package org.schabi.newpipe.fragments.detail;

import android.view.View;
import android.view.ViewGroup;

/**
 * Applies the detail pager's height and scroll mode to one scrolling view shown inside it.
 */
public final class PagerContentSizer {
    private final PagerScrollPolicy policy = new PagerScrollPolicy();

    /**
     * Applies the pager content height while remembering the view's original height.
     *
     * @param view   the scrolling view
     * @param height the height the pager content should have
     */
    public void onContentHeight(final View view, final int height) {
        setHeight(view, policy.resizeTo(view.getLayoutParams().height, height));
    }

    /**
     * Updates nested scrolling and restores the original height when needed.
     *
     * @param view                  the scrolling view
     * @param landscape             whether the device is in landscape orientation
     * @param scrollOnlyBelowPlayer the user's "scroll only below player" setting
     */
    public void onScrollMode(final View view, final boolean landscape,
                             final boolean scrollOnlyBelowPlayer) {
        if (PagerScrollPolicy.scrollsBelowPlayer(landscape, scrollOnlyBelowPlayer)) {
            view.setNestedScrollingEnabled(false);
        } else {
            view.setNestedScrollingEnabled(true);
            // reset to former layout height
            final int originalHeight = policy.takeOriginalHeight();
            if (originalHeight != PagerScrollPolicy.UNSET) {
                setHeight(view, originalHeight);
            }
        }
    }

    private static void setHeight(final View view, final int height) {
        final ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.height != height) {
            params.height = height;
            view.setLayoutParams(params);
            view.requestLayout();
        }
    }
}
