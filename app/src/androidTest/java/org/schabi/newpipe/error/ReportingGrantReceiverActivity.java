package org.schabi.newpipe.error;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Runs as the test APK's UID, without depending on the target app's classes. */
public class ReportingGrantReceiverActivity extends Activity {
    public static final String RESULT_ACTION = "org.schabi.newpipe.test.REPORTING_GRANT_RESULT";
    public static final String TARGET_PACKAGE = "reportingTargetPackage";

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent incoming = getIntent();
        final Uri uri = incoming.getParcelableExtra(Intent.EXTRA_STREAM);
        final Intent result = new Intent(RESULT_ACTION)
                .setPackage(incoming.getStringExtra(TARGET_PACKAGE))
                .putExtra("receiverUid", android.os.Process.myUid())
                .putExtra("token", incoming.getStringExtra("token"));
        try {
            result.putExtra("text", incoming.getStringExtra(Intent.EXTRA_TEXT));
            result.putExtra("stream", uri.toString());
            result.putExtra("clip", incoming.getClipData().getItemAt(0).getUri().toString());
            result.putExtra("clipCount", incoming.getClipData().getItemCount());
            try (InputStream input = getContentResolver().openInputStream(uri);
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                final byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                result.putExtra("contents", output.toString(StandardCharsets.UTF_8.name()));
            }
            boolean writeDenied = false;
            try (OutputStream ignored = getContentResolver().openOutputStream(uri, "wa")) {
                // Opening alone probes the grant, without changing the synthetic fixture.
                result.putExtra("writeOpened", ignored != null);
            } catch (final SecurityException exception) {
                writeDenied = true;
            }
            result.putExtra("writeDenied", writeDenied);
        } catch (final Exception exception) {
            result.putExtra("failure", exception.toString());
        }
        sendBroadcast(result);
        finish();
    }
}
