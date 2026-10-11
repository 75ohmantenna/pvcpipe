package org.schabi.newpipe.views;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/**
 * A {@link NestedScrollView} that keeps scrolling by touch after its nested scrolling is switched
 * off. NestedScrollView reuses the window offset of its last nested scroll in the same way
 * RecyclerView does; see {@link OffsetResettingRecyclerView} for what goes wrong and why clearing
 * the offset at the start of every drag step fixes it.
 */
public class OffsetResettingNestedScrollView extends NestedScrollView {
    public OffsetResettingNestedScrollView(@NonNull final Context context) {
        super(context);
    }

    public OffsetResettingNestedScrollView(@NonNull final Context context,
                                           @Nullable final AttributeSet attrs) {
        super(context, attrs);
    }

    public OffsetResettingNestedScrollView(@NonNull final Context context,
                                           @Nullable final AttributeSet attrs,
                                           final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean dispatchNestedPreScroll(final int dx, final int dy,
                                           @Nullable final int[] consumed,
                                           @Nullable final int[] offsetInWindow,
                                           final int type) {
        if (offsetInWindow != null) {
            offsetInWindow[0] = 0;
            offsetInWindow[1] = 0;
        }
        return super.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type);
    }
}
