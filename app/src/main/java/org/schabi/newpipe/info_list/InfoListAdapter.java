package org.schabi.newpipe.info_list;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PignateFooterBinding;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.info_list.holder.ChannelCardInfoItemHolder;
import org.schabi.newpipe.info_list.holder.ChannelMiniInfoItemHolder;
import org.schabi.newpipe.info_list.holder.CommentInfoItemHolder;
import org.schabi.newpipe.info_list.holder.InfoItemHolder;
import org.schabi.newpipe.info_list.holder.PlaylistMiniInfoItemHolder;
import org.schabi.newpipe.info_list.holder.StreamInfoItemHolder;
import org.schabi.newpipe.info_list.holder.StreamMiniInfoItemHolder;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.FallbackViewHolder;
import org.schabi.newpipe.util.OnClickGesture;

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

public class InfoListAdapter extends HeaderFooterListAdapter<InfoItem> {
    private static final String TAG = InfoListAdapter.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final int MINI_STREAM_HOLDER_TYPE = 0x100;
    private static final int STREAM_HOLDER_TYPE = 0x101;
    private static final int GRID_STREAM_HOLDER_TYPE = 0x102;
    private static final int CARD_STREAM_HOLDER_TYPE = 0x103;
    private static final int MINI_CHANNEL_HOLDER_TYPE = 0x200;
    private static final int CHANNEL_HOLDER_TYPE = 0x201;
    private static final int GRID_CHANNEL_HOLDER_TYPE = 0x202;
    private static final int CARD_CHANNEL_HOLDER_TYPE = 0x203;
    private static final int MINI_PLAYLIST_HOLDER_TYPE = 0x300;
    private static final int PLAYLIST_HOLDER_TYPE = 0x301;
    private static final int GRID_PLAYLIST_HOLDER_TYPE = 0x302;
    private static final int CARD_PLAYLIST_HOLDER_TYPE = 0x303;
    private static final int COMMENT_HOLDER_TYPE = 0x400;

    private final LayoutInflater layoutInflater;
    private final InfoItemBuilder infoItemBuilder;
    private final HistoryRecordManager recordManager;

    private boolean useMiniVariant = false;

    private ItemViewMode itemMode = ItemViewMode.LIST;

    public InfoListAdapter(final Context context) {
        layoutInflater = LayoutInflater.from(context);
        recordManager = new HistoryRecordManager(context);
        infoItemBuilder = new InfoItemBuilder(context);
    }

    public void setOnStreamSelectedListener(final OnClickGesture<StreamInfoItem> listener) {
        infoItemBuilder.setOnStreamSelectedListener(listener);
    }

    public void setOnChannelSelectedListener(final OnClickGesture<ChannelInfoItem> listener) {
        infoItemBuilder.setOnChannelSelectedListener(listener);
    }

    public void setOnPlaylistSelectedListener(final OnClickGesture<PlaylistInfoItem> listener) {
        infoItemBuilder.setOnPlaylistSelectedListener(listener);
    }

    public void setOnCommentsSelectedListener(final OnClickGesture<CommentsInfoItem> listener) {
        infoItemBuilder.setOnCommentsSelectedListener(listener);
    }

    public void setUseMiniVariant(final boolean useMiniVariant) {
        this.useMiniVariant = useMiniVariant;
    }

    public void setItemViewMode(final ItemViewMode itemViewMode) {
        this.itemMode = itemViewMode;
    }

    public void addInfoItemList(@Nullable final List<? extends InfoItem> data) {
        addItems(data);
    }

    @Override
    protected int getViewTypeOf(@NonNull final InfoItem item) {
        switch (item.getInfoType()) {
            case STREAM:
                if (itemMode == ItemViewMode.CARD) {
                    return CARD_STREAM_HOLDER_TYPE;
                } else if (itemMode == ItemViewMode.GRID) {
                    return GRID_STREAM_HOLDER_TYPE;
                } else if (useMiniVariant) {
                    return MINI_STREAM_HOLDER_TYPE;
                } else {
                    return STREAM_HOLDER_TYPE;
                }
            case CHANNEL:
                if (itemMode == ItemViewMode.CARD) {
                    return CARD_CHANNEL_HOLDER_TYPE;
                } else if (itemMode == ItemViewMode.GRID) {
                    return GRID_CHANNEL_HOLDER_TYPE;
                } else if (useMiniVariant) {
                    return MINI_CHANNEL_HOLDER_TYPE;
                } else {
                    return CHANNEL_HOLDER_TYPE;
                }
            case PLAYLIST:
                if (itemMode == ItemViewMode.CARD) {
                    return CARD_PLAYLIST_HOLDER_TYPE;
                } else if (itemMode == ItemViewMode.GRID) {
                    return GRID_PLAYLIST_HOLDER_TYPE;
                } else if (useMiniVariant) {
                    return MINI_PLAYLIST_HOLDER_TYPE;
                } else {
                    return PLAYLIST_HOLDER_TYPE;
                }
            case COMMENT:
                return COMMENT_HOLDER_TYPE;
            default:
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
            // #4475 and #3368
            // Always create a new instance otherwise the same instance
            // is sometimes reused which causes a crash
            case HEADER_TYPE:
                return new HFHolder(createHeaderView());
            case FOOTER_TYPE:
                return new HFHolder(PignateFooterBinding
                        .inflate(layoutInflater, parent, false)
                        .getRoot()
                );
            case MINI_STREAM_HOLDER_TYPE:
                return new StreamMiniInfoItemHolder(infoItemBuilder, parent);
            case STREAM_HOLDER_TYPE:
                return new StreamInfoItemHolder(infoItemBuilder, parent);
            case GRID_STREAM_HOLDER_TYPE:
                return new StreamInfoItemHolder(infoItemBuilder,
                        R.layout.list_stream_grid_item, parent);
            case CARD_STREAM_HOLDER_TYPE:
                return new StreamInfoItemHolder(infoItemBuilder,
                        R.layout.list_stream_card_item, parent);
            case MINI_CHANNEL_HOLDER_TYPE:
                return new ChannelMiniInfoItemHolder(infoItemBuilder, parent);
            case CHANNEL_HOLDER_TYPE:
                return new ChannelMiniInfoItemHolder(infoItemBuilder,
                        R.layout.list_channel_item, parent);
            case CARD_CHANNEL_HOLDER_TYPE:
                return new ChannelCardInfoItemHolder(infoItemBuilder, parent);
            case GRID_CHANNEL_HOLDER_TYPE:
                return new ChannelMiniInfoItemHolder(infoItemBuilder,
                        R.layout.list_channel_grid_item, parent);
            case MINI_PLAYLIST_HOLDER_TYPE:
                return new PlaylistMiniInfoItemHolder(infoItemBuilder, parent);
            case PLAYLIST_HOLDER_TYPE:
                return new PlaylistMiniInfoItemHolder(infoItemBuilder,
                        R.layout.list_playlist_item, parent);
            case GRID_PLAYLIST_HOLDER_TYPE:
                return new PlaylistMiniInfoItemHolder(infoItemBuilder,
                        R.layout.list_playlist_grid_item, parent);
            case CARD_PLAYLIST_HOLDER_TYPE:
                return new PlaylistMiniInfoItemHolder(infoItemBuilder,
                        R.layout.list_playlist_card_item, parent);
            case COMMENT_HOLDER_TYPE:
                return new CommentInfoItemHolder(infoItemBuilder, parent);
            default:
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
        if (holder instanceof InfoItemHolder) {
            ((InfoItemHolder) holder).updateFromItem(
                    getItemsList().get(itemIndexOf(position)), recordManager);
        }
    }

    @Override
    public void onViewRecycled(@NonNull final RecyclerView.ViewHolder holder) {
        if (holder instanceof InfoItemHolder infoHolder) {
            infoHolder.recycle();
        }
        super.onViewRecycled(holder);
    }

    static class HFHolder extends RecyclerView.ViewHolder {
        HFHolder(final View v) {
            super(v);
        }
    }
}
