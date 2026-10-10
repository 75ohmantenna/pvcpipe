package org.schabi.newpipe.fragments.detail;

import static org.schabi.newpipe.extractor.stream.StreamExtractor.NO_AGE_LIMIT;
import static org.schabi.newpipe.util.Localization.getAppLocale;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.util.Localization;

import java.util.List;
import android.view.ViewGroup;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.schabi.newpipe.pvc.bus.PvcBus;
import org.schabi.newpipe.pvc.PvcConstants;
import org.schabi.newpipe.pvc.bus.events.PvcEvents;
import org.schabi.newpipe.util.DeviceUtils;

public class DescriptionFragment extends BaseDescriptionFragment
        implements PvcEvents.PrefEventScrollOnlyBelowPlayer.Handler,
        PvcEvents.EventViewPagersContentHeight.Handler {
    private int originalScrollViewHeight = PvcConstants.LAYOUT_LENGTH_UNSET;

    private static final String STATE_SUFFIX =
            "org.schabi.newpipe.fragments.detail.DescriptionFragment$$StateSaver";

    StreamInfo streamInfo;

    public DescriptionFragment(final StreamInfo streamInfo) {
        this.streamInfo = streamInfo;
    }

    public DescriptionFragment() {
        // keep empty constructor for fragment restoration by Android
    }

    @Override
    protected void saveFragmentState(@NonNull final Bundle state) {
        super.saveFragmentState(state);
        state.putSerializable("streamInfo" + STATE_SUFFIX, streamInfo);
    }

    @Override
    protected void restoreFragmentState(@NonNull final Bundle state) {
        super.restoreFragmentState(state);
        state.setClassLoader(getClass().getClassLoader());
        if (state.containsKey("streamInfo" + STATE_SUFFIX)) {
            streamInfo = (StreamInfo) state.getSerializable("streamInfo" + STATE_SUFFIX);
        }
    }

    @Nullable
    @Override
    protected Description getDescription() {
        return streamInfo.getDescription();
    }

    @NonNull
    @Override
    protected StreamingService getService() {
        return streamInfo.getService();
    }

    @Override
    protected int getServiceId() {
        return streamInfo.getServiceId();
    }

    @NonNull
    @Override
    protected String getStreamUrl() {
        return streamInfo.getUrl();
    }

    @NonNull
    @Override
    public List<String> getTags() {
        return streamInfo.getTags();
    }

    @Override
    protected void setupMetadata(final LayoutInflater inflater,
                                 final LinearLayout layout) {
        if (streamInfo != null && streamInfo.getUploadDate() != null) {
            binding.detailUploadDateView.setText(Localization
                    .localizeUploadDate(activity, streamInfo.getUploadDate().offsetDateTime()));
        } else {
            binding.detailUploadDateView.setVisibility(View.GONE);
        }

        if (streamInfo == null) {
            return;
        }

        addMetadataItem(inflater, layout, false, R.string.metadata_category,
                streamInfo.getCategory());

        addMetadataItem(inflater, layout, false, R.string.metadata_licence,
                streamInfo.getLicence());

        addPrivacyMetadataItem(inflater, layout);

        if (streamInfo.getAgeLimit() != NO_AGE_LIMIT) {
            addMetadataItem(inflater, layout, false, R.string.metadata_age_limit,
                    String.valueOf(streamInfo.getAgeLimit()));
        }

        if (streamInfo.getLanguageInfo() != null) {
            addMetadataItem(inflater, layout, false, R.string.metadata_language,
                    streamInfo.getLanguageInfo().getDisplayLanguage(getAppLocale()));
        }

        addMetadataItem(inflater, layout, true, R.string.metadata_support,
                streamInfo.getSupportInfo());
        addMetadataItem(inflater, layout, true, R.string.metadata_host,
                streamInfo.getHost());

        addImagesMetadataItem(inflater, layout, R.string.metadata_thumbnails,
                streamInfo.getThumbnails());
        addImagesMetadataItem(inflater, layout, R.string.metadata_uploader_avatars,
                streamInfo.getUploaderAvatars());
        addImagesMetadataItem(inflater, layout, R.string.metadata_subchannel_avatars,
                streamInfo.getSubChannelAvatars());
    }

    private void addPrivacyMetadataItem(final LayoutInflater inflater, final LinearLayout layout) {
        if (streamInfo.getPrivacy() != null) {
            @StringRes final int contentRes;
            switch (streamInfo.getPrivacy()) {
                case PUBLIC:
                    contentRes = R.string.metadata_privacy_public;
                    break;
                case UNLISTED:
                    contentRes = R.string.metadata_privacy_unlisted;
                    break;
                case PRIVATE:
                    contentRes = R.string.metadata_privacy_private;
                    break;
                case INTERNAL:
                    contentRes = R.string.metadata_privacy_internal;
                    break;
                case OTHER:
                default:
                    contentRes = 0;
                    break;
            }

            if (contentRes != 0) {
                addMetadataItem(inflater, layout, false, R.string.metadata_privacy,
                        getString(contentRes));
            }
        }
    }

    @Override
    public void onViewCreated(
            final @NonNull View rootView,
            final Bundle savedInstanceState) {
        super.onViewCreated(rootView, savedInstanceState);
        PvcBus.getBus().register(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        PvcBus.getBus().unregister(this);
    }

    @Override
    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void handleEventViewPagersContentHeight(
            final PvcEvents.EventViewPagersContentHeight event) {
        storeOriginalScrollViewLayoutHeightAndSetNew(event.height);
    }

    private void storeOriginalScrollViewLayoutHeightAndSetNew(
            final int height) {
        final ViewGroup.LayoutParams params = binding.scrollView.getLayoutParams();

        // store original height if not already done
        if (PvcConstants.Helper.isDimensionUnset(originalScrollViewHeight)) {
            originalScrollViewHeight = params.height;
        }

        if (params.height != height) {
            params.height = height;
            binding.scrollView.setLayoutParams(params);
            binding.scrollView.requestLayout();
        }
    }

    @Override
    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void handlePrefEventScrollOnlyBelowPlayer(
            final PvcEvents.PrefEventScrollOnlyBelowPlayer event) {
        if (!DeviceUtils.isLandscape(requireContext()) && event.doScrollInViewPager) {
            binding.scrollView.setNestedScrollingEnabled(false);
        } else {
            binding.scrollView.setNestedScrollingEnabled(true);
            if (!PvcConstants.Helper.isDimensionUnset(originalScrollViewHeight)) {
                storeOriginalScrollViewLayoutHeightAndSetNew(originalScrollViewHeight);
                originalScrollViewHeight = PvcConstants.LAYOUT_LENGTH_UNSET;
            }
        }
    }
}
