package org.schabi.newpipe.util.image

import android.graphics.Bitmap
import androidx.core.graphics.BitmapCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class NotificationThumbnailTest {
    @Test
    fun `rounds the proportional height only after scaling`() {
        assertEquals(113, NotificationThumbnail.scaledHeight(1280, 720, 200))
        assertEquals(356, NotificationThumbnail.scaledHeight(720, 1280, 200))
        assertEquals(1, NotificationThumbnail.scaledHeight(10000, 1, 200))
        assertEquals(1, NotificationThumbnail.scaledHeight(1, 1, 1))
        assertEquals(Int.MAX_VALUE, NotificationThumbnail.scaledHeight(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `keeps a distinct mutable scaled bitmap`() {
        val input = bitmap(1280, 720)
        val scaled = bitmap(200, 113, mutable = true)
        mockStatic(BitmapCompat::class.java).use { scaling ->
            scaling.`when`<Bitmap> { BitmapCompat.createScaledBitmap(input, 200, 113, null, true) }
                .thenReturn(scaled)
            assertSame(scaled, NotificationThumbnail.scale(input, 200))
        }
    }

    @Test
    fun `copies a one pixel source without a second resize`() {
        val input = bitmap(1, 1, mutable = true)
        val copy = bitmap(1, 1, mutable = true)
        `when`(input.copy(Bitmap.Config.ARGB_8888, true)).thenReturn(copy)
        mockStatic(BitmapCompat::class.java).use { scaling ->
            scaling.`when`<Bitmap> { BitmapCompat.createScaledBitmap(input, 1, 1, null, true) }
                .thenReturn(input)
            assertSame(copy, NotificationThumbnail.scale(input, 200))
            verify(input).copy(Bitmap.Config.ARGB_8888, true)
        }
    }

    @Test
    fun `copies an immutable scaled bitmap without changing its dimensions`() {
        val input = bitmap(1280, 720)
        val scaled = bitmap(200, 113)
        val copy = bitmap(200, 113, mutable = true)
        `when`(scaled.copy(Bitmap.Config.ARGB_8888, true)).thenReturn(copy)
        mockStatic(BitmapCompat::class.java).use { scaling ->
            scaling.`when`<Bitmap> { BitmapCompat.createScaledBitmap(input, 200, 113, null, true) }
                .thenReturn(scaled)
            assertSame(copy, NotificationThumbnail.scale(input, 200))
            verify(scaled).copy(Bitmap.Config.ARGB_8888, true)
        }
    }

    @Test
    fun `preserves the software pixel format when copying`() {
        val input = bitmap(1, 1)
        val config = mock(Bitmap.Config::class.java)
        val copy = bitmap(1, 1, mutable = true)
        `when`(input.config).thenReturn(config)
        `when`(input.copy(config, true)).thenReturn(copy)
        mockStatic(BitmapCompat::class.java).use { scaling ->
            scaling.`when`<Bitmap> { BitmapCompat.createScaledBitmap(input, 1, 1, null, true) }
                .thenReturn(input)
            assertSame(copy, NotificationThumbnail.scale(input, 200))
            verify(input).copy(config, true)
        }
    }

    @Test
    fun `uses a software format for a hardware bitmap copy`() {
        val input = bitmap(1, 1)
        val copy = bitmap(1, 1, mutable = true)
        `when`(input.config).thenReturn(Bitmap.Config.HARDWARE)
        `when`(input.copy(Bitmap.Config.ARGB_8888, true)).thenReturn(copy)
        mockStatic(BitmapCompat::class.java).use { scaling ->
            scaling.`when`<Bitmap> { BitmapCompat.createScaledBitmap(input, 1, 1, null, true) }
                .thenReturn(input)
            assertSame(copy, NotificationThumbnail.scale(input, 200))
            verify(input).copy(Bitmap.Config.ARGB_8888, true)
        }
    }

    @Test
    fun `clamps a zero requested width and never upscales small images`() {
        val input = bitmap(2, 3)
        val copy = bitmap(2, 3, mutable = true)
        val narrow = bitmap(1, 2, mutable = true)
        `when`(input.copy(Bitmap.Config.ARGB_8888, true)).thenReturn(copy)
        mockStatic(BitmapCompat::class.java).use { scaling ->
            scaling.`when`<Bitmap> { BitmapCompat.createScaledBitmap(input, 2, 3, null, true) }
                .thenReturn(input)
            scaling.`when`<Bitmap> { BitmapCompat.createScaledBitmap(input, 1, 2, null, true) }
                .thenReturn(narrow)
            assertSame(copy, NotificationThumbnail.scale(input, 200))
            assertSame(narrow, NotificationThumbnail.scale(input, 0))
        }
    }

    private fun bitmap(width: Int, height: Int, mutable: Boolean = false): Bitmap {
        val bitmap = mock(Bitmap::class.java)
        `when`(bitmap.width).thenReturn(width)
        `when`(bitmap.height).thenReturn(height)
        `when`(bitmap.isMutable).thenReturn(mutable)
        return bitmap
    }
}
