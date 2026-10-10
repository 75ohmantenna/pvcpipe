package org.schabi.newpipe.pvc.bus.events;

import org.schabi.newpipe.pvc.PvcConstants;

/**
 * Home of all PVCPipe Events.
 * <p>
 * Most Events also have an handler interface that is implemented and than
 * called via the {@link org.greenrobot.eventbus.EventBus} Subscribe notation.
 */
public class PvcEvents {

    /**
     * Event for a new height to the the VideoDetailFragment's ViewPager's content height.
     * <p>
     * The height value is calculated in
     * {@link org.schabi.newpipe.fragments.detail.DetailPagerScrollController} and used in
     * the fragments that are placed in
     * {@link org.schabi.newpipe.fragments.detail.VideoDetailFragment}'s ViewPager.
     */
    public static class EventViewPagersContentHeight {
        public int height = PvcConstants.LAYOUT_LENGTH_UNSET;

        public interface Handler {
            void handleEventViewPagersContentHeight(EventViewPagersContentHeight event);
        }
    }

    ///region SharedPreferences Events for some PVCPipe settings.

    /**
     * Event for the PVCPipe option that shows comment replies in the same fragment.
     */
    public static class PrefEventSameWindowCommentReplies {
        public boolean doSameWindowCommentReplies = false;

        public PrefEventSameWindowCommentReplies(
                final boolean doSameWindow) {
            this.doSameWindowCommentReplies = doSameWindow;
        }
    }

    /**
     * Event for PVCPipe config option to scroll only below player in the ViewPager.
     */
    public static class PrefEventScrollOnlyBelowPlayer {
        public boolean doScrollInViewPager = false;

        public PrefEventScrollOnlyBelowPlayer(
                final boolean doScroll) {
            this.doScrollInViewPager = doScroll;
        }
        public interface Handler {
            void handlePrefEventScrollOnlyBelowPlayer(PrefEventScrollOnlyBelowPlayer event);
        }
    }
}
