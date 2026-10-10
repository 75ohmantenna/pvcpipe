package org.schabi.newpipe.pvc.bus.events;

import org.schabi.newpipe.pvc.PvcConstants;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;

/**
 * Home of all PVCPipe Events.
 * <p>
 * Most Events also have an handler interface that is implemented and than
 * called via the {@link org.greenrobot.eventbus.EventBus} Subscribe notation.
 */
public class PvcEvents {

    /**
     * If the user wants to see replies to a comment this event is ignited.
     */
    public static class EventShowCommentRepliesFragment {
        public final CommentsInfoItem item;

        public EventShowCommentRepliesFragment(
                final CommentsInfoItem item) {
            this.item = item;
        }

        public interface Handler {
            void handleEventShowCommentRepliesFragment(EventShowCommentRepliesFragment event);
        }
    }

    /**
     * Event ignited if back button of is pushed.
     * Back button of {@link org.schabi.newpipe.fragments.list.comments.CommentRepliesFragment}
     */
    public static class EventBackPressedInCommentRepliesFragment {

        public interface Handler {
            void handleEventBackPressedInCommentRepliesFragment(
                    EventBackPressedInCommentRepliesFragment event);
        }
    }

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
