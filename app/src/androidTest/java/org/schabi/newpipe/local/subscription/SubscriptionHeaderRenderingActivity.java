/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.local.subscription;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Test-APK host for real production header resources, restricted to emulators. */
public class SubscriptionHeaderRenderingActivity extends Activity {
    private static final String TARGET = "org.seventyfiveohmantenna.pvcpipe.debug";

    @Override
    public void onCreate(final Bundle state) {
        super.onCreate(state);
        if (!"ranchu".equals(Build.HARDWARE) && !"goldfish".equals(Build.HARDWARE)) {
            finish();
            return;
        }
        try {
            final Context target = createPackageContext(TARGET,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            final Configuration config = new Configuration(
                    target.getResources().getConfiguration());
            config.setLocale(Locale.forLanguageTag(getIntent().getStringExtra("locale") == null
                    ? "en-US" : getIntent().getStringExtra("locale")));
            config.fontScale = getIntent().getFloatExtra("font_scale", 1.0f);
            final Context localized = target.createConfigurationContext(config);
            final int theme = target.getResources().getIdentifier("LightTheme", "style", TARGET);
            final Context ui = new ContextThemeWrapper(localized, theme);
            final ScrollView scroll = new ScrollView(this);
            scroll.setBackgroundColor(0xffffffff);
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            });
            final LinearLayout rows = new LinearLayout(ui);
            rows.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(rows);
            final int requestedWidth = getIntent().getIntExtra("width_dp", -1);
            final int width = requestedWidth < 0 ? LinearLayout.LayoutParams.MATCH_PARENT
                    : Math.round(requestedWidth * ui.getResources().getDisplayMetrics().density);
            // The test APK is a separate process. Load both production types through the target
            // APK's loader, so this host does not contain a second header implementation.
            final ClassLoader loader = target.getClassLoader();
            final Class<?> header = loader.loadClass(
                    "org.schabi.newpipe.local.subscription.item.Header");
            final Class<?> binding = loader.loadClass(
                    "org.schabi.newpipe.databinding.SubscriptionHeaderBinding");
            final int layout = target.getResources().getIdentifier(
                    "subscription_header", "layout", TARGET);
            final int title = target.getResources().getIdentifier(
                    "tab_subscriptions", "string", TARGET);
            addHeader(rows, ui, layout, width, header.getConstructor(String.class)
                    .newInstance(ui.getString(title)), header, binding);
            final int[] selected = getIntent().getIntArrayExtra("service_ids");
            final int[] ids = selected == null
                    ? new int[]{0, 2, 3, 4, 99, Integer.MAX_VALUE} : selected;
            for (final int id : ids) {
                addHeader(rows, ui, layout, width,
                        header.getConstructor(int.class).newInstance(id), header, binding);
                final TextView channel = new TextView(ui);
                channel.setText("Fixture channel for ID " + id);
                channel.setPadding(32, 8, 16, 8);
                rows.addView(channel);
            }
            setContentView(scroll);
            scroll.requestApplyInsets();
        } catch (final ReflectiveOperationException | NameNotFoundException e) {
            throw new IllegalStateException("Cannot load production subscription headers", e);
        }
    }

    private void addHeader(final LinearLayout rows, final Context ui, final int layout,
                           final int width, final Object header, final Class<?> type,
                           final Class<?> binding) throws ReflectiveOperationException {
        final View view = LayoutInflater.from(ui).inflate(layout, rows, false);
        final Object bound = binding.getMethod("bind", View.class).invoke(null, view);
        type.getMethod("bind", binding, int.class).invoke(header, bound, 0);
        rows.addView(view, new LinearLayout.LayoutParams(width,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }
}
