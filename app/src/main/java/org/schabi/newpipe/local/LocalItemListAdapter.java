package org.schabi.newpipe.local;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.info_list.HeaderFooterListAdapter;
import org.schabi.newpipe.info_list.ItemViewMode;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.local.holder.LocalBookmarkPlaylistItemHolder;
import org.schabi.newpipe.local.holder.LocalItemHolder;
import org.schabi.newpipe.local.holder.LocalPlaylistItemHolder;
import org.schabi.newpipe.local.holder.LocalPlaylistStreamItemHolder;
import org.schabi.newpipe.local.holder.LocalStatisticStreamItemHolder;
import org.schabi.newpipe.local.holder.RemoteBookmarkPlaylistItemHolder;
import org.schabi.newpipe.local.holder.RemotePlaylistItemHolder;
import org.schabi.newpipe.util.FallbackViewHolder;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.OnClickGesture;

import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;

/*
 * Created by Christian Schabesberger on 01.08.16.
 *
 * Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
 * InfoListAdapter.java is part of NewPipe.
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

public class LocalItemListAdapter extends HeaderFooterListAdapter<LocalItem> {
    private static final String TAG = LocalItemListAdapter.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final int STREAM_STATISTICS_HOLDER_TYPE = 0x1000;
    private static final int STREAM_PLAYLIST_HOLDER_TYPE = 0x1001;
    private static final int STREAM_STATISTICS_GRID_HOLDER_TYPE = 0x1002;
    private static final int STREAM_STATISTICS_CARD_HOLDER_TYPE = 0x1003;
    private static final int STREAM_PLAYLIST_GRID_HOLDER_TYPE = 0x1004;
    private static final int STREAM_PLAYLIST_CARD_HOLDER_TYPE = 0x1005;

    private static final int LOCAL_PLAYLIST_HOLDER_TYPE = 0x2000;
    private static final int LOCAL_PLAYLIST_GRID_HOLDER_TYPE = 0x2001;
    private static final int LOCAL_PLAYLIST_CARD_HOLDER_TYPE = 0x2002;
    private static final int LOCAL_BOOKMARK_PLAYLIST_HOLDER_TYPE = 0x2003;

    private static final int REMOTE_PLAYLIST_HOLDER_TYPE = 0x3000;
    private static final int REMOTE_PLAYLIST_GRID_HOLDER_TYPE = 0x3001;
    private static final int REMOTE_PLAYLIST_CARD_HOLDER_TYPE = 0x3002;
    private static final int REMOTE_BOOKMARK_PLAYLIST_HOLDER_TYPE = 0x3003;

    private final LocalItemBuilder localItemBuilder;
    private final HistoryRecordManager recordManager;
    private final DateTimeFormatter dateTimeFormatter;

    private View footer = null;
    private ItemViewMode itemViewMode = ItemViewMode.LIST;
    private boolean useItemHandle = false;

    public LocalItemListAdapter(final Context context) {
        recordManager = new HistoryRecordManager(context);
        localItemBuilder = new LocalItemBuilder(context);

        dateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
                .withLocale(Localization.getPreferredLocale(context));
    }

    public void setSelectedListener(final OnClickGesture<LocalItem> listener) {
        localItemBuilder.setOnItemSelectedListener(listener);
    }

    public void unsetSelectedListener() {
        localItemBuilder.setOnItemSelectedListener(null);
    }

    public void removeItem(final LocalItem data) {
        final int index = getItemsList().indexOf(data);
        if (index != -1) {
            getItemsList().remove(index);
            notifyItemRemoved(index + (hasHeader() ? 1 : 0));
        } else {
            // this happens when
            // 1) removeItem is called on infoItemDuplicate as in showStreamItemDialog of
            // LocalPlaylistFragment in this case need to implement delete object by it's duplicate

            // OR

            // 2)data not in itemList and UI is still not updated so notifyDataSetChanged()
            notifyDataSetChanged();
        }
    }

    public boolean swapItems(final int fromAdapterPosition, final int toAdapterPosition) {
        final ArrayList<LocalItem> items = getItemsList();
        final int actualFrom = itemIndexOf(fromAdapterPosition);
        final int actualTo = itemIndexOf(toAdapterPosition);

        if (actualFrom < 0 || actualTo < 0) {
            return false;
        }
        if (actualFrom >= items.size() || actualTo >= items.size()) {
            return false;
        }

        items.add(actualTo, items.remove(actualFrom));
        notifyItemMoved(fromAdapterPosition, toAdapterPosition);
        return true;
    }

    public void setItemViewMode(final ItemViewMode itemViewMode) {
        this.itemViewMode = itemViewMode;
    }

    public void setUseItemHandle(final boolean useItemHandle) {
        this.useItemHandle = useItemHandle;
    }

    public void setFooter(final View view) {
        this.footer = view;
    }

    @Deprecated(since = "Calling this method with `true` may cause crashes, see "
            + "https://github.com/TeamNewPipe/NewPipe/pull/12996#pullrequestreview-3713317115")
    @Override
    public void showFooter(final boolean show) {
        if (show && !isFooterRequested()) {
            Log.w(TAG, "Calling LocalItemListAdapter.showFooter(true) may cause crashes, see https"
                    + "://github.com/TeamNewPipe/NewPipe/pull/12996#pullrequestreview-3713317115");
        }
        super.showFooter(show);
    }

    /**
     * Unlike in {@link org.schabi.newpipe.info_list.InfoListAdapter}, the footer row only exists
     * once {@link #setFooter(View)} supplied its view.
     */
    @Override
    protected boolean isFooterShown() {
        return footer != null && isFooterRequested();
    }

    @Override
    protected int getViewTypeOf(@NonNull final LocalItem item) {
        switch (item.getLocalItemType()) {
            case PLAYLIST_LOCAL_ITEM:
                if (useItemHandle) {
                    return LOCAL_BOOKMARK_PLAYLIST_HOLDER_TYPE;
                } else if (itemViewMode == ItemViewMode.CARD) {
                    return LOCAL_PLAYLIST_CARD_HOLDER_TYPE;
                } else if (itemViewMode == ItemViewMode.GRID) {
                    return LOCAL_PLAYLIST_GRID_HOLDER_TYPE;
                } else {
                    return LOCAL_PLAYLIST_HOLDER_TYPE;
                }
            case PLAYLIST_REMOTE_ITEM:
                if (useItemHandle) {
                    return REMOTE_BOOKMARK_PLAYLIST_HOLDER_TYPE;
                } else if (itemViewMode == ItemViewMode.CARD) {
                    return REMOTE_PLAYLIST_CARD_HOLDER_TYPE;
                } else if (itemViewMode == ItemViewMode.GRID) {
                    return REMOTE_PLAYLIST_GRID_HOLDER_TYPE;
                } else {
                    return REMOTE_PLAYLIST_HOLDER_TYPE;
                }
            case PLAYLIST_STREAM_ITEM:
                if (itemViewMode == ItemViewMode.CARD) {
                    return STREAM_PLAYLIST_CARD_HOLDER_TYPE;
                } else if (itemViewMode == ItemViewMode.GRID) {
                    return STREAM_PLAYLIST_GRID_HOLDER_TYPE;
                } else {
                    return STREAM_PLAYLIST_HOLDER_TYPE;
                }
            case STATISTIC_STREAM_ITEM:
                if (itemViewMode == ItemViewMode.CARD) {
                    return STREAM_STATISTICS_CARD_HOLDER_TYPE;
                } else if (itemViewMode == ItemViewMode.GRID) {
                    return STREAM_STATISTICS_GRID_HOLDER_TYPE;
                } else {
                    return STREAM_STATISTICS_HOLDER_TYPE;
                }
            default:
                Log.e(TAG, "No holder type has been considered for item: ["
                        + item.getLocalItemType() + "]");
                return -1;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent,
                                                      final int type) {
        if (DEBUG) {
            Log.d(TAG, "onCreateViewHolder() called with: "
                    + "parent = [" + parent + "], type = [" + type + "]");
        }
        switch (type) {
            case HEADER_TYPE:
                return new HeaderFooterHolder(createHeaderView());
            case FOOTER_TYPE:
                return new HeaderFooterHolder(footer);
            case LOCAL_PLAYLIST_HOLDER_TYPE:
                return new LocalPlaylistItemHolder(localItemBuilder, parent);
            case LOCAL_PLAYLIST_GRID_HOLDER_TYPE:
                return new LocalPlaylistItemHolder(localItemBuilder,
                        R.layout.list_playlist_grid_item, parent);
            case LOCAL_PLAYLIST_CARD_HOLDER_TYPE:
                return new LocalPlaylistItemHolder(localItemBuilder,
                        R.layout.list_playlist_card_item, parent);
            case LOCAL_BOOKMARK_PLAYLIST_HOLDER_TYPE:
                return new LocalBookmarkPlaylistItemHolder(localItemBuilder, parent);
            case REMOTE_PLAYLIST_HOLDER_TYPE:
                return new RemotePlaylistItemHolder(localItemBuilder, parent);
            case REMOTE_PLAYLIST_GRID_HOLDER_TYPE:
                return new RemotePlaylistItemHolder(localItemBuilder,
                        R.layout.list_playlist_grid_item, parent);
            case REMOTE_PLAYLIST_CARD_HOLDER_TYPE:
                return new RemotePlaylistItemHolder(localItemBuilder,
                        R.layout.list_playlist_card_item, parent);
            case REMOTE_BOOKMARK_PLAYLIST_HOLDER_TYPE:
                return new RemoteBookmarkPlaylistItemHolder(localItemBuilder, parent);
            case STREAM_PLAYLIST_HOLDER_TYPE:
                return new LocalPlaylistStreamItemHolder(localItemBuilder, parent);
            case STREAM_PLAYLIST_GRID_HOLDER_TYPE:
                return new LocalPlaylistStreamItemHolder(localItemBuilder,
                        R.layout.list_stream_playlist_grid_item, parent);
            case STREAM_PLAYLIST_CARD_HOLDER_TYPE:
                return new LocalPlaylistStreamItemHolder(localItemBuilder,
                        R.layout.list_stream_playlist_card_item, parent);
            case STREAM_STATISTICS_HOLDER_TYPE:
                return new LocalStatisticStreamItemHolder(localItemBuilder, parent);
            case STREAM_STATISTICS_GRID_HOLDER_TYPE:
                return new LocalStatisticStreamItemHolder(localItemBuilder,
                        R.layout.list_stream_grid_item, parent);
            case STREAM_STATISTICS_CARD_HOLDER_TYPE:
                return new LocalStatisticStreamItemHolder(localItemBuilder,
                        R.layout.list_stream_card_item, parent);
            default:
                Log.e(TAG, "No view type has been considered for holder: [" + type + "]");
                return new FallbackViewHolder(new View(parent.getContext()));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder,
                                 final int position) {
        if (DEBUG) {
            Log.d(TAG, "onBindViewHolder() called with: "
                    + "holder = [" + holder.getClass().getSimpleName() + "], "
                    + "position = [" + position + "]");
        }

        if (holder instanceof LocalItemHolder) {
            ((LocalItemHolder) holder).updateFromItem(getItemsList().get(itemIndexOf(position)),
                    recordManager, dateTimeFormatter);
        } else if (holder instanceof HeaderFooterHolder && position == 0 && hasHeader()) {
            ((HeaderFooterHolder) holder).view = createHeaderView();
        } else if (holder instanceof HeaderFooterHolder && position == sizeConsideringHeader()
                && isFooterShown()) {
            ((HeaderFooterHolder) holder).view = footer;
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, final int position,
                                 @NonNull final List<Object> payloads) {
        if (!payloads.isEmpty() && holder instanceof LocalItemHolder) {
            for (final Object payload : payloads) {
                if (payload instanceof StreamStateEntity) {
                    ((LocalItemHolder) holder).updateState(
                            getItemsList().get(itemIndexOf(position)), recordManager);
                } else if (payload instanceof Boolean) {
                    ((LocalItemHolder) holder).updateState(
                            getItemsList().get(itemIndexOf(position)), recordManager);
                }
            }
        } else {
            onBindViewHolder(holder, position);
        }
    }

}
