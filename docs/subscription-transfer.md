# Subscription transfer ownership

`SubscriptionTransfer` owns importing and exporting subscriptions. Its execution
interface accepts a subscription source or export destination and reports domain
progress followed by an outcome. The document-picker helper and confirmation
dialog use its scheduling entry points; they do not construct background work.
The workers retain Android foreground notifications, toasts, and WorkManager
lifetime handling.

The implementation owns source selection, document-stream lifetime, the existing
JSON format, channel and tab extraction, extraction progress, subscription
snapshotting, and database batching. Imports finish extracting every selected
channel before storing any subscription. They preserve source order for storage,
use batches of fifty, and retain the existing per-batch transaction semantics.
Earlier successful batches remain committed if a later batch fails. Exports use
one database snapshot and open the destination with truncation enabled.

The internal operations seam supplies seven external effects: opening input and
output documents, reading channel and streaming subscription sources, extracting
channel information, selecting saved subscriptions, and storing a batch. The
Android adapter uses the document resolver, extractor, and `SubscriptionManager`.
Tests use byte streams and controlled extraction while executing the real JSON
codec and transfer policy. Room integration tests inject an isolated database
into the production adapter and retain real snapshot queries, subscription
upserts, and feed writes.

Scheduling preserves existing worker class identities, work names, serialized
input keys and source mode values. Import still requires a connected network;
both directions remain expedited with ordinary-work fallback and the existing
append-or-replace policy. Keeping these identities allows already queued work to
continue using the same input representation after an application update.

Unavailable document streams produce failure outcomes instead of successful empty
imports or unwritten exports. Import source, extraction, progress, and storage
errors produce failure outcomes. A later failed batch leaves earlier committed
batches intact; import progress starts at zero before storage and advances only
after each batch commits. Export success requires the destination to open,
serialize, and close successfully. Cancellation propagates through the codec,
transfer, and worker presentation instead of becoming an ordinary failure
outcome.

An explicit permit limits the whole channel-and-tab extraction to eight requests
in flight, including suspended external requests. Loading progress increments and
reporting are serialized together, preventing overlapping foreground updates or
backwards progress. WorkManager retains ownership of cancellation and lifetime;
the module executes in its caller's coroutine.

`SubscriptionTransferTest` exercises the execution interface with actual JSON
and streams, including malformed input, closure, extraction-before-storage,
batching, and progress. `SubscriptionTransferDatabaseTest` uses in-memory Room
to verify persisted subscriptions and feed rows, existing subscription identity,
export snapshots, and transaction rollback. Run `make ci` for application tests,
style, lint, dependency checks, and APK builds; run the Android database test on
a device for the Room coverage.
