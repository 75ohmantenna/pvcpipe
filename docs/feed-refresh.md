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

This structural phase deliberately keeps the existing cold result behavior.
Repeated result observation repeats work, disposing the result observer can lose
a partial batch, and fatal shared feed events still originate in the foreground
caller. The foreground caller therefore retains its existing no-disposal drain
workaround. Separate failure correction will make execution ownership consistent
for both callers.

Run `make ci` for all checks and APK builds. `FeedRefreshTest` exercises the
refresh interface, including batching, cancellation, materialized failures,
and the inherited disposal and repeated-observation defects.
