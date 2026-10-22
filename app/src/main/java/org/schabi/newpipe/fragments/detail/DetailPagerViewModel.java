package org.schabi.newpipe.fragments.detail;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

/**
 * State the detail screen shares with the fragments in its pager: the "scroll only below player"
 * setting and the height the pager content should have.
 * <p>
 * It is scoped to the activity. Each observer receives the latest value when its view starts.
 */
public final class DetailPagerViewModel extends AndroidViewModel {
    private final MutableLiveData<Boolean> scrollOnlyBelowPlayer = new MutableLiveData<>();
    private final MutableLiveData<Integer> pagerContentHeight = new MutableLiveData<>();

    private final SharedPreferences preferences;
    private final String scrollOnlyBelowPlayerKey;
    // Held in a field because SharedPreferences only keeps weak references to its listeners.
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;

    public DetailPagerViewModel(@NonNull final Application application) {
        super(application);
        preferences = PreferenceManager.getDefaultSharedPreferences(application);
        scrollOnlyBelowPlayerKey =
                application.getString(R.string.pvc_settings_scroll_only_below_player_key);
        publish(scrollOnlyBelowPlayer, readScrollOnlyBelowPlayer());

        preferenceListener = (sharedPreferences, key) -> {
            if (scrollOnlyBelowPlayerKey.equals(key)) {
                publish(scrollOnlyBelowPlayer, readScrollOnlyBelowPlayer());
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    /**
     * Gets the detail pager view model shared by the fragment's activity.
     *
     * @param fragment a fragment attached to the activity that shows the detail screen
     * @return the view model shared by that activity's fragments
     */
    public static DetailPagerViewModel of(@NonNull final Fragment fragment) {
        return new ViewModelProvider(fragment.requireActivity()).get(DetailPagerViewModel.class);
    }

    public LiveData<Boolean> getScrollOnlyBelowPlayer() {
        return scrollOnlyBelowPlayer;
    }

    public LiveData<Integer> getPagerContentHeight() {
        return pagerContentHeight;
    }

    /**
     * Publishes a new height for the pager content. It is ignored while "scroll only below
     * player" is off, because the content then scrolls with the whole screen.
     *
     * @param height the height the views inside the pager should take
     */
    public void publishPagerContentHeight(final int height) {
        if (Boolean.TRUE.equals(scrollOnlyBelowPlayer.getValue())) {
            publish(pagerContentHeight, height);
        }
    }

    @Override
    protected void onCleared() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
    }

    private boolean readScrollOnlyBelowPlayer() {
        return preferences.getBoolean(scrollOnlyBelowPlayerKey, false);
    }

    private static <T> void publish(final MutableLiveData<T> liveData, final T value) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            liveData.setValue(value);
        } else {
            liveData.postValue(value);
        }
    }
}
