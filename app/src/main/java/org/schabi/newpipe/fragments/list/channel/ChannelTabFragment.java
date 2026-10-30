package org.schabi.newpipe.fragments.list.channel;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PlaylistControlBinding;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.ReadyChannelTabListLinkHandler;
import org.schabi.newpipe.extractor.services.kick.KickClipFilters;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.list.BaseListInfoFragment;
import org.schabi.newpipe.fragments.list.playlist.PlaylistControlViewHolder;
import org.schabi.newpipe.player.playqueue.ChannelTabPlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.util.ChannelTabHelper;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.PlayButtonHelper;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.core.Single;

public class ChannelTabFragment extends BaseListInfoFragment<InfoItem, ChannelTabInfo>
        implements PlaylistControlViewHolder {
    private static final String STATE_SUFFIX =
            "org.schabi.newpipe.fragments.list.channel.ChannelTabFragment$$StateSaver";

    protected ListLinkHandler tabHandler;
    protected String channelName;
    private TextView clipSort;
    private TextView clipTimeRange;

    @Override
    protected void saveFragmentState(@NonNull final Bundle state) {
        super.saveFragmentState(state);
        state.putString("channelName" + STATE_SUFFIX, channelName);
        state.putSerializable("tabHandler" + STATE_SUFFIX, tabHandler);
    }

    @Override
    protected void restoreFragmentState(@NonNull final Bundle state) {
        super.restoreFragmentState(state);
        state.setClassLoader(getClass().getClassLoader());
        if (state.containsKey("channelName" + STATE_SUFFIX)) {
            channelName = state.getString("channelName" + STATE_SUFFIX);
        }
        if (state.containsKey("tabHandler" + STATE_SUFFIX)) {
            tabHandler = (ListLinkHandler) state.getSerializable("tabHandler" + STATE_SUFFIX);
        }
    }

    private PlaylistControlBinding playlistControlBinding;

    @NonNull
    public static ChannelTabFragment getInstance(final int serviceId,
                                                 final ListLinkHandler tabHandler,
                                                 final String channelName) {
        final ChannelTabFragment instance = new ChannelTabFragment();
        instance.serviceId = serviceId;
        instance.tabHandler = tabHandler;
        instance.channelName = channelName;
        return instance;
    }

    public ChannelTabFragment() {
        super(UserAction.REQUESTED_CHANNEL);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_channel_tab, container, false);
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);
        initClipFilters();
    }

    private void initClipFilters() {
        final View row = requireView().findViewById(R.id.channel_tab_filters);
        final boolean clips = serviceId == ServiceList.Kick.getServiceId()
                && tabHandler.getContentFilters().contains(ChannelTabs.CLIPS);
        row.setVisibility(clips ? View.VISIBLE : View.GONE);
        if (!clips) {
            return;
        }
        clipSort = row.findViewById(R.id.channel_tab_sort);
        clipTimeRange = row.findViewById(R.id.channel_tab_time_range);
        clipSort.setOnClickListener(this::showClipSortMenu);
        clipTimeRange.setOnClickListener(this::showClipTimeRangeMenu);
        updateClipFilterLabels();
    }

    private void showClipSortMenu(final View anchor) {
        final PopupMenu menu = new PopupMenu(requireContext(), anchor);
        try {
            final String selected = KickClipFilters.sort(tabHandler.getSortFilter());
            menu.getMenu().add(0, 0, 0, R.string.search_filters_views)
                    .setChecked("views".equals(selected));
            menu.getMenu().add(0, 1, 1, R.string.search_filters_upload_date)
                    .setChecked("date".equals(selected));
            menu.getMenu().setGroupCheckable(0, true, true);
            menu.setOnMenuItemClickListener(item -> {
                try {
                    applyClipFilters(item.getItemId() == 0 ? "views" : "date",
                            KickClipFilters.time(tabHandler.getSortFilter()));
                } catch (final ParsingException e) {
                    Log.e(TAG, "Invalid Kick clip filters", e);
                }
                return true;
            });
            menu.show();
        } catch (final ParsingException e) {
            Log.e(TAG, "Invalid Kick clip filters", e);
        }
    }

    private void showClipTimeRangeMenu(final View anchor) {
        final PopupMenu menu = new PopupMenu(requireContext(), anchor);
        final String[] values = {"day", "week", "month", "all"};
        final int[] labels = {R.string.search_filters_past_day, R.string.search_filters_past_week,
                R.string.search_filters_past_month, R.string.search_filters_any_time};
        try {
            final String selected = KickClipFilters.time(tabHandler.getSortFilter());
            for (int i = 0; i < values.length; i++) {
                menu.getMenu().add(0, i, i, labels[i]).setChecked(values[i].equals(selected));
            }
            menu.getMenu().setGroupCheckable(0, true, true);
            menu.setOnMenuItemClickListener(item -> {
                try {
                    applyClipFilters(KickClipFilters.sort(tabHandler.getSortFilter()),
                            values[item.getItemId()]);
                } catch (final ParsingException e) {
                    Log.e(TAG, "Invalid Kick clip filters", e);
                }
                return true;
            });
            menu.show();
        } catch (final ParsingException e) {
            Log.e(TAG, "Invalid Kick clip filters", e);
        }
    }

    private void applyClipFilters(final String sort, final String range) throws ParsingException {
        if (sort.equals(KickClipFilters.sort(tabHandler.getSortFilter()))
                && range.equals(KickClipFilters.time(tabHandler.getSortFilter()))) {
            return;
        }
        tabHandler = ServiceList.Kick.getChannelTabLHFactory().fromQuery(tabHandler.getId(),
                tabHandler.getContentFilters(), KickClipFilters.fromValues(sort, range));
        url = tabHandler.getUrl();
        currentNextPage = null;
        updateClipFilterLabels();
        startLoading(false);
    }

    private void updateClipFilterLabels() {
        try {
            final String sort = KickClipFilters.sort(tabHandler.getSortFilter());
            final String range = KickClipFilters.time(tabHandler.getSortFilter());
            clipSort.setText(getString(R.string.kick_clips_sort_selected, getString(
                    "views".equals(sort) ? R.string.search_filters_views
                            : R.string.search_filters_upload_date)));
            final int rangeLabel;
            switch (range) {
                case "day": rangeLabel = R.string.search_filters_past_day; break;
                case "week": rangeLabel = R.string.search_filters_past_week; break;
                case "month": rangeLabel = R.string.search_filters_past_month; break;
                default: rangeLabel = R.string.search_filters_any_time; break;
            }
            clipTimeRange.setText(getString(R.string.kick_clips_time_selected,
                    getString(rangeLabel)));
        } catch (final ParsingException e) {
            Log.e(TAG, "Invalid Kick clip filters", e);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        playlistControlBinding = null;
        clipSort = null;
        clipTimeRange = null;
    }

    @Override
    protected Supplier<View> getListHeaderSupplier() {
        if (ChannelTabHelper.isStreamsTab(tabHandler)) {
            playlistControlBinding = PlaylistControlBinding
                    .inflate(activity.getLayoutInflater(), itemsList, false);
            return playlistControlBinding::getRoot;
        }
        return null;
    }

    @Override
    protected Single<ChannelTabInfo> loadResult(final boolean forceLoad) {
        return ExtractorHelper.getChannelTab(serviceId, tabHandler, forceLoad);
    }

    @Override
    protected Single<ListExtractor.InfoItemsPage<InfoItem>> loadMoreItemsLogic() {
        return ExtractorHelper.getMoreChannelTabItems(serviceId, tabHandler, currentNextPage);
    }

    @Override
    public void setTitle(final String title) {
        // The channel name is displayed as title in the toolbar.
        // The title is always a description of the content of the tab fragment.
        // It should be unique for each channel because multiple channel tabs
        // can be added to the main page. Therefore, the channel name is used.
        // Using the title variable would cause the title to be the same for all channel tabs.
        super.setTitle(channelName);
    }

    @Override
    public void handleResult(@NonNull final ChannelTabInfo result) {
        super.handleResult(result);

        // FIXME this is a really hacky workaround, to avoid storing useless data in the fragment
        //  state. The problem is, `ReadyChannelTabListLinkHandler` might contain raw JSON data that
        //  uses a lot of memory (e.g. ~800KB for YouTube). While 800KB doesn't seem much, if
        //  you combine just a couple of channel tab fragments you easily go over the 1MB
        //  save&restore transaction limit, and get `TransactionTooLargeException`s. A proper
        //  solution would require rethinking about `ReadyChannelTabListLinkHandler`s.
        if (tabHandler instanceof ReadyChannelTabListLinkHandler) {
            try {
                // once `handleResult` is called, the parsed data was already saved to cache, so
                // we can discard any raw data in ReadyChannelTabListLinkHandler and create a
                // link handler with identical properties, but without any raw data
                final ListLinkHandlerFactory channelTabLHFactory = result.getService()
                        .getChannelTabLHFactory();
                if (channelTabLHFactory != null) {
                    // some services do not not have a ChannelTabLHFactory
                    tabHandler = channelTabLHFactory.fromQuery(tabHandler.getId(),
                            tabHandler.getContentFilters(), tabHandler.getSortFilter());
                }
            } catch (final ParsingException e) {
                // silently ignore the error, as the app can continue to function normally
                Log.w(TAG, "Could not recreate channel tab handler", e);
            }
        }

        if (playlistControlBinding != null) {
            // PlaylistControls should be visible only if there is some item in
            // infoListAdapter other than header
            if (infoListAdapter.getItemCount() > 1) {
                playlistControlBinding.getRoot().setVisibility(View.VISIBLE);
            } else {
                playlistControlBinding.getRoot().setVisibility(View.GONE);
            }

            PlayButtonHelper.initPlaylistControlClickListener(
                    activity, playlistControlBinding, this);
        }
    }

    @Override
    public PlayQueue getPlayQueue() {
        final List<StreamInfoItem> streamItems = infoListAdapter.getItemsList().stream()
                .filter(StreamInfoItem.class::isInstance)
                .map(StreamInfoItem.class::cast)
                .collect(Collectors.toList());

        return new ChannelTabPlayQueue(currentInfo.getServiceId(), tabHandler,
                currentInfo.getNextPage(), streamItems, 0);
    }
}
