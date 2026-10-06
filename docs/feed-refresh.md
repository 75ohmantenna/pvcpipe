# Feed refresh ownership

A `FeedRefresh` is one attempt to update a selected set of subscriptions. Its
interface exposes progress, a result, and cooperative cancellation. Both the
foreground refresh and notification worker use this interface. Presentation,
Android lifecycle, and new-stream notifications remain with the callers.

`FeedLoadManager.createRefresh` reads application preferences and creates the
production adapters. The refresh owns the actual Rx pipeline: subscription
selection, randomized ordering, extraction throttling, three parallel requests,
channel-error materialization, progress, batches of twenty, accumulated errors,
and final database cleanup. The existing extractor and transactional storage
policy remain in the production adapter.

The internal operations seam has four effects: select subscriptions, fetch one
subscription, store a batch, and trim. Tests substitute extraction and scheduling
while exercising the real pipeline. They assert complete results, persisted
batches, progress, and terminal events through the same refresh interface used
by callers. Existing database integration tests retain distinct storage coverage.

Each refresh has independent cancellation, counters, and errors. A channel
extraction failure is result data associated with its subscription; query,
transaction, or cleanup failure terminates the result. Successful completion
means the collected batches and cleanup finished, rather than that every
channel extraction succeeded. Cancellation stops admitting extractions where
cooperative checks permit and lets already collected results reach storage.

The first result subscription accepts execution once. The refresh retains its
complete pipeline through persistence and cleanup; disposing an observer only
detaches presentation. Later observers receive the same success or failure
without re-extracting or rewriting data. Progress remains a live stream, so
callers that need all progress subscribe before observing the result.

Cooperative cancellation prevents further extraction where checks permit. It
does not interrupt an external request or throttle sleep already running. Those
requests can finish, and their results join the final partial batch. The module
publishes one terminal shared feed event independently of which caller started
it or whether observers remain attached. Synchronous subscription-query errors
also enter this terminal result.

The foreground caller cancels and disposes its observers on destruction or
timeout. The notification worker cancels its active refresh when stopped,
including a stop racing refresh creation. Accepted work remains in-process;
this interface does not promise completion after process death, prohibit
concurrent refreshes, or change foreground notification IDs.

Run `make ci` for all checks and APK builds. `FeedRefreshTest` exercises the
refresh interface, including batching, cancellation, materialized failures,
shared observation, retained completion, and independent run state.
`FeedRefreshDatabaseTest` runs production queries, transactions, and cleanup
against isolated in-memory Room, including actual foreground destruction and
whole-batch rollback. Only external extraction is substituted.
