package org.schabi.newpipe.player.playqueue;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.image.CoilHelper;

public class PlayQueueItemBuilder {
    private static final String TAG = PlayQueueItemBuilder.class.toString();
    private OnSelectedListener onItemClickListener;
    private final Context context;

    public PlayQueueItemBuilder(final Context context) {
        this.context = context;
    }

    public void setOnSelectedListener(final OnSelectedListener listener) {
        this.onItemClickListener = listener;
    }

    @SuppressLint("ClickableViewAccessibility")
    public void buildStreamInfoItem(final PlayQueueItemHolder holder, final PlayQueueItem item) {
        holder.itemVideoTitleView.setText(item.getTitle());
        holder.itemAdditionalDetailsView.setText(Localization.concatenateStrings(item.getUploader(),
                ServiceHelper.getNameOfServiceById(item.getServiceId())));

        if (item.getDuration() > 0) {
            holder.itemDurationView.setText(Localization.getDurationString(item.getDuration()));
            holder.itemDurationView.setVisibility(View.VISIBLE);
        } else {
            holder.itemDurationView.setVisibility(View.GONE);
        }

        CoilHelper.INSTANCE.loadThumbnail(holder.itemThumbnailView, item.getThumbnails());

        holder.itemRoot.setOnClickListener(view -> {
            if (onItemClickListener != null) {
                onItemClickListener.selected(item, view);
            }
        });

        holder.itemRoot.setOnLongClickListener(view -> {
            if (onItemClickListener != null) {
                onItemClickListener.held(item, view);
                return true;
            }
            return false;
        });

        holder.itemHandle.setOnTouchListener(getOnTouchListener(holder));
        holder.itemHandle.setContentDescription(
                context.getString(R.string.reorder_item, item.getTitle()));
        holder.itemHandle.setFocusable(true);
        holder.itemHandle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        ViewCompat.replaceAccessibilityAction(holder.itemHandle,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD,
                context.getString(R.string.move_up),
                (view, arguments) -> move(holder, -1));
        ViewCompat.replaceAccessibilityAction(holder.itemHandle,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD,
                context.getString(R.string.move_down),
                (view, arguments) -> move(holder, 1));

        updateMoveActionAvailability(holder);
    }

    @SuppressLint("ClickableViewAccessibility")
    private View.OnTouchListener getOnTouchListener(final PlayQueueItemHolder holder) {
        return (view, motionEvent) -> {
            if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN
                    && onItemClickListener != null) {
                onItemClickListener.onStartDrag(holder);
            }
            return false;
        };
    }

    private boolean move(final PlayQueueItemHolder holder, final int offset) {
        return onItemClickListener != null && onItemClickListener.onMove(
                holder, holder.getBindingAdapterPosition() + offset);
    }

    private void updateMoveActionAvailability(final PlayQueueItemHolder holder) {
        final int position = holder.getBindingAdapterPosition();
        final int itemCount = holder.getBindingAdapter() == null
                ? 0 : holder.getBindingAdapter().getItemCount();
        if (position <= 0) {
            ViewCompat.removeAccessibilityAction(holder.itemHandle,
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD);
        }
        if (position < 0 || position >= itemCount - 1) {
            ViewCompat.removeAccessibilityAction(holder.itemHandle,
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD);
        }
    }

    public interface OnSelectedListener {
        void selected(PlayQueueItem item, View view);

        void held(PlayQueueItem item, View view);

        void onStartDrag(PlayQueueItemHolder viewHolder);

        boolean onMove(PlayQueueItemHolder viewHolder, int targetIndex);
    }
}
