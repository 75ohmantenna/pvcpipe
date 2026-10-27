package org.schabi.newpipe.local.feed.service

/** Report metadata for a refresh that may contain failures from several services. */
internal class FeedErrorContext(errors: List<Throwable>) {
    // A mixed refresh must not attribute every error to the first failed service.
    val serviceId: Int? = errors.map { (it as? FeedRefresh.RequestException)?.serviceId }
        .distinct().singleOrNull()

    // Keep per-request service IDs in the description even for a mixed-service report.
    // Bound metadata as well as the traces so a large failed refresh fits an Intent.
    val request: String = errors.filterIsInstance<FeedRefresh.RequestException>()
        .mapNotNull { it.message }.distinct().joinToString("\n", limit = 10)
        .ifEmpty { "Loading feed" }.let { if (it.length > 4000) it.take(3999) + "…" else it }
}
