package us.shandian.giga.service;

import android.app.Service;

import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import us.shandian.giga.preprocessing.PvcHlsPreProcessor;
import us.shandian.giga.get.DownloadMission;

public abstract class PvcDownloadManagerService extends Service {

    protected static final String EXTRA_SEGMENTS = "DownloadManagerService.extra.segments";
    private final CompositeDisposable hlsPreparation = new CompositeDisposable();
    private int activeHlsPreparation;

    protected void pvcLaunchHlsPreProcessor(
            final DownloadMission mission,
            final DownloadManager manager) {
        activeHlsPreparation++;
        final SerialDisposable task = new SerialDisposable();
        hlsPreparation.add(task);
        task.set(Completable.fromAction(() ->
                new PvcHlsPreProcessor().modifyMission(mission))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> hlsPreparation.delete(task))
                .subscribe(() -> {
                    activeHlsPreparation--;
                    manager.startMission(mission);
                    onHlsPreparationFinished();
                }, error -> {
                    activeHlsPreparation--;
                    ErrorUtil.createNotification(this,
                            new ErrorInfo(error, UserAction.DOWNLOAD_FAILED,
                                    "Preparing HLS download"));
                    onHlsPreparationFinished();
                }));
    }

    protected boolean hasHlsPreparation() {
        return activeHlsPreparation > 0;
    }

    protected void cancelHlsPreparation() {
        hlsPreparation.dispose();
        activeHlsPreparation = 0;
    }

    protected abstract void onHlsPreparationFinished();
}
