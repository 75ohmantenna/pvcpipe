package org.schabi.newpipe.info_list.holder;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.info_list.InfoItemBuilder;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.DependentPreferenceHelper;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.StreamTypeUtil;
import org.schabi.newpipe.util.image.CoilHelper;
import org.schabi.newpipe.views.AnimatedProgressBar;

import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.SerialDisposable;

public class StreamMiniInfoItemHolder extends InfoItemHolder {
    public final ImageView itemThumbnailView;
    public final TextView itemVideoTitleView;
    public final TextView itemUploaderView;
    public final TextView itemDurationView;
    private final AnimatedProgressBar itemProgressView;
    private final SerialDisposable stateSubscription = new SerialDisposable();
    private InfoItem boundItem;

    StreamMiniInfoItemHolder(final InfoItemBuilder infoItemBuilder, final int layoutId,
                             final ViewGroup parent) {
        super(infoItemBuilder, layoutId, parent);

        itemThumbnailView = itemView.findViewById(R.id.itemThumbnailView);
        itemVideoTitleView = itemView.findViewById(R.id.itemVideoTitleView);
        itemUploaderView = itemView.findViewById(R.id.itemUploaderView);
        itemDurationView = itemView.findViewById(R.id.itemDurationView);
        itemProgressView = itemView.findViewById(R.id.itemProgressView);
    }

    public StreamMiniInfoItemHolder(final InfoItemBuilder infoItemBuilder, final ViewGroup parent) {
        this(infoItemBuilder, R.layout.list_stream_mini_item, parent);
    }

    @Override
    public void updateFromItem(final InfoItem infoItem,
                               final HistoryRecordManager historyRecordManager) {
        stateSubscription.set(null);
        boundItem = infoItem;
        itemProgressView.setVisibility(View.GONE);
        if (!(infoItem instanceof StreamInfoItem)) {
            return;
        }
        final StreamInfoItem item = (StreamInfoItem) infoItem;

        itemVideoTitleView.setText(item.getName());
        itemUploaderView.setText(item.getUploaderName());

        if (item.getDuration() > 0) {
            itemDurationView.setText(Localization.getDurationString(item.getDuration()));
            itemDurationView.setBackgroundColor(itemBuilder.getContext()
                    .getColor(R.color.duration_background_color));
            itemDurationView.setVisibility(View.VISIBLE);

            loadState(item, historyRecordManager, false);
        } else if (StreamTypeUtil.isLiveStream(item.getStreamType())) {
            itemDurationView.setText(R.string.duration_live);
            itemDurationView.setBackgroundColor(itemBuilder.getContext()
                    .getColor(R.color.live_duration_background_color));
            itemDurationView.setVisibility(View.VISIBLE);
            itemProgressView.setVisibility(View.GONE);
        } else {
            itemDurationView.setVisibility(View.GONE);
            itemProgressView.setVisibility(View.GONE);
        }

        // Default thumbnail is shown on error, while loading and if the url is empty
        CoilHelper.INSTANCE.loadThumbnail(itemThumbnailView, item.getThumbnails());

        itemView.setOnClickListener(view -> {
            if (itemBuilder.getOnStreamSelectedListener() != null) {
                itemBuilder.getOnStreamSelectedListener().selected(item);
            }
        });

        switch (item.getStreamType()) {
            case AUDIO_STREAM:
            case VIDEO_STREAM:
            case LIVE_STREAM:
            case AUDIO_LIVE_STREAM:
            case POST_LIVE_STREAM:
            case POST_LIVE_AUDIO_STREAM:
                enableLongClick(item);
                break;
            case NONE:
            default:
                disableLongClick();
                break;
        }
    }

    @Override
    public void updateState(final InfoItem infoItem,
                            final HistoryRecordManager historyRecordManager) {
        if (boundItem == infoItem && infoItem instanceof StreamInfoItem item) {
            loadState(item, historyRecordManager, true);
        }
    }

    private void loadState(final StreamInfoItem item, final HistoryRecordManager manager,
                           final boolean animate) {
        stateSubscription.set(null);
        if (!DependentPreferenceHelper.getPositionsInListsEnabled(itemProgressView.getContext())
                || item.getDuration() <= 0 || StreamTypeUtil.isLiveStream(item.getStreamType())) {
            itemProgressView.setVisibility(View.GONE);
            return;
        }
        stateSubscription.set(manager.loadStreamState(item)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(states -> {
                    if (boundItem == item) {
                        showState(item, states.length == 0 ? null : states[0], animate);
                    }
                }, error -> {
                    if (boundItem == item) {
                        itemProgressView.setVisibility(View.GONE);
                    }
                }));
    }

    private void showState(final StreamInfoItem item, final StreamStateEntity state,
                           final boolean animate) {
        if (state != null) {
            itemProgressView.setMax((int) item.getDuration());
            final int progress = (int) TimeUnit.MILLISECONDS.toSeconds(state.getProgressMillis());
            if (animate && itemProgressView.getVisibility() == View.VISIBLE) {
                itemProgressView.setProgressAnimated(progress);
            } else {
                itemProgressView.setProgress(progress);
                itemProgressView.setVisibility(View.VISIBLE);
            }
        } else {
            itemProgressView.setVisibility(View.GONE);
        }
    }

    @Override
    public void recycle() {
        stateSubscription.set(null);
        boundItem = null;
    }

    private void enableLongClick(final StreamInfoItem item) {
        itemView.setLongClickable(true);
        itemView.setOnLongClickListener(view -> {
            if (itemBuilder.getOnStreamSelectedListener() != null) {
                itemBuilder.getOnStreamSelectedListener().held(item);
            }
            return true;
        });
    }

    private void disableLongClick() {
        itemView.setLongClickable(false);
        itemView.setOnLongClickListener(null);
    }
}
