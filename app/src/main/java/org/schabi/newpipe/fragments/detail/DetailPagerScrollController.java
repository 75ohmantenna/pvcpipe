package org.schabi.newpipe.fragments.detail;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import org.schabi.newpipe.pvc.fragments.PvcHostFragment;
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding;
import org.schabi.newpipe.pvc.PvcPreferences;
import org.schabi.newpipe.pvc.views.PvcInterceptTouchRelativeLayout;
import org.schabi.newpipe.player.ui.VideoPlayerUi;
import org.schabi.newpipe.util.DeviceUtils;

import static org.schabi.newpipe.ktx.ViewUtils.animateRotation;

/**
 * Keeps the pager below the player sized and scrollable for {@link VideoDetailFragment}, and
 * lets the system Back key leave comment replies shown in its comments tab.
 * <p>
 * It follows the "scroll only below player" setting through {@link DetailPagerViewModel} and
 * publishes the height the pager content should have there.
 */
final class DetailPagerScrollController {
    private static final String TAG = "DetailPagerScroll";

    private final VideoDetailFragment fragment;
    private FragmentVideoDetailBinding binding;
    private DetailPagerViewModel pager;

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
                        pager.publishPagerContentHeight(
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

    private void onScrollOnlyBelowPlayerChanged(final boolean scrollOnlyBelowPlayer) {
        if (PagerScrollPolicy.scrollsBelowPlayer(
                DeviceUtils.isLandscape(fragment.requireContext()), scrollOnlyBelowPlayer)) {
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
                    pager.publishPagerContentHeight(actualHeight);
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
        final Context context = fragment.getContext();
        if (context != null && PvcPreferences.commentRepliesInSameWindow(context)) {
            return PvcHostFragment.handleBackPressed(fragment.getChildFragmentManager());
        } else {
            return false;
        }
    }


    /**
     * Starts following the "scroll only below player" setting for as long as the fragment's view
     * lives. The current setting arrives when that view starts.
     *
     * @param viewBinding the views of the fragment this controller belongs to
     */
    void attach(final FragmentVideoDetailBinding viewBinding) {
        binding = viewBinding;
        pager = DetailPagerViewModel.of(fragment);
        pager.getScrollOnlyBelowPlayer().observe(fragment.getViewLifecycleOwner(),
                this::onScrollOnlyBelowPlayerChanged);
    }

    void detach() {
        binding = null;
    }
}
