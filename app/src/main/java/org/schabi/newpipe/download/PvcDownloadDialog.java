package org.schabi.newpipe.download;

import android.content.Context;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;

import org.schabi.newpipe.databinding.DownloadDialogBinding;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.util.SponsorBlock;
import org.schabi.newpipe.util.SponsorBlockSegment;

import java.util.List;

import androidx.fragment.app.DialogFragment;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import static org.schabi.newpipe.extractor.stream.DeliveryMethod.HLS;
import static org.schabi.newpipe.ktx.ViewUtils.animate;
import static org.schabi.newpipe.util.ListHelper.getStreamsOfSpecifiedDelivery;

public abstract class PvcDownloadDialog extends DialogFragment {
    private static final String TAG = PvcDownloadDialog.class.getSimpleName();

    private SponsorBlockSegment[] sponsorBlockSegments = new SponsorBlockSegment[0];
    private Disposable sponsorBlockDisposable;

    protected List<VideoStream> pvcAddHlsStreams(
            final StreamInfo info,
            final List<VideoStream> videoStreams) {
        videoStreams.addAll(getStreamsOfSpecifiedDelivery(info.getVideoStreams(), HLS));

        return videoStreams;
    }

    @Override
    public void onDestroyView() {
        if (sponsorBlockDisposable != null) {
            sponsorBlockDisposable.dispose();
        }
        super.onDestroyView();
    }

    protected void loadSponsorBlockSegments(
            final StreamInfo currentInfo,
            final MenuItem okButton,
            final DownloadDialogBinding dialogBinding) {
        final Context applicationContext = requireContext().getApplicationContext();
        if (!SponsorBlock.canFetch(applicationContext, currentInfo)) {
            return;
        }

        showLoading(dialogBinding);
        okButton.setEnabled(false);
        sponsorBlockDisposable = Single.fromCallable(
                        () -> SponsorBlock.getSegments(applicationContext, currentInfo))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> {
                    if (getView() != null) {
                        okButton.setEnabled(true);
                        hideLoading(dialogBinding);
                    }
                })
                .subscribe(segments -> sponsorBlockSegments = segments,
                        error -> Log.w(TAG, "SponsorBlock lookup failed", error));
    }

    private void showLoading(final DownloadDialogBinding dialogBinding) {
        dialogBinding.fileName.setVisibility(View.GONE);
        animate(dialogBinding.loadingProgressBar, true, 400);
    }

    private void hideLoading(final DownloadDialogBinding dialogBinding) {
        animate(dialogBinding.loadingProgressBar, false, 0);
        dialogBinding.fileName.setVisibility(View.VISIBLE);
    }

    protected SponsorBlockSegment[] pvcSponsorBlockSegments() {
        return sponsorBlockSegments.clone();
    }
}
