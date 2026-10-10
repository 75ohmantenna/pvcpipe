package org.schabi.newpipe.fragments.detail;

import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.schabi.newpipe.pvc.bus.events.PvcEvents;
import org.schabi.newpipe.pvc.fragments.PvcHostFragment;
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding;
import org.schabi.newpipe.pvc.bus.PvcBus;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.fragments.list.comments.CommentRepliesFragment;
import org.schabi.newpipe.pvc.views.PvcInterceptTouchRelativeLayout;
import org.schabi.newpipe.player.ui.VideoPlayerUi;
import org.schabi.newpipe.util.DeviceUtils;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import static org.schabi.newpipe.ktx.ViewUtils.animateRotation;

/**
 * Keeps the pager below the player sized and scrollable for {@link VideoDetailFragment}, and
 * swaps comment replies in and out of its comments tab.
 * <p>
 * It receives these events:
 * <ul>
 * <li>{@link PvcEvents.PrefEventScrollOnlyBelowPlayer}</li>
 * <li>{@link PvcEvents.EventBackPressedInCommentRepliesFragment}</li>
 * <li>{@link PvcEvents.EventShowCommentRepliesFragment}</li>
 * </ul>
 * <p>
 * It sends this event:
 * <ul>
 * <li>{@link PvcEvents.EventViewPagersContentHeight}</li>
 * </ul>
 * <p>
 * The class is public because EventBus calls its subscriber methods reflectively.
 */
public final class DetailPagerScrollController
        implements PvcEvents.EventShowCommentRepliesFragment.Handler,
        PvcEvents.EventBackPressedInCommentRepliesFragment.Handler,
        PvcEvents.PrefEventScrollOnlyBelowPlayer.Handler {
    private static final String TAG = "DetailPagerScroll";

    private final VideoDetailFragment fragment;
    private FragmentVideoDetailBinding binding;

    DetailPagerScrollController(final VideoDetailFragment fragment) {
        this.fragment = fragment;
    }


    private int lastHeight = -1;
    private View.OnLayoutChangeListener onChangeLayoutListener = null;

    private void hideTitleAndSecondaryControls() {
        if (binding.detailContentRootHiding.getVisibility() == View.GONE) {
            binding.detailVideoTitleView.setMaxLines(10);
            animateRotation(binding.detailToggleSecondaryControlsView,
                    VideoPlayerUi.DEFAULT_CONTROLS_DURATION, 180);
            binding.detailContentRootHiding.setVisibility(View.VISIBLE);
        } else {
            binding.detailVideoTitleView.setMaxLines(1);
            animateRotation(binding.detailToggleSecondaryControlsView,
                    VideoPlayerUi.DEFAULT_CONTROLS_DURATION, 0);
            binding.detailContentRootHiding.setVisibility(View.GONE);
        }
        // view pager height has changed, update the tab layout
        fragment.updateTabLayoutVisibility();
        addGlobalLayoutListener();
    }

    private void addGlobalLayoutListener() {
        final View rootLayout = binding.detailContentRootLayout;
        rootLayout.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        rootLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        PvcBus.Helpers.postEventViewPagersContentHeightChanged(
                                recalcPossibleViewPagersContentHeight());
                    }
                });
    }

    private int recalcPossibleViewPagersContentHeight() {

        int height = 800; // assume as default height
        try {
            height = binding.detailMainContent.getHeight()
                    - binding.playerPlaceholder.getHeight()
                    - binding.detailContentRootLayout.getHeight()
                    - binding.tabLayout.getHeight();
        } catch (final Exception e) {
            Log.e(TAG, e.getMessage());
        }

        return height;
    }

    @Override
    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void handlePrefEventScrollOnlyBelowPlayer(
            final PvcEvents.PrefEventScrollOnlyBelowPlayer event) {

        if (!DeviceUtils.isLandscape(fragment.requireContext()) && event.doScrollInViewPager) {
            binding.detailContentRootLayout.setOnInterceptTouchEventListener(
                    new PvcInterceptTouchRelativeLayout
                            .NoOuterScrollingWhileInlineCommentsEnabledListener());
            binding.detailToggleSecondaryControlsView.setOnClickListener(v ->
                    hideTitleAndSecondaryControls());
            binding.viewPager.addOnLayoutChangeListener(createLayoutListener());
        } else {
            binding.detailContentRootLayout.setOnInterceptTouchEventListener(null);
            binding.detailToggleSecondaryControlsView.setOnClickListener(null);
            // set to false as only the parent View should handle the click events now
            binding.detailToggleSecondaryControlsView.setClickable(false);

            if (null != onChangeLayoutListener) {
                binding.viewPager.removeOnLayoutChangeListener(onChangeLayoutListener);
            }
        }
    }

    /**
     * The {@link android.view.ViewTreeObserver.OnGlobalLayoutListener} is not enough.
     * <p>
     * We need to listen on layout changes of the ViewPager. It only sends a new event
     * in case the height actually changed.
     * @return the listener
     */
    private View.OnLayoutChangeListener createLayoutListener() {
        if (onChangeLayoutListener != null) {
            return onChangeLayoutListener;
        }

        onChangeLayoutListener = new View.OnLayoutChangeListener() {
            @Override
            @SuppressWarnings("checkstyle:ParameterNumber") // fixed OnLayoutChangeListener contract
            public void onLayoutChange(
                    final View v,
                    final int left,
                    final int top,
                    final int right,
                    final int bottom,
                    final int oldLeft,
                    final int oldTop,
                    final int oldRight,
                    final int oldBottom) {
                final int actualHeight = recalcPossibleViewPagersContentHeight(
                );
                if (lastHeight != actualHeight) {
                    lastHeight = actualHeight;
                    PvcBus.Helpers.postEventViewPagersContentHeightChanged(actualHeight);
                }
            }
        };

        return onChangeLayoutListener;
    }

    /**
     * Handle android back button pressed.
     * @return true if it was handled here
     */
    boolean onBackPressed() {
        if (PvcBus.Helpers.isPrefCommentRepliesSameWindowEnabled()) {
            return PvcHostFragment.handleBackPressed(fragment.getChildFragmentManager());
        } else {
            return false;
        }
    }

    @Override
    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void handleEventBackPressedInCommentRepliesFragment(
            final PvcEvents.EventBackPressedInCommentRepliesFragment event) {
        PvcHostFragment.handleBackPressed(fragment.getChildFragmentManager());
    }

    @Override
    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void handleEventShowCommentRepliesFragment(
            final PvcEvents.EventShowCommentRepliesFragment event) {
        replaceCommentsFragmentInViewPager(
                event.item,
                binding.viewPager.getAdapter(),
                binding.viewPager);
    }

    private void replaceCommentsFragmentInViewPager(
            @NonNull final CommentsInfoItem comment,
            final PagerAdapter pagerAdapter,
            final ViewPager viewPager) {
        final TabAdapter customPagerAdapter = (TabAdapter) pagerAdapter;
        final PvcHostFragment hostFragment =
                (PvcHostFragment) customPagerAdapter.getItem(viewPager.getCurrentItem());
        hostFragment.replaceFragment(new CommentRepliesFragment(comment), true);
    }


    /**
     * Starts listening for events. The current preference state is delivered before this returns.
     *
     * @param viewBinding the views of the fragment this controller belongs to
     */
    void attach(final FragmentVideoDetailBinding viewBinding) {
        binding = viewBinding;
        PvcBus.getBus().register(this);
    }

    void detach() {
        PvcBus.getBus().unregister(this);
        binding = null;
    }
}
