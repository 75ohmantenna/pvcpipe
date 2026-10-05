package org.schabi.newpipe.util.image

import android.graphics.Bitmap
import org.schabi.newpipe.ktx.scale

internal object NotificationThumbnail {
    fun scaledHeight(sourceWidth: Int, sourceHeight: Int, targetWidth: Int): Int {
        // Round only after applying the aspect ratio, using long arithmetic to avoid overflow.
        return ((sourceHeight.toLong() * targetWidth + sourceWidth / 2) / sourceWidth)
            .toInt().coerceAtLeast(1)
    }

    fun scale(input: Bitmap, maxWidth: Int): Bitmap {
        val width = maxWidth.coerceIn(1, input.width)
        val height = scaledHeight(input.width, input.height, width)
        val result = input.scale(width, height)
        return if (result === input || !result.isMutable) {
            // Keep a distinct mutable bitmap even when resizing is unnecessary (see #4638).
            val config = result.config?.takeUnless { it == Bitmap.Config.HARDWARE }
                ?: Bitmap.Config.ARGB_8888
            checkNotNull(result.copy(config, true))
        } else {
            result
        }
    }
}
