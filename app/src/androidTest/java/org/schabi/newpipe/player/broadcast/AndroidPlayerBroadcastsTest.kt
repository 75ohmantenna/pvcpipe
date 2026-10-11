/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.broadcast

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.player.notification.NotificationConstants.ACTION_PLAY_NEXT
import org.schabi.newpipe.player.notification.NotificationConstants.ACTION_PLAY_PAUSE
import org.schabi.newpipe.player.notification.NotificationConstants.ACTION_RECREATE_NOTIFICATION

/** Sends real broadcasts to the receiver the player registers with Android. */
class AndroidPlayerBroadcastsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val events = CopyOnWriteArrayList<String>()

    @Volatile
    private var delivered = CountDownLatch(1)

    private val commands = object : PlayerBroadcasts.Commands {
        override fun pause() = record("pause")
        override fun close() = record("close")
        override fun playPause() = record("playPause")
        override fun playPrevious() = record("playPrevious")
        override fun playNext() = record("playNext")
        override fun fastRewind() = record("fastRewind")
        override fun fastForward() = record("fastForward")
        override fun cycleNextRepeatMode() = record("cycleNextRepeatMode")
        override fun toggleShuffleModeEnabled() = record("toggleShuffleModeEnabled")
    }
    private val receiver = AndroidPlayerBroadcasts(context, PlayerBroadcasts(commands)) { intent ->
        events += "observed ${intent.action}"
        delivered.countDown()
    }

    @After
    fun tearDown() {
        receiver.unregister()
    }

    @Test
    fun registeredReceiverRunsTheCommandBeforeTheObserverSeesTheIntent() {
        receiver.register()

        assertTrue(send(ACTION_PLAY_PAUSE))

        assertEquals(listOf("playPause", "observed $ACTION_PLAY_PAUSE"), events.toList())
    }

    @Test
    fun actionWithoutCommandStillReachesTheObserver() {
        receiver.register()

        assertTrue(send(ACTION_RECREATE_NOTIFICATION))

        assertEquals(listOf("observed $ACTION_RECREATE_NOTIFICATION"), events.toList())
    }

    @Test
    fun registeringTwiceDeliversEachBroadcastOnce() {
        receiver.register()
        receiver.register()

        assertTrue(send(ACTION_PLAY_NEXT))
        // A second registration would deliver the same broadcast again right after.
        assertFalse(awaitAnother())

        assertEquals(listOf("playNext", "observed $ACTION_PLAY_NEXT"), events.toList())
    }

    @Test
    fun nothingIsDeliveredAfterUnregister() {
        receiver.register()
        receiver.unregister()
        // Unregistering a receiver that is not registered must not throw.
        receiver.unregister()

        assertFalse(send(ACTION_PLAY_PAUSE, timeoutMillis = 1500))

        assertEquals(emptyList<String>(), events.toList())
    }

    private fun send(action: String, timeoutMillis: Long = 10_000): Boolean {
        delivered = CountDownLatch(1)
        context.sendBroadcast(Intent(action).setPackage(context.packageName))
        return delivered.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun awaitAnother(): Boolean {
        delivered = CountDownLatch(1)
        return delivered.await(1500, TimeUnit.MILLISECONDS)
    }

    private fun record(command: String) {
        events += command
    }
}
