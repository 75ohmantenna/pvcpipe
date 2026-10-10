package org.schabi.newpipe.info_list;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The list state that {@link InfoListAdapter} and
 * {@link org.schabi.newpipe.local.LocalItemListAdapter} share: the items, an optional header in
 * front of them and an optional footer behind them.
 *
 * @param <T> the type of the listed items
 */
public abstract class HeaderFooterListAdapter<T>
        extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    protected static final int HEADER_TYPE = 0;
    protected static final int FOOTER_TYPE = 1;

    private final ArrayList<T> items = new ArrayList<>();
    private Supplier<View> headerSupplier = null;
    private boolean footerRequested = false;

    public void addItems(@Nullable final List<? extends T> data) {
        if (data == null) {
            return;
        }

        final int offsetStart = sizeConsideringHeader();
        items.addAll(data);
        notifyItemRangeInserted(offsetStart, data.size());

        if (isFooterShown()) {
            // the new items pushed the footer behind them
            notifyItemMoved(offsetStart, sizeConsideringHeader());
        }
    }

    public void clearStreamItemList() {
        if (items.isEmpty()) {
            return;
        }
        items.clear();
        notifyDataSetChanged();
    }

    public void setHeaderSupplier(@Nullable final Supplier<View> headerSupplier) {
        final boolean changed = headerSupplier != this.headerSupplier;
        this.headerSupplier = headerSupplier;
        if (changed) {
            notifyDataSetChanged();
        }
    }

    public void showFooter(final boolean show) {
        if (show == footerRequested) {
            return;
        }

        footerRequested = show;
        if (show) {
            notifyItemInserted(sizeConsideringHeader());
        } else {
            notifyItemRemoved(sizeConsideringHeader());
        }
    }

    public ArrayList<T> getItemsList() {
        return items;
    }

    protected boolean hasHeader() {
        return headerSupplier != null;
    }

    /**
     * @return a header view from the current supplier; call only while {@link #hasHeader()}
     */
    protected View createHeaderView() {
        return headerSupplier.get();
    }

    /**
     * @return whether {@link #showFooter(boolean)} was last called with {@code true}
     */
    protected boolean isFooterRequested() {
        return footerRequested;
    }

    /**
     * @return whether a footer row currently follows the items
     */
    protected boolean isFooterShown() {
        return footerRequested;
    }

    protected int sizeConsideringHeader() {
        return items.size() + (hasHeader() ? 1 : 0);
    }

    /**
     * @param adapterPosition a position in this adapter
     * @return the index in {@link #getItemsList()} that the position refers to
     */
    protected int itemIndexOf(final int adapterPosition) {
        return adapterPosition - (hasHeader() ? 1 : 0);
    }

    /**
     * @param item an item of this adapter
     * @return the view type of the holder that shows the item
     */
    protected abstract int getViewTypeOf(@NonNull T item);

    @Override
    public int getItemCount() {
        return sizeConsideringHeader() + (isFooterShown() ? 1 : 0);
    }

    @Override
    public int getItemViewType(final int position) {
        if (hasHeader() && position == 0) {
            return HEADER_TYPE;
        }

        final int index = itemIndexOf(position);
        if (index == items.size() && isFooterShown()) {
            return FOOTER_TYPE;
        }
        return getViewTypeOf(items.get(index));
    }

    public GridLayoutManager.SpanSizeLookup getSpanSizeLookup(final int spanCount) {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(final int position) {
                final int type = getItemViewType(position);
                return type == HEADER_TYPE || type == FOOTER_TYPE ? spanCount : 1;
            }
        };
    }
}
