package org.schabi.newpipe.local.subscription.workers

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.util.ExtractorHelper

/** Subscription transfer policy shared by background workers and tests. */
class SubscriptionTransfer internal constructor(
    private val operations: Operations,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    sealed class Progress {
        data class Loading(val current: Int, val total: Int, val name: String?) : Progress()
        data class Importing(val current: Int, val total: Int) : Progress()
        data class Exporting(val total: Int) : Progress()
    }

    sealed class Outcome {
        data class Success(val count: Int) : Outcome()
        data class Failure(val cause: Exception) : Outcome()
    }

    suspend fun `import`(
        input: SubscriptionImportInput,
        progress: suspend (Progress) -> Unit
    ): Outcome {
        val subscriptions = try {
            withContext(ioDispatcher) {
                when (input) {
                    is SubscriptionImportInput.ChannelUrlMode ->
                        operations.channelSource(input.serviceId, input.url)

                    is SubscriptionImportInput.InputStreamMode ->
                        openInput(input.url).use {
                            operations.streamSource(input.serviceId, it, input.url)
                        }

                    is SubscriptionImportInput.PreviousExportMode ->
                        openInput(input.url).use(ImportExportJsonHelper::readFrom)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return Outcome.Failure(e)
        }

        val mutex = Mutex()
        val permits = Semaphore(PARALLEL_EXTRACTIONS)
        var index = 1
        val qty = subscriptions.size
        val channels = try {
            withContext(ioDispatcher.limitedParallelism(PARALLEL_EXTRACTIONS)) {
                subscriptions.map { item ->
                    async {
                        permits.withPermit {
                            val channel = operations.extract(item)
                            mutex.withLock {
                                progress(Progress.Loading(index++, qty, channel.first.name))
                            }
                            channel
                        }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return Outcome.Failure(e)
        }

        try {
            progress(Progress.Importing(0, qty))
            index = 0
            for (chunk in channels.chunked(BUFFER_COUNT_BEFORE_INSERT)) {
                withContext(ioDispatcher) {
                    operations.store(chunk)
                }
                index += chunk.size
                progress(Progress.Importing(index, qty))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return Outcome.Failure(e)
        }
        return Outcome.Success(qty)
    }

    suspend fun export(destination: String, progress: suspend (Progress) -> Unit): Outcome = try {
        val subscriptions = operations.snapshot()
        progress(Progress.Exporting(subscriptions.size))
        withContext(ioDispatcher) {
            val output = operations.openOutput(destination)
                ?: throw IOException("Cannot open subscription export destination: $destination")
            output.use {
                ImportExportJsonHelper.writeTo(subscriptions, it)
            }
        }
        Outcome.Success(subscriptions.size)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Outcome.Failure(e)
    }

    private fun openInput(url: String): InputStream = operations.openInput(url)
        ?: throw IOException("Cannot open subscription import source: $url")

    internal interface Operations {
        fun openInput(url: String): InputStream?
        fun openOutput(url: String): OutputStream?
        fun channelSource(serviceId: Int, url: String): List<SubscriptionItem>
        fun streamSource(serviceId: Int, stream: InputStream, url: String): List<SubscriptionItem>
        suspend fun extract(item: SubscriptionItem): Pair<ChannelInfo, ChannelTabInfo>
        suspend fun snapshot(): List<SubscriptionItem>
        fun store(channels: List<Pair<ChannelInfo, ChannelTabInfo>>)
    }

    internal open class AndroidOperations(
        private val context: Context,
        subscriptionManager: SubscriptionManager? = null
    ) : Operations {
        private val manager by lazy { subscriptionManager ?: SubscriptionManager(context) }

        override fun openInput(url: String): InputStream? = context.contentResolver.openInputStream(url.toUri())

        override fun openOutput(url: String): OutputStream? = context.contentResolver.openOutputStream(url.toUri(), "wt")

        override fun channelSource(serviceId: Int, url: String): List<SubscriptionItem> = NewPipe.getService(serviceId).subscriptionExtractor.fromChannelUrl(url)
            .map { SubscriptionItem(it.serviceId, it.url, it.name) }

        override fun streamSource(serviceId: Int, stream: InputStream, url: String): List<SubscriptionItem> {
            val contentType = MimeTypeMap.getFileExtensionFromUrl(url).ifEmpty { DEFAULT_MIME }
            return NewPipe.getService(serviceId).subscriptionExtractor.fromInputStream(stream, contentType)
                .map { SubscriptionItem(it.serviceId, it.url, it.name) }
        }

        override suspend fun extract(item: SubscriptionItem): Pair<ChannelInfo, ChannelTabInfo> {
            val channel = ExtractorHelper.getChannelInfo(item.serviceId, item.url, true).await()
            val tab = ExtractorHelper.getChannelTab(item.serviceId, channel.tabs[0], true).await()
            return channel to tab
        }

        override suspend fun snapshot(): List<SubscriptionItem> = manager.subscriptions().awaitFirst()
            .map { SubscriptionItem(it.serviceId, it.url ?: "", it.name ?: "") }

        override fun store(channels: List<Pair<ChannelInfo, ChannelTabInfo>>) {
            manager.upsertAll(channels)
        }
    }

    companion object {
        private const val DEFAULT_MIME = "application/octet-stream"
        private const val PARALLEL_EXTRACTIONS = 8
        private const val BUFFER_COUNT_BEFORE_INSERT = 50
        internal const val EXPORT_PATH = "exportPath"
        private const val EXPORT_WORK_NAME = "exportSubscriptions"

        @JvmStatic
        fun create(context: Context): SubscriptionTransfer = SubscriptionTransfer(AndroidOperations(context.applicationContext))

        @JvmStatic
        fun enqueueImport(context: Context, input: SubscriptionImportInput) {
            val request = OneTimeWorkRequestBuilder<SubscriptionImportWorker>()
                .setInputData(input.toData())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(SubscriptionImportWorker.WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        @JvmStatic
        fun enqueueExport(context: Context, uri: Uri) {
            val request = OneTimeWorkRequestBuilder<SubscriptionExportWorker>()
                .setInputData(workDataOf(EXPORT_PATH to uri.toString()))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(EXPORT_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
