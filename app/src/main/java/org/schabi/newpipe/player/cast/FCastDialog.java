package org.schabi.newpipe.player.cast;

import android.Manifest;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.DialogFcastAddressBinding;
import org.schabi.newpipe.databinding.DialogFcastBinding;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.helper.PlayerHolder;
import org.schabi.newpipe.player.ui.VideoPlayerUi;
import org.schabi.newpipe.util.PermissionHelper;
import org.schabi.newpipe.util.external_communication.ShareUtils;

import java.util.List;

/** The picker is activity-themed, not wrapped in the video surface's white-tint theme. */
public final class FCastDialog extends DialogFragment implements FCastDiscovery.Listener {
    private static final String TAG = "fcast_dialog";
    private static final String PERMISSION_REQUESTED = "local_network_requested";
    private static final String DISMISS_AFTER_CONNECT = "dismiss_after_connect";
    private final Runnable changed = this::render;
    private final ActivityResultLauncher<String> permission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (this.binding == null || !isResumed()) {
                    return;
                }
                if (granted) {
                    startDiscovery();
                }
                render();
            });
    @Nullable private DialogFcastBinding binding;
    @Nullable private CastPlayback casting;
    @Nullable private Player player;
    @Nullable private FCastDiscovery discovery;
    @Nullable private AlertDialog addressDialog;
    private boolean permissionRequested;
    private boolean dismissAfterConnect;
    private String discoveryError;

    public static void show(final FragmentManager manager) {
        if (!manager.isStateSaved() && manager.findFragmentByTag(TAG) == null) {
            new FCastDialog().show(manager, TAG);
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable final Bundle savedInstanceState) {
        permissionRequested = savedInstanceState != null
                && savedInstanceState.getBoolean(PERMISSION_REQUESTED);
        dismissAfterConnect = savedInstanceState != null
                && savedInstanceState.getBoolean(DISMISS_AFTER_CONNECT);
        binding = DialogFcastBinding.inflate(getLayoutInflater());
        binding.addAddress.setOnClickListener(view -> showAddressDialog());
        binding.getReceiver.setOnClickListener(view ->
                ShareUtils.openUrlInBrowser(requireContext(), "https://fcast.org/"));
        binding.allowNetwork.setOnClickListener(view -> requestNetwork());
        binding.stopCasting.setOnClickListener(view -> {
            if (casting != null) {
                dismissAfterConnect = false;
                casting.stop(false);
                dismiss();
            }
        });
        binding.playHere.setOnClickListener(view -> {
            if (casting != null) {
                dismissAfterConnect = false;
                casting.stop(true);
                dismiss();
            }
        });
        return new AlertDialog.Builder(requireContext())
                .setTitle(R.string.fcast_title)
                .setIcon(R.drawable.ic_cast)
                .setView(binding.getRoot())
                .setNegativeButton(R.string.close, null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        player = PlayerHolder.getInstance().getPlayer().orElse(null);
        if (player == null) {
            dismiss();
            return;
        }
        casting = player.getCasting();
        casting.addListener(changed);
        if (PermissionHelper.hasLocalNetworkPermission(requireContext())) {
            startDiscovery();
        } else if (!permissionRequested) {
            requestNetwork();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Permission results and connection changes can arrive while the fragment is stopped.
        render();
    }

    private void requestNetwork() {
        permissionRequested = true;
        permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK);
    }

    private void startDiscovery() {
        if (discovery == null && binding != null && casting != null
                && PermissionHelper.hasLocalNetworkPermission(requireContext())) {
            discoveryError = null;
            binding.castDevices.removeAllViews();
            discovery = new FCastDiscovery(requireContext(), this);
            discovery.start();
        }
    }

    private void stopDiscovery() {
        if (discovery != null) {
            discovery.close();
            discovery = null;
        }
    }

    @Override
    public void onStop() {
        stopDiscovery();
        if (casting != null) {
            casting.removeListener(changed);
        }
        if (addressDialog != null) {
            addressDialog.dismiss();
            addressDialog = null;
        }
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        casting = null;
        player = null;
        super.onDestroyView();
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(PERMISSION_REQUESTED, permissionRequested);
        state.putBoolean(DISMISS_AFTER_CONNECT, dismissAfterConnect);
    }

    @Override
    public void onDevicesChanged(final List<FCastDevice> devices) {
        if (binding == null) {
            return;
        }
        binding.castDevices.removeAllViews();
        for (final FCastDevice device : devices) {
            final View item = getLayoutInflater().inflate(
                    android.R.layout.simple_list_item_2, binding.castDevices, false);
            ((TextView) item.findViewById(android.R.id.text1)).setText(device.getName());
            ((TextView) item.findViewById(android.R.id.text2)).setText(
                    device.getHost() + ":" + device.getPort());
            item.setBackground(org.schabi.newpipe.util.ThemeHelper.resolveDrawable(
                    requireContext(), android.R.attr.selectableItemBackground));
            item.setFocusable(true);
            item.setClickable(true);
            item.setEnabled(casting != null && casting.getSession().getConnectionState() != 1);
            item.setOnClickListener(view -> {
                if (casting != null && casting.getSession().getConnectionState() != 1) {
                    dismissAfterConnect = true;
                    casting.connect(device);
                    render();
                }
            });
            binding.castDevices.addView(item);
        }
    }

    @Override
    public void onError(final String message) {
        discoveryError = message;
        render();
    }

    private void render() {
        if (binding == null || casting == null || !isAdded()) {
            return;
        }
        final FCastSession session = casting.getSession();
        final int connection = session.getConnectionState();
        final boolean connected = connection == 2;
        final boolean busy = connection == 1 || casting.isPreparing();
        final boolean allowed = PermissionHelper.hasLocalNetworkPermission(requireContext());
        if (dismissAfterConnect && connected && casting.isActive() && !casting.isPreparing()
                && !getParentFragmentManager().isStateSaved()) {
            dismissAfterConnect = false;
            if (player != null) {
                player.UIs().get(VideoPlayerUi.class)
                        .ifPresent(VideoPlayerUi::showControlsThenHide);
            }
            dismiss();
            return;
        }
        binding.playHere.setVisibility(casting.isActive() ? View.VISIBLE : View.GONE);
        binding.stopCasting.setVisibility(connection != 0 ? View.VISIBLE : View.GONE);
        binding.stopCasting.setText(connection == 1
                ? R.string.fcast_cancel_connection : R.string.fcast_disconnect);
        binding.castProgress.setVisibility(busy || connection == 0 && allowed
                ? View.VISIBLE : View.GONE);
        binding.allowNetwork.setVisibility(allowed ? View.GONE : View.VISIBLE);
        binding.addAddress.setEnabled(allowed && connection != 1);
        for (int index = 0; index < binding.castDevices.getChildCount(); index++) {
            binding.castDevices.getChildAt(index).setEnabled(allowed && connection != 1);
        }
        if (connected) {
            binding.castStatus.setText(getString(casting.isPreparing()
                    ? R.string.fcast_preparing : R.string.fcast_connected,
                    session.getDeviceName()));
        } else if (connection == 1) {
            binding.castStatus.setText(getString(R.string.fcast_connecting,
                    session.getDeviceName()));
        } else {
            dismissAfterConnect = false;
            final String error = casting.getError() != null ? casting.getError() : discoveryError;
            binding.castStatus.setText(!allowed ? getString(R.string.fcast_local_network)
                    : error == null ? getString(R.string.fcast_searching) : error);
        }
        startDiscovery();
    }

    private void showAddressDialog() {
        final DialogFcastAddressBinding address = DialogFcastAddressBinding
                .inflate(getLayoutInflater());
        addressDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.fcast_add_address)
                .setView(address.getRoot())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.fcast_connect, null).create();
        addressDialog.setOnShowListener(ignored -> addressDialog
                .getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    if (casting == null) {
                        return;
                    }
                    final String host = address.receiverAddress.getText().toString().trim();
                    if (host.startsWith("fcast://")) {
                        dismissAfterConnect = true;
                        casting.connectUrl(host);
                    } else {
                        final int port;
                        try {
                            port = Integer.parseInt(address.receiverPort.getText().toString());
                            if (host.isEmpty() || host.contains("/") || host.contains(" ")
                                    || port < 1 || port > 65535) {
                                throw new IllegalArgumentException();
                            }
                        } catch (final IllegalArgumentException failure) {
                            address.receiverAddress.setError(
                                    getString(R.string.fcast_address_error));
                            return;
                        }
                        dismissAfterConnect = true;
                        casting.connect(host, port);
                    }
                    addressDialog.dismiss();
                    render();
                }));
        addressDialog.show();
    }
}
