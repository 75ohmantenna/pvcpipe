package org.schabi.newpipe.views;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A {@link RecyclerView} that keeps scrolling by touch after its nested scrolling is switched off.
 * <p>
 * While nested scrolling is on, RecyclerView records how far each drag step moved the list inside
 * the window and takes that distance off the next touch position. With nested scrolling off
 * nothing updates the record any more, yet RecyclerView still applies it. If the last step before
 * the switch moved the list, as it does while the detail screen's collapsing header is sliding,
 * every later drag step loses that distance, and the list barely moves or does not move at all.
 * <p>
 * This class clears the record at the start of every step, so it cannot outlive the nested scroll
 * it describes. A nested scroll that does move the list writes it again right after.
 */
public class OffsetResettingRecyclerView extends RecyclerView {
    public OffsetResettingRecyclerView(@NonNull final Context context) {
        super(context);
    }

    public OffsetResettingRecyclerView(@NonNull final Context context,
                                       @Nullable final AttributeSet attrs) {
        super(context, attrs);
    }

    public OffsetResettingRecyclerView(@NonNull final Context context,
                                       @Nullable final AttributeSet attrs,
                                       final int defStyle) {
        super(context, attrs, defStyle);
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
