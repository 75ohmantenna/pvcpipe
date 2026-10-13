package org.schabi.newpipe.error

import android.util.Log
import org.schabi.newpipe.error.ErrorInfo.Companion.throwableListToStringList
import org.schabi.newpipe.error.ErrorInfo.Companion.throwableToStringList
import org.schabi.newpipe.extractor.pvc.AttachException
import org.schabi.newpipe.pvc.feature.logcat.PvcLogcatDumper

object PvcErrorInfoHelper {

    /**
     * dump [ErrorInfo] stack traces via Log.e.
     *
     * - The traces need to be dumped early for [PvcLogcatDumper] to actually catch
     *   them. In the current NewPipe implementation the traces will be dumped
     *   to logcat only if the [ErrorActivity] is already started. That is way too late and
     *   also user dependent.
     * - If the [PvcLogcatDumper] is enabled we disable the dumping in [ErrorActivity]
     */
    fun logStackTraces(stackTraces: Array<String>): Array<String> {
        logIfPvcLocatDumperIsEnabled(stackTraces)
        return truncate(stackTraces)
    }

    fun logStackTraces(throwable: Throwable): Array<String> {
        val stackTraces: Array<String> = throwableToStringList(throwable)
        logDataIfAttachException(throwable)
        logIfPvcLocatDumperIsEnabled(stackTraces)
        return truncate(stackTraces)
    }

    fun logStackTraces(throwables: List<Throwable>): Array<String> {
        val stackTraces: Array<String> = throwableListToStringList(throwables)
        throwables.forEach { throwable ->
            logDataIfAttachException(throwable)
        }
        logIfPvcLocatDumperIsEnabled(stackTraces)
        return truncate(stackTraces)
    }

    /** What one trace may hold, in characters. */
    const val MAX_TRACE_LENGTH = 32_000

    /** What all traces of one error may hold together, in characters. */
    const val MAX_TOTAL_LENGTH = 64_000

    /** Ends a trace that was cut. */
    const val TRUNCATED = "\n[PVCPipe cut this trace here]"

    /**
     * Bounds the traces of one error. An [ErrorInfo] travels in intents, and Android fails a
     * transaction that is too large, so the traces are cut where the error is built and not
     * only where a report is written.
     *
     * Each trace gets at most [perTrace] characters and all of them together at most [total],
     * the [TRUNCATED] marker of a cut trace included. Traces that no room is left for are
     * dropped. Truncating again changes nothing.
     */
    @JvmStatic
    @JvmOverloads
    fun truncate(
        stackTraces: Array<String>,
        perTrace: Int = MAX_TRACE_LENGTH,
        total: Int = MAX_TOTAL_LENGTH
    ): Array<String> {
        val result = ArrayList<String>(stackTraces.size)
        var remaining = total
        for (trace in stackTraces) {
            if (remaining <= 0) {
                break
            }
            val kept = cut(trace, minOf(perTrace, remaining))
            result.add(kept)
            remaining -= kept.length
        }
        return result.toTypedArray()
    }

    private fun cut(trace: String, limit: Int): String {
        if (trace.length <= limit) {
            return trace
        }
        if (limit <= TRUNCATED.length) {
            return TRUNCATED.take(limit)
        }
        var end = limit - TRUNCATED.length
        // do not leave half of a character that takes two
        if (end > 0 && trace[end - 1].isHighSurrogate()) {
            end--
        }
        return trace.substring(0, end) + TRUNCATED
    }

    /**
     * if the exception is [AttachException] we dump its user given data to the Logger.
     */
    private fun logDataIfAttachException(throwable: Throwable) {
        if (throwable is AttachException) {
            throwable.exceptionData.forEach { data ->
                val stackTrace = throwable.stackTrace
                if (stackTrace.isNotEmpty()) {
                    val element = stackTrace[0]
                    val className = element.className.substringAfterLast(".")
                    Log.e(
                        "AttachExceptionData",
                        "[$className.${element.methodName}() line:${element.lineNumber}] DATA: $data"
                    )
                } else {
                    Log.e(
                        "AttachExceptionData",
                        "DATA: $data"
                    )
                }
            }
        }
    }

    private fun logIfPvcLocatDumperIsEnabled(stackTraces: Array<String>): Array<String> {
        if (!PvcLogcatDumper.isLogcatDumperEnabled()) return stackTraces

        // print stack trace once again for debugging:
        var count = 0 // how many traces do we have
        stackTraces.forEach {
            Log.e("${count++}_${PvcLogcatDumper::class.simpleName}", it)
        }

        return stackTraces
    }
}
