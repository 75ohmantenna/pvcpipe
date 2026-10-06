package org.schabi.newpipe.local.subscription.workers

import android.os.Parcelable
import androidx.work.Data
import androidx.work.workDataOf
import kotlinx.parcelize.Parcelize

sealed class SubscriptionImportInput : Parcelable {
    @Parcelize
    data class ChannelUrlMode(val serviceId: Int, val url: String) : SubscriptionImportInput()

    @Parcelize
    data class InputStreamMode(val serviceId: Int, val url: String) : SubscriptionImportInput()

    @Parcelize
    data class PreviousExportMode(val url: String) : SubscriptionImportInput()

    fun toData(): Data {
        val (mode, serviceId, url) = when (this) {
            is ChannelUrlMode -> Triple(CHANNEL_URL_MODE, serviceId, url)
            is InputStreamMode -> Triple(INPUT_STREAM_MODE, serviceId, url)
            is PreviousExportMode -> Triple(PREVIOUS_EXPORT_MODE, null, url)
        }
        return workDataOf("mode" to mode, "service_id" to serviceId, "url" to url)
    }

    companion object {

        private const val CHANNEL_URL_MODE = 0
        private const val INPUT_STREAM_MODE = 1
        private const val PREVIOUS_EXPORT_MODE = 2

        fun fromData(data: Data): SubscriptionImportInput {
            val mode = data.getInt("mode", PREVIOUS_EXPORT_MODE)
            when (mode) {
                CHANNEL_URL_MODE -> {
                    val serviceId = data.getInt("service_id", -1)
                    if (serviceId == -1) {
                        throw IllegalArgumentException("No service id provided")
                    }
                    val url = data.getString("url")!!
                    return ChannelUrlMode(serviceId, url)
                }

                INPUT_STREAM_MODE -> {
                    val serviceId = data.getInt("service_id", -1)
                    if (serviceId == -1) {
                        throw IllegalArgumentException("No service id provided")
                    }
                    val url = data.getString("url")!!
                    return InputStreamMode(serviceId, url)
                }

                PREVIOUS_EXPORT_MODE -> {
                    val url = data.getString("url")!!
                    return PreviousExportMode(url)
                }

                else -> throw IllegalArgumentException("Unknown mode: $mode")
            }
        }
    }
}
