package org.schabi.newpipe.local.subscription;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.os.BundleCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import org.schabi.newpipe.R;
import org.schabi.newpipe.local.subscription.workers.SubscriptionImportInput;
import org.schabi.newpipe.local.subscription.workers.SubscriptionTransfer;

public class ImportConfirmationDialog extends DialogFragment {
    private static final String INPUT = "input";

    public static void show(@NonNull final Fragment fragment, final SubscriptionImportInput input) {
        final var confirmationDialog = new ImportConfirmationDialog();
        final var arguments = new Bundle();
        arguments.putParcelable(INPUT, input);
        confirmationDialog.setArguments(arguments);
        confirmationDialog.show(fragment.getParentFragmentManager(), null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable final Bundle savedInstanceState) {
        final var context = requireContext();
        return new AlertDialog.Builder(context)
                .setMessage(R.string.import_network_expensive_warning)
                .setCancelable(true)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialogInterface, i) -> {
                    final var input = BundleCompat.getParcelable(requireArguments(), INPUT,
                            SubscriptionImportInput.class);
                    SubscriptionTransfer.enqueueImport(context, input);

                    dismiss();
                })
                .create();
    }

}
