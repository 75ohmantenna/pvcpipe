package org.schabi.newpipe.info_list.holder;

import static org.assertj.core.api.Assertions.assertThat;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.info_list.InfoItemBuilder;
import org.schabi.newpipe.util.Localization;

/** Inflates real rows without reading or writing app databases or changing preferences. */
@RunWith(AndroidJUnit4.class)
public class PaidVideoBadgeTest {
    @Test
    public void paidBadgesAndRecycledRowsRenderInEveryOnlineLayout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (final int theme : new int[]{R.style.LightTheme, R.style.DarkTheme}) {
                final Context context = new ContextThemeWrapper(
                        ApplicationProvider.getApplicationContext(), theme);
                for (final int layout : new int[]{R.layout.list_stream_item,
                        R.layout.list_stream_grid_item, R.layout.list_stream_card_item,
                        R.layout.list_stream_mini_item}) {
                    final var builder = new InfoItemBuilder(context);
                    final var parent = new FrameLayout(context);
                    final StreamMiniInfoItemHolder holder = layout == R.layout.list_stream_mini_item
                            ? new StreamMiniInfoItemHolder(builder, parent)
                            : new StreamInfoItemHolder(builder, layout, parent);
                    final var item = item(StreamType.VIDEO_STREAM, ContentAvailability.UNKNOWN);
                    for (final var availability : new ContentAvailability[]{
                            ContentAvailability.MEMBERSHIP, ContentAvailability.PAID}) {
                        item.setContentAvailability(availability);
                        for (final long duration : new long[]{-1, 120}) {
                            item.setDuration(duration);
                            holder.updateFromItem(item, null);
                            holder.updateState(item, null);
                            assertThat(holder.itemDurationView.getText().toString())
                                    .isEqualTo(context.getString(R.string.paid_video));
                            assertThat(holder.itemDurationView.getVisibility())
                                    .isEqualTo(View.VISIBLE);
                            assertColor(holder, context, R.color.paid_video_background_color);
                            assertThat(holder.itemView.findViewById(R.id.itemProgressView)
                                    .getVisibility()).isEqualTo(View.GONE);
                        }
                        holder.updateFromItem(item(StreamType.LIVE_STREAM, availability), null);
                        assertThat(holder.itemDurationView.getText().toString())
                                .isEqualTo(context.getString(R.string.paid_video));
                    }
                    for (final var availability : new ContentAvailability[]{
                            ContentAvailability.AVAILABLE, ContentAvailability.UNKNOWN,
                            ContentAvailability.UPCOMING}) {
                        item.setContentAvailability(availability);
                        item.setDuration(120);
                        holder.updateDuration(item);
                        assertThat(holder.itemDurationView.getText().toString())
                                .isEqualTo(Localization.getDurationString(120));
                        assertColor(holder, context, R.color.duration_background_color);
                        item.setDuration(-1);
                        holder.updateDuration(item(StreamType.LIVE_STREAM, availability));
                        assertThat(holder.itemDurationView.getText().toString())
                                .isEqualTo(context.getString(R.string.duration_live));
                        assertColor(holder, context, R.color.live_duration_background_color);
                        holder.updateDuration(item);
                        assertThat(holder.itemDurationView.getVisibility()).isEqualTo(View.GONE);
                    }
                }
            }
        });
    }

    private static StreamInfoItem item(final StreamType type,
                                       final ContentAvailability availability) {
        final var item = new StreamInfoItem(0,
                "https://www.youtube.com/watch?v=fixtureVid1", "Fixture", type);
        item.setDuration(-1);
        item.setContentAvailability(availability);
        return item;
    }

    private static void assertColor(final StreamMiniInfoItemHolder holder, final Context context,
                                    final int color) {
        assertThat(((ColorDrawable) holder.itemDurationView.getBackground()).getColor())
                .isEqualTo(context.getColor(color));
    }
}
