package org.schabi.newpipe.settings;

import android.Manifest;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import org.schabi.newpipe.PvcDownloaderImplUtils;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.PermissionHelper;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import okhttp3.HttpUrl;

/** Only explicit, visible custom-endpoint actions may request local network access. */
final class LocalNetworkPermissionGate {
    private final Fragment fragment;
    private final ActivityResultLauncher<String> permissionLauncher;
    private final SerialDisposable resolution = new SerialDisposable();
    private Runnable pending;

    LocalNetworkPermissionGate(final Fragment fragment) {
        this.fragment = fragment;
        permissionLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    final Runnable action = pending;
                    pending = null;
                    if (action == null || !isVisible()) {
                        return;
                    }
                    if (granted && PermissionHelper.hasLocalNetworkPermission(
                            fragment.requireContext())) {
                        action.run();
                    } else {
                        showDenied();
                    }
                });
    }

    void run(final String url, final Runnable action) {
        final HttpUrl endpoint = HttpUrl.parse(url);
        if (endpoint == null || !endpoint.isHttps()) {
            Toast.makeText(fragment.requireContext(), R.string.https_urls_only,
                    Toast.LENGTH_LONG).show();
            return;
        }
        // Do not replace the action belonging to an already visible system permission dialog.
        if (pending != null) {
            return;
        }
        resolution.set(Single.fromCallable(() ->
                PvcDownloaderImplUtils.requiresLocalNetwork(endpoint.host()))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(local -> {
                    if (!isVisible()) {
                        return;
                    }
                    if (!local || PermissionHelper.hasLocalNetworkPermission(
                            fragment.requireContext())) {
                        action.run();
                    } else {
                        pending = action;
                        permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK);
                    }
                }, error -> {
                    if (isVisible()) {
                        Toast.makeText(fragment.requireContext(), R.string.error_unknown_host,
                                Toast.LENGTH_LONG).show();
                    }
                }));
    }

    private boolean isVisible() {
        return fragment.getView() != null && fragment.getViewLifecycleOwner().getLifecycle()
                .getCurrentState().isAtLeast(Lifecycle.State.STARTED);
    }

    private void showDenied() {
        new AlertDialog.Builder(fragment.requireContext())
                .setMessage(R.string.local_network_permission_required)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    void clear() {
        resolution.set(null);
        pending = null;
    }
}
