/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package us.shandian.giga.service;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Grants only this test provider's existing nonce fixture to the disposable debug app. */
public class DeletionGrantActivity extends Activity {
    @Override protected void onCreate(final Bundle state) {
        super.onCreate(state);
        DeletionDocumentsProvider.requireEmulator();
        final String id = getIntent().getStringExtra("fixtureId");
        DeletionDocumentsProvider.requireId(id);
        if (!getPackageName().equals("org.seventyfiveohmantenna.pvcpipe.debug.test")) {
            throw new SecurityException("Wrong fixture package");
        }
        getContentResolver().call(DocumentsContract.buildDocumentUri(
                getPackageName() + ".download-delete", id), "create-fixture", id, null);
        grantUriPermission("org.seventyfiveohmantenna.pvcpipe.debug",
                DocumentsContract.buildDocumentUri(getPackageName() + ".download-delete", id),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        finish();
    }
}
