package org.schabi.newpipe.pvc.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;

import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListInfo;
import org.schabi.newpipe.fragments.detail.DetailPagerViewModel;
import org.schabi.newpipe.fragments.detail.PagerContentSizer;
import org.schabi.newpipe.fragments.list.BaseListInfoFragment;
import org.schabi.newpipe.util.DeviceUtils;

/**
 * A list that can be shown inside the detail screen's pager. It follows the pager's content height
 * and the "scroll only below player" setting.
 *
 * @param <I> the type of the listed items
 * @param <L> the type of the list info that provides them
 */
public abstract class PagerHostedListInfoFragment<I extends InfoItem, L extends ListInfo<I>>
        extends BaseListInfoFragment<I, L> {
    private final PagerContentSizer sizer = new PagerContentSizer();

    protected PagerHostedListInfoFragment(final UserAction errorUserAction) {
        super(errorUserAction);
    }

    @Override
    public void onViewCreated(@NonNull final View rootView, final Bundle savedInstanceState) {
        super.onViewCreated(rootView, savedInstanceState);

        final DetailPagerViewModel pager = DetailPagerViewModel.of(this);
        // Height before scroll mode: leaving "scroll only below player" has to undo a height
        // that was delivered just before it.
        pager.getPagerContentHeight().observe(getViewLifecycleOwner(),
                this::doResizeViewPagersContent);
        pager.getScrollOnlyBelowPlayer().observe(getViewLifecycleOwner(), scrollOnly ->
                sizer.onScrollMode(itemsList, DeviceUtils.isLandscape(requireContext()),
                        scrollOnly));
    }

    /**
     * Do the resize of the ViewPager's content View.
     *
     * @param height new height the view inside the ViewPager should have
     */
    protected void doResizeViewPagersContent(final int height) {
        sizer.onContentHeight(itemsList, height);
    }
}
