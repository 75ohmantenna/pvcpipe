package us.shandian.giga.service;

import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.functions.Function;
import java.util.concurrent.Callable;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.ErrorInfo;
import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.preprocessing.PvcHlsPreProcessor;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class HlsPreparationLifecycleTest {
    private Scheduler immediate;
    private Function<Callable<Scheduler>, Scheduler> previous;
    @Before public void setUp() {
        immediate = Schedulers.trampoline();
        previous = RxAndroidPlugins.getInitMainThreadSchedulerHandler();
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> immediate);
    }
    @After public void tearDown() {
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(previous);
    }
    @Test
    public void closeDropsAlreadyQueuedPreparationCompletion() throws Exception {
        final PvcDownloadManagerService service =
                mock(PvcDownloadManagerService.class, CALLS_REAL_METHODS);
        final Field field = PvcDownloadManagerService.class.getDeclaredField("hlsPreparation");
        field.setAccessible(true); field.set(service, new CompositeDisposable());
        final DownloadManager manager = mock(DownloadManager.class);
        final TestScheduler main = new TestScheduler();
        try (var android = mockStatic(AndroidSchedulers.class);
             var schedulers = mockStatic(Schedulers.class);
             var processor = mockConstruction(PvcHlsPreProcessor.class)) {
            android.when(AndroidSchedulers::mainThread).thenReturn(main);
            schedulers.when(Schedulers::io).thenReturn(immediate);
            service.pvcLaunchHlsPreProcessor(mock(DownloadMission.class), manager);
            service.cancelHlsPreparation();
            main.triggerActions();
            verifyNoInteractions(manager);
        }
    }

    @Test
    public void failureIsReportedWithoutStartingAMission() throws Exception {
        final PvcDownloadManagerService service =
                mock(PvcDownloadManagerService.class, CALLS_REAL_METHODS);
        final Field field = PvcDownloadManagerService.class.getDeclaredField("hlsPreparation");
        field.setAccessible(true); field.set(service, new CompositeDisposable());
        final DownloadManager manager = mock(DownloadManager.class);
        try (var android = mockStatic(AndroidSchedulers.class);
             var schedulers = mockStatic(Schedulers.class);
             var errors = mockStatic(ErrorUtil.class);
             var info = mockConstruction(ErrorInfo.class);
             var processor = mockConstruction(PvcHlsPreProcessor.class, (mock, context) ->
                     doThrow(new RuntimeException("fetch failed"))
                             .when(mock).modifyMission(any()))) {
            android.when(AndroidSchedulers::mainThread).thenReturn(immediate);
            schedulers.when(Schedulers::io).thenReturn(immediate);
            service.pvcLaunchHlsPreProcessor(mock(DownloadMission.class), manager);
            errors.verify(() -> ErrorUtil.createNotification(eq(service), any()));
            verify(service).onHlsPreparationFinished();
            verifyNoInteractions(manager);
        }
    }
}
