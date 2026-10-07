package org.schabi.newpipe.player.gesture

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.widget.ProgressBar
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.PlayerBinding
import org.schabi.newpipe.ktx.AnimationType
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.player.audio.PlaybackAudio
import org.schabi.newpipe.player.ui.MainPlayerUi

/**
 * GestureListenerHelper for the player.
 *
 * This class focuses on setting internal volume during scrolling for specific event.
 */
class PvcMainPlayerGestureListenerHelper {

    private fun isInternalVolumeEnabled(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.pvc_settings_internal_volume_key), false)
    }

    fun isInternalScrollVolumeEvent(
        binding: PlayerBinding,
        context: Context,
        initialEvent: MotionEvent
    ): Boolean {
        if (getTheDisplayPortion(binding, initialEvent) == DisplayPortion.RIGHT) {
            if (isInternalVolumeEnabled(context)) {
                return true
            }
        }
        return false
    }

    fun onInternalScrollVolumeEvent(
        binding: PlayerBinding,
        playerUi: MainPlayerUi,
        distanceY: Float
    ) {
        val bar: ProgressBar = binding.pvcPlayerExt.internalVolProgressBar
        val player = playerUi.player
        val playbackAudio: PlaybackAudio = player.playbackAudio

        // If we just started sliding, change the progress bar to match the system volume
        if (!binding.pvcPlayerExt.internalVolRelativeLayout.isVisible) {
            val volumePercent: Float = playbackAudio.internalVolume
            bar.progress = (volumePercent * bar.max).toInt()
        }

        // Update progress bar
        bar.incrementProgressBy((distanceY / REDUCE_DISTANCE_BY_FACTOR).toInt())

        // Update volume
        val currentVolumePercent: Float = bar.progress / bar.max.toFloat()
        playbackAudio.internalVolume = currentVolumePercent
        if (DEBUG) {
            Log.d(TAG, "onScroll().internalVolume, currentVolumePercent = $currentVolumePercent")
        }

        // Update player center image
        binding.pvcPlayerExt.internalVolImageView.setImageDrawable(
            AppCompatResources.getDrawable(
                player.context,
                when {
                    currentVolumePercent <= 0 -> R.drawable.ic_volume_off
                    currentVolumePercent < 0.25 -> R.drawable.ic_volume_mute
                    currentVolumePercent < 0.75 -> R.drawable.ic_volume_down
                    else -> R.drawable.ic_volume_up
                }
            )
        )

        // Make sure the correct layout is visible
        if (!binding.pvcPlayerExt.internalVolRelativeLayout.isVisible) {
            binding.pvcPlayerExt.internalVolRelativeLayout.animate(true, 200, AnimationType.SCALE_AND_ALPHA)
        }
        binding.brightnessRelativeLayout.isVisible = false
        binding.volumeRelativeLayout.isVisible = false
    }

    fun onScrollEnd(binding: PlayerBinding, event: MotionEvent) {
        if (binding.pvcPlayerExt.internalVolRelativeLayout.isVisible) {
            binding.pvcPlayerExt.internalVolRelativeLayout.animate(false, 200, AnimationType.SCALE_AND_ALPHA, 200)
        }
    }

    // divide the screen in three portions. Two smaller (left and right) and a big mid portion
    // will be detected. Until now only the right portion is used.
    private fun getTheDisplayPortion(binding: PlayerBinding, e: MotionEvent): DisplayPortion {
        // divide into parts (bigger number -> smaller left/right parts
        val noOfDisplayParts = 7
        val lastRightPortionBegin = noOfDisplayParts - 1 // 1 -> one portion
        return when {
            e.x < binding.root.width / noOfDisplayParts -> DisplayPortion.LEFT
            e.x > (binding.root.width / noOfDisplayParts) * lastRightPortionBegin -> DisplayPortion.RIGHT
            else -> DisplayPortion.MIDDLE
        }
    }

    companion object {
        private val TAG = PvcMainPlayerGestureListenerHelper::class.java.simpleName
        private val DEBUG = MainActivity.DEBUG

        // the swiping is to fast (one my phone).
        // TODO Verify it does no need tuning/better handling on other phones
        private const val REDUCE_DISTANCE_BY_FACTOR = 4
    }
}
