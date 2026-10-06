package org.schabi.newpipe.local.subscription.workers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor.InvalidSourceException

class SubscriptionTransferTest {
    @Test
    fun `previous export fixture imports unicode and round trips with current version metadata`() = runBlocking {
        val fixture = javaClass.classLoader!!.getResourceAsStream("import_export_test.json")!!.use { it.readBytes() }
        val operations = TestOperations().apply { input = TrackedInput(fixture) }
        val first = transfer(operations).`import`(previousExport()) {}

        assertEquals(8, (first as SubscriptionTransfer.Outcome.Success).count)
        assertTrue(operations.input!!.closed)
        assertEquals("中文", operations.extracted[6].name)
        assertEquals("हिंदी", operations.extracted[7].name)
        assertEquals(operations.extracted, operations.stored.single().map { it.first.toItem() })

        operations.subscriptions = operations.stored.flatten().map { it.first.toItem() }
        val output = TrackedOutput()
        operations.output = output
        val exported = transfer(operations).export("destination") {}

        assertEquals(8, (exported as SubscriptionTransfer.Outcome.Success).count)
        assertTrue(output.closed)
        val document = Json.parseToJsonElement(output.toString(Charsets.UTF_8.name())).jsonObject
        assertEquals(BuildConfig.VERSION_NAME, document.getValue("app_version").jsonPrimitive.content)
        assertEquals(BuildConfig.VERSION_CODE, document.getValue("app_version_int").jsonPrimitive.int)
        operations.input = TrackedInput(output.toByteArray())
        operations.extracted.clear()
        val roundTrip = transfer(operations).`import`(previousExport()) {}

        assertEquals(8, (roundTrip as SubscriptionTransfer.Outcome.Success).count)
        assertEquals(operations.subscriptions, operations.extracted)
    }

    @Test
    fun `empty previous export completes without extraction or writes`() = runBlocking {
        val operations = TestOperations().apply { input = TrackedInput(emptyDocument.toByteArray()) }
        val progress = ArrayList<SubscriptionTransfer.Progress>()

        val result = transfer(operations).`import`(previousExport()) { progress.add(it) }

        assertEquals(0, (result as SubscriptionTransfer.Outcome.Success).count)
        assertTrue(operations.input!!.closed)
        assertTrue(operations.extracted.isEmpty())
        assertTrue(operations.stored.isEmpty())
        assertEquals(listOf(SubscriptionTransfer.Progress.Importing(0, 0)), progress)
    }

    @Test
    fun `malformed previous exports fail before extraction and close their input`() = runBlocking {
        for (document in listOf("{}", "", "gibberish")) {
            val operations = TestOperations().apply { input = TrackedInput(document.toByteArray()) }

            val result = transfer(operations).`import`(previousExport()) {}

            assertTrue(result is SubscriptionTransfer.Outcome.Failure)
            assertTrue((result as SubscriptionTransfer.Outcome.Failure).cause is InvalidSourceException)
            assertTrue(operations.input!!.closed)
            assertTrue(operations.extracted.isEmpty())
            assertTrue(operations.stored.isEmpty())
        }
    }

    @Test
    fun `channel source is fully extracted before batches are stored and progress reports writes`() = runBlocking {
        val operations = TestOperations().apply { source = items(103) }
        val progress = ArrayList<SubscriptionTransfer.Progress>()

        val result = transfer(operations).`import`(SubscriptionImportInput.ChannelUrlMode(17, "channel source")) {
            progress.add(it)
            if (it is SubscriptionTransfer.Progress.Importing && it.current > 0) {
                assertEquals(it.current, operations.stored.sumOf { batch -> batch.size })
            }
        }

        assertEquals(103, (result as SubscriptionTransfer.Outcome.Success).count)
        assertEquals(17 to "channel source", operations.channelRequest)
        assertEquals(listOf(50, 50, 3), operations.stored.map { it.size })
        assertEquals(operations.source, operations.stored.flatten().map { it.first.toItem() })
        assertEquals((1..103).toList(), progress.filterIsInstance<SubscriptionTransfer.Progress.Loading>().map { it.current })
        assertTrue(progress.filterIsInstance<SubscriptionTransfer.Progress.Loading>().all { it.total == 103 })
        assertEquals(
            listOf(0, 50, 100, 103),
            progress.filterIsInstance<SubscriptionTransfer.Progress.Importing>().map { it.current }
        )
        assertTrue(operations.effects.take(103).all { it == "extract" })
        assertEquals(listOf("store", "store", "store"), operations.effects.drop(103))
    }

    @Test
    fun `provider source receives its stream and closes it before extraction`() = runBlocking {
        val operations = TestOperations().apply {
            source = items(2)
            input = TrackedInput("provider format".toByteArray())
        }

        val result = transfer(operations).`import`(SubscriptionImportInput.InputStreamMode(23, "provider source")) {}

        assertEquals(2, (result as SubscriptionTransfer.Outcome.Success).count)
        assertEquals(23 to "provider source", operations.streamRequest)
        assertEquals("provider format", operations.sourceContents)
        assertTrue(operations.input!!.closed)
        assertEquals(operations.source, operations.extracted)
    }

    @Test
    fun `source failure closes provider input and prevents channel requests or writes`() = runBlocking {
        val cause = IOException("source unavailable")
        val operations = TestOperations().apply {
            input = TrackedInput("provider format".toByteArray())
            sourceError = cause
        }

        val result = transfer(operations).`import`(SubscriptionImportInput.InputStreamMode(23, "provider source")) {}

        assertSame(cause, (result as SubscriptionTransfer.Outcome.Failure).cause)
        assertTrue(operations.input!!.closed)
        assertTrue(operations.extracted.isEmpty())
        assertTrue(operations.stored.isEmpty())
    }

    @Test
    fun `channel extraction failure prevents every database write`() = runBlocking {
        val cause = IOException("channel unavailable")
        val operations = TestOperations().apply {
            source = items(4)
            extractError = cause
        }
        val progress = ArrayList<SubscriptionTransfer.Progress>()

        val result = transfer(operations).`import`(SubscriptionImportInput.ChannelUrlMode(17, "source")) { progress.add(it) }

        assertSame(cause, (result as SubscriptionTransfer.Outcome.Failure).cause)
        assertTrue(operations.extracted.isNotEmpty())
        assertTrue(operations.stored.isEmpty())
        assertTrue(progress.none { it is SubscriptionTransfer.Progress.Importing })
    }

    @Test
    fun `import storage failure reports a failure outcome without successful progress`() = runBlocking {
        val cause = IOException("transaction unavailable")
        val operations = TestOperations().apply {
            source = items(2)
            storeError = cause
        }
        val progress = ArrayList<SubscriptionTransfer.Progress>()

        val result = transfer(operations).`import`(SubscriptionImportInput.ChannelUrlMode(17, "source")) { progress.add(it) }

        assertSame(cause, (result as SubscriptionTransfer.Outcome.Failure).cause)
        assertEquals(2, operations.extracted.size)
        assertTrue(operations.stored.isEmpty())
        assertEquals(listOf(0), progress.filterIsInstance<SubscriptionTransfer.Progress.Importing>().map { it.current })
    }

    @Test
    fun `export snapshot precedes destination opening and closes written document`() = runBlocking {
        val output = TrackedOutput()
        val operations = TestOperations().apply {
            subscriptions = items(2)
            this.output = output
        }
        val progress = ArrayList<SubscriptionTransfer.Progress>()

        val result = transfer(operations).export("destination") { progress.add(it) }

        assertEquals(2, (result as SubscriptionTransfer.Outcome.Success).count)
        assertEquals(listOf("snapshot", "output"), operations.effects)
        assertEquals("destination", operations.outputDestination)
        assertEquals(listOf(SubscriptionTransfer.Progress.Exporting(2)), progress)
        assertTrue(output.closed)
        assertFalse(output.toByteArray().isEmpty())
        operations.input = TrackedInput(output.toByteArray())
        transfer(operations).`import`(previousExport()) {}
        assertEquals(operations.subscriptions, operations.extracted)
    }

    @Test
    fun `export snapshot failure never opens destination or reports progress`() = runBlocking {
        val cause = IOException("database unavailable")
        val operations = TestOperations().apply { snapshotError = cause }
        val progress = ArrayList<SubscriptionTransfer.Progress>()

        val result = transfer(operations).export("destination") { progress.add(it) }

        assertSame(cause, (result as SubscriptionTransfer.Outcome.Failure).cause)
        assertEquals(listOf("snapshot"), operations.effects)
        assertTrue(progress.isEmpty())
    }

    @Test
    fun `export write failure closes stream and reports original cause`() = runBlocking {
        val cause = IOException("destination full")
        var closed = false
        val operations = TestOperations().apply {
            subscriptions = items(2)
            output = object : OutputStream() {
                override fun write(value: Int) {
                    throw cause
                }

                override fun close() {
                    closed = true
                }
            }
        }

        val result = transfer(operations).export("destination") {}

        assertSame(cause, (result as SubscriptionTransfer.Outcome.Failure).cause)
        assertTrue(closed)
    }

    @Test
    fun `unavailable import documents fail instead of importing an empty selection`() = runBlocking {
        for (input in listOf(previousExport(), SubscriptionImportInput.InputStreamMode(17, "source"))) {
            val operations = TestOperations()
            val progress = ArrayList<SubscriptionTransfer.Progress>()

            val result = transfer(operations).`import`(input) { progress.add(it) }

            assertTrue(result is SubscriptionTransfer.Outcome.Failure)
            assertTrue((result as SubscriptionTransfer.Outcome.Failure).cause is IOException)
            assertTrue(operations.extracted.isEmpty())
            assertTrue(operations.stored.isEmpty())
            assertTrue(progress.isEmpty())
        }
    }

    @Test
    fun `unavailable export destination fails instead of reporting an unwritten export`() = runBlocking {
        val operations = TestOperations().apply { subscriptions = items(2) }

        val result = transfer(operations).export("destination") {}

        assertTrue(result is SubscriptionTransfer.Outcome.Failure)
        assertTrue((result as SubscriptionTransfer.Outcome.Failure).cause is IOException)
        assertEquals(listOf("snapshot", "output"), operations.effects)
    }

    @Test
    fun `cancelled source propagates cancellation without extraction or writes`() = runBlocking {
        val cause = CancellationException("source cancelled")
        val operations = TestOperations().apply { sourceError = cause }

        assertCancellation(cause) {
            transfer(operations).`import`(SubscriptionImportInput.ChannelUrlMode(17, "source")) {}
        }

        assertTrue(operations.extracted.isEmpty())
        assertTrue(operations.stored.isEmpty())
    }

    @Test
    fun `cancelled previous export read closes its input and propagates cancellation`() = runBlocking {
        val cause = CancellationException("document read cancelled")
        val input = object : TrackedInput(byteArrayOf()) {
            override fun read(): Int {
                throw cause
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                throw cause
            }
        }
        val operations = TestOperations().apply { this.input = input }
        val progress = ArrayList<SubscriptionTransfer.Progress>()

        assertCancellation(cause) {
            transfer(operations).`import`(previousExport()) { progress.add(it) }
        }

        assertTrue(input.closed)
        assertTrue(operations.extracted.isEmpty())
        assertTrue(operations.stored.isEmpty())
        assertTrue(operations.effects.isEmpty())
        assertTrue(progress.isEmpty())
    }

    @Test
    fun `cancelled extraction propagates cancellation without writes`() = runBlocking {
        val cause = CancellationException("extraction cancelled")
        val operations = TestOperations().apply {
            source = items(2)
            extractError = cause
        }

        assertCancellation(cause) {
            transfer(operations).`import`(SubscriptionImportInput.ChannelUrlMode(17, "source")) {}
        }

        assertTrue(operations.stored.isEmpty())
    }

    @Test
    fun `cancelled import storage propagates cancellation`() = runBlocking {
        val cause = CancellationException("storage cancelled")
        val operations = TestOperations().apply {
            source = items(2)
            storeError = cause
        }

        assertCancellation(cause) {
            transfer(operations).`import`(SubscriptionImportInput.ChannelUrlMode(17, "source")) {}
        }

        assertTrue(operations.stored.isEmpty())
    }

    @Test
    fun `cancelled export snapshot propagates cancellation without opening destination`() = runBlocking {
        val cause = CancellationException("snapshot cancelled")
        val operations = TestOperations().apply { snapshotError = cause }

        assertCancellation(cause) {
            transfer(operations).export("destination") {}
        }

        assertEquals(listOf("snapshot"), operations.effects)
    }

    @Test
    fun `cancelled export write closes its stream and propagates cancellation`() = runBlocking {
        val cause = CancellationException("write cancelled")
        var closed = false
        val operations = TestOperations().apply {
            subscriptions = items(2)
            output = object : OutputStream() {
                override fun write(value: Int) {
                    throw cause
                }

                override fun close() {
                    closed = true
                }
            }
        }

        assertCancellation(cause) {
            transfer(operations).export("destination") {}
        }

        assertTrue(closed)
    }

    @Test
    fun `cancelled import loading presentation propagates cancellation without writes`() = runBlocking {
        val cause = CancellationException("loading presentation cancelled")
        val operations = TestOperations().apply { source = items(2) }

        assertCancellation(cause) {
            transfer(operations).`import`(SubscriptionImportInput.ChannelUrlMode(17, "source")) { throw cause }
        }

        assertTrue(operations.stored.isEmpty())
    }

    @Test
    fun `cancelled export presentation propagates cancellation without opening destination`() = runBlocking {
        val cause = CancellationException("export presentation cancelled")
        val operations = TestOperations().apply { subscriptions = items(2) }

        assertCancellation(cause) {
            transfer(operations).export("destination") { throw cause }
        }

        assertEquals(listOf("snapshot"), operations.effects)
    }

    @Test
    fun `suspended extraction requests never exceed eight in flight`() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val release = CompletableDeferred<Unit>()
            val firstWave = CompletableDeferred<Unit>()
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val started = AtomicInteger()
            val operations = TestOperations().apply {
                source = items(24)
                beforeExtract = {
                    val concurrent = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, concurrent) }
                    if (started.incrementAndGet() == 8) firstWave.complete(Unit)
                    try {
                        release.await()
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
            val execution = async(dispatcher) {
                SubscriptionTransfer(operations, dispatcher).`import`(SubscriptionImportInput.ChannelUrlMode(17, "source")) {}
            }

            try {
                withTimeout(10_000) { firstWave.await() }
                // A queued sentinel lets the extraction dispatcher drain runnable requests.
                withContext(dispatcher) {}
                assertEquals("suspended external requests must retain their slots", 8, maximum.get())
                assertTrue(operations.stored.isEmpty())
                release.complete(Unit)
                val outcome = withTimeout(10_000) { execution.await() }
                assertEquals(24, (outcome as SubscriptionTransfer.Outcome.Success).count)
                assertEquals(24, started.get())
                assertEquals(8, maximum.get())
                assertEquals(0, active.get())
                assertEquals(24, operations.stored.single().size)
            } finally {
                release.complete(Unit)
                execution.cancelAndJoin()
            }
        }
    }

    @Test
    fun `suspended loading presentation is serialized and progress never regresses`() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val firstProgress = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val delivered = ArrayList<Int>()
            val operations = TestOperations().apply { source = items(24) }
            val execution = async(dispatcher) {
                SubscriptionTransfer(operations, dispatcher).`import`(SubscriptionImportInput.ChannelUrlMode(17, "source")) {
                    if (it is SubscriptionTransfer.Progress.Loading) {
                        val concurrent = active.incrementAndGet()
                        maximum.updateAndGet { previous -> maxOf(previous, concurrent) }
                        if (it.current == 1) firstProgress.complete(Unit)
                        try {
                            release.await()
                            delivered.add(it.current)
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }

            try {
                withTimeout(10_000) { firstProgress.await() }
                withContext(dispatcher) {}
                assertEquals("foreground updates must not overlap", 1, maximum.get())
                release.complete(Unit)
                val outcome = withTimeout(10_000) { execution.await() }
                assertEquals(24, (outcome as SubscriptionTransfer.Outcome.Success).count)
                assertEquals((1..24).toList(), delivered)
                assertEquals(1, maximum.get())
                assertEquals(0, active.get())
            } finally {
                release.complete(Unit)
                execution.cancelAndJoin()
            }
        }
    }

    @Test
    fun `cancelling a running import releases suspended requests without admitting waiting requests or writes`() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val release = CompletableDeferred<Unit>()
            val firstWave = CompletableDeferred<Unit>()
            val active = AtomicInteger()
            val started = AtomicInteger()
            val operations = TestOperations().apply {
                source = items(24)
                beforeExtract = {
                    active.incrementAndGet()
                    if (started.incrementAndGet() == 8) firstWave.complete(Unit)
                    try {
                        release.await()
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
            val execution = async(dispatcher) {
                SubscriptionTransfer(operations, dispatcher).`import`(SubscriptionImportInput.ChannelUrlMode(17, "source")) {}
            }
            try {
                withTimeout(10_000) { firstWave.await() }
                withContext(dispatcher) {}
                assertEquals(8, active.get())
                withTimeout(10_000) { execution.cancelAndJoin() }
                assertTrue(execution.isCancelled)
                assertEquals(0, active.get())
                assertEquals(8, started.get())
                assertTrue(operations.stored.isEmpty())
            } finally {
                release.complete(Unit)
                execution.cancelAndJoin()
            }
        }
    }

    private suspend fun assertCancellation(cause: CancellationException, action: suspend () -> Unit) {
        try {
            action()
            fail("Cancellation must propagate rather than become a transfer outcome")
        } catch (error: CancellationException) {
            assertTrue("Original cancellation must be retained", generateSequence<Throwable>(error) { it.cause }.any { it === cause })
        }
    }

    private fun transfer(operations: TestOperations) = SubscriptionTransfer(operations, ImmediateDispatcher)

    private fun previousExport() = SubscriptionImportInput.PreviousExportMode("source")

    private fun items(count: Int) = (1..count).map { SubscriptionItem(17, "https://example.test/$it", "channel $it") }

    private fun ChannelInfo.toItem() = SubscriptionItem(serviceId, url, name)

    private open class TrackedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class TrackedOutput : ByteArrayOutputStream() {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class TestOperations : SubscriptionTransfer.Operations {
        var input: TrackedInput? = null
        var output: OutputStream? = null
        var source = emptyList<SubscriptionItem>()
        var subscriptions = emptyList<SubscriptionItem>()
        var sourceError: Exception? = null
        var extractError: Exception? = null
        var beforeExtract: (suspend (SubscriptionItem) -> Unit)? = null
        var storeError: Exception? = null
        var snapshotError: Exception? = null
        var channelRequest: Pair<Int, String>? = null
        var streamRequest: Pair<Int, String>? = null
        var sourceContents: String? = null
        var outputDestination: String? = null
        val extracted = ArrayList<SubscriptionItem>()
        val stored = ArrayList<List<Pair<ChannelInfo, ChannelTabInfo>>>()
        val effects = ArrayList<String>()

        override fun openInput(url: String): InputStream? = input

        override fun openOutput(url: String): OutputStream? {
            effects.add("output")
            outputDestination = url
            return output
        }

        override fun channelSource(serviceId: Int, url: String): List<SubscriptionItem> {
            channelRequest = serviceId to url
            sourceError?.let { throw it }
            return source
        }

        override fun streamSource(serviceId: Int, stream: InputStream, url: String): List<SubscriptionItem> {
            streamRequest = serviceId to url
            sourceContents = stream.readBytes().toString(Charsets.UTF_8)
            sourceError?.let { throw it }
            return source
        }

        override suspend fun extract(item: SubscriptionItem): Pair<ChannelInfo, ChannelTabInfo> {
            input?.let { assertTrue("source must close before extraction", it.closed) }
            effects.add("extract")
            extracted.add(item)
            extractError?.let { throw it }
            beforeExtract?.invoke(item)
            val channel = ChannelInfo(item.serviceId, item.url, item.url, item.url, item.name)
            val tab = ChannelTabInfo(
                item.serviceId,
                ListLinkHandler(item.url, item.url, item.url, listOf(ChannelTabs.VIDEOS), emptyList())
            )
            return channel to tab
        }

        override suspend fun snapshot(): List<SubscriptionItem> {
            effects.add("snapshot")
            snapshotError?.let { throw it }
            return subscriptions
        }

        override fun store(channels: List<Pair<ChannelInfo, ChannelTabInfo>>) {
            assertEquals("all channels must extract before storage", source.size.takeIf { it > 0 } ?: extracted.size, extracted.size)
            effects.add("store")
            storeError?.let { throw it }
            stored.add(channels.toList())
        }
    }

    private object ImmediateDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }
    }

    companion object {
        private const val emptyDocument = "{\"app_version\":\"0.11.6\",\"app_version_int\":47,\"subscriptions\":[]}"
    }
}
