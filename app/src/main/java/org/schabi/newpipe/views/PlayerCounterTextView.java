/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import org.schabi.newpipe.R;

/** Keeps the counter's opaque backing close to its text without changing its touch area. */
public class PlayerCounterTextView extends NewPipeTextView {
    private final Drawable counterBackground;
    private final int horizontalInset;
    private final int verticalInset;

    public PlayerCounterTextView(@NonNull final Context context,
                                 @Nullable final AttributeSet attrs) {
        super(context, attrs);
        counterBackground = AppCompatResources.getDrawable(context,
                R.drawable.player_counter_background);
        horizontalInset = Math.round(2 * getResources().getDisplayMetrics().density);
        verticalInset = Math.round(getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDraw(@NonNull final Canvas canvas) {
        final Layout layout = getLayout();
        if (layout != null && layout.getText().length() > 0) {
            float left = layout.getWidth();
            float right = 0;
            for (int line = 0; line < layout.getLineCount(); line++) {
                left = Math.min(left, layout.getLineLeft(line));
                right = Math.max(right, layout.getLineRight(line));
            }
            // Total padding includes TextView's vertical gravity offset. Keep font padding:
            // accents and fallback fonts still need the full line height at large font scales.
            counterBackground.setBounds(
                    (int) Math.floor(getCompoundPaddingLeft() + left) - horizontalInset,
                    getTotalPaddingTop() - verticalInset,
                    (int) Math.ceil(getCompoundPaddingLeft() + right) + horizontalInset,
                    getTotalPaddingTop() + layout.getHeight() + verticalInset);
            // Clip rather than shrink the rounded shape: clipped large text must not acquire
            // new transparent corners underneath its visible glyphs at the view edges.
            final int save = canvas.save();
            canvas.clipRect(getScrollX(), getScrollY(),
                    getScrollX() + getWidth(), getScrollY() + getHeight());
            counterBackground.draw(canvas);
            canvas.restoreToCount(save);
        }
        super.onDraw(canvas);
    }
}
