package org.schabi.newpipe.local.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog.Builder;

import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.databinding.DialogEditTextBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.local.playlist.LocalPlaylistManager;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

public final class PlaylistCreationDialog extends PlaylistDialog {
    @Nullable
    private Disposable createPlaylistDisposable;

    @Override
    public void onDestroy() {
        // Creating a playlist is a finite database transaction that must finish after the
        // positive button dismisses this dialog. Keep it alive, but drop our completed-operation
        // reference with the rest of the fragment state.
        createPlaylistDisposable = null;
        super.onDestroy();
    }

    /**
     * Create a new instance of {@link PlaylistCreationDialog}.
     *
     * @param streamEntities    a list of {@link StreamEntity} to be added to playlists
     * @return a new instance of {@link PlaylistCreationDialog}
     */
    public static PlaylistCreationDialog newInstance(final List<StreamEntity> streamEntities) {
        final PlaylistCreationDialog dialog = new PlaylistCreationDialog();
        dialog.setStreamEntities(streamEntities);
        return dialog;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Dialog
    //////////////////////////////////////////////////////////////////////////*/

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable final Bundle savedInstanceState) {
        if (getStreamEntities() == null) {
            return super.onCreateDialog(savedInstanceState);
        }

        final Builder dialogBuilder = new Builder(requireContext(),
                ThemeHelper.getDialogTheme(requireContext()));
        final DialogEditTextBinding dialogBinding =
                DialogEditTextBinding.inflate(LayoutInflater.from(dialogBuilder.getContext()));
        dialogBinding.dialogEditText.setHint(R.string.name);
        dialogBinding.dialogEditText.setInputType(InputType.TYPE_CLASS_TEXT);

        dialogBuilder
                .setTitle(R.string.create_playlist)
                .setView(dialogBinding.getRoot())
                .setCancelable(true)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.create, (dialogInterface, i) -> {
                    final String name = dialogBinding.dialogEditText.getText().toString();
                    final Context applicationContext = requireContext().getApplicationContext();
                    final LocalPlaylistManager playlistManager =
                            new LocalPlaylistManager(NewPipeDatabase.getInstance(
                                    applicationContext));
                    final Toast successToast = Toast.makeText(applicationContext,
                            R.string.playlist_creation_success,
                            Toast.LENGTH_SHORT);

                    createPlaylistDisposable = playlistManager
                            .createPlaylist(name, getStreamEntities())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(longs -> successToast.show(), throwable ->
                                    ErrorUtil.createNotification(applicationContext,
                                            new ErrorInfo(throwable, UserAction.REQUESTED_PLAYLIST,
                                                    "Creating playlist")));
                });
        return dialogBuilder.create();
    }
}
