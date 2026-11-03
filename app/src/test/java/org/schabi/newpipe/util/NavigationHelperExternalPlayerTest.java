package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.util.external_communication.ShareUtils;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class NavigationHelperExternalPlayerTest {
    @Test
    public void externalProgressiveAudioUsesExtractedFormatMime() {
        final Context context = RuntimeEnvironment.getApplication();
        for (final MediaFormat format : new MediaFormat[]{MediaFormat.AAC, MediaFormat.M4A}) {
            final String mime = format == MediaFormat.AAC ? "audio/aac" : "audio/mp4";
            final AudioStream audio = new AudioStream.Builder().setId("audio")
                    .setContent("https://media.example/audio?token=fixture", true)
                    .setMediaFormat(format).setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                    .setAverageBitrate(128).build();
            try (var share = mockStatic(ShareUtils.class)) {
                share.when(() -> ShareUtils.tryOpenIntentInApp(eq(context), any(Intent.class)))
                        .thenReturn(true);

                NavigationHelper.playOnExternalPlayer(context, "recording", "uploader", audio);

                final ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);
                share.verify(() -> ShareUtils.tryOpenIntentInApp(eq(context), intent.capture()));
                assertEquals(Intent.ACTION_VIEW, intent.getValue().getAction());
                assertEquals(audio.getContent(), intent.getValue().getDataString());
                assertEquals(mime, intent.getValue().getType());
                assertEquals("recording", intent.getValue().getStringExtra(Intent.EXTRA_TITLE));
            }
        }
    }
}
