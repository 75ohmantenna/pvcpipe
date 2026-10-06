# Proof-token sessions

`PoTokenProviderImpl` remains the extractor's registered provider. Its blocking web
request delegates to the internal `WebPoTokenProvider`; the other client methods
continue returning `null`.

A proof-token session contains a generator, visitor data, and its streaming token.
The module initializes all three before publishing their coherent replacement.
It owns reuse, expiry, replacement, retry, and broken-WebView suppression. Player
requests can overlap after the session has been selected.

## Internal seams

`WebPoTokenProvider.Environment` substitutes platform support, the YouTube visitor
request, generator creation, and main-thread retirement. `AndroidPoTokenEnvironment`
binds those operations to the application and the existing
`PoTokenGenerator.Factory`. Its default remains `PoTokenWebView`.

A supplied timeout scheduler lets tests advance the existing 30-second generator
and token deadlines without waiting in real time. Diagnostics preserve production
logging without introducing Android dependencies into the session implementation.
Tests use the existing `PoTokenProvider` request interface and scripted external
adapters rather than changing the singleton's state or reflecting into a generator.

`PoTokenWebViewEnvironment` substitutes browser operations, BotGuard HTTP, bundled
HTML, the main queue, and the clock. Its Android adapter retains the configured
WebView and downloader. The generator still owns initialization, JavaScript,
response parsing, request callbacks, cancellation, expiry, and closure.

Unit tests exercise factory/generate/close through a scripted adapter. Device tests
use the same production browser with local HTML and HTTP fixtures, covering real
JavaScript token conversion, initialization failure, and canceled initialization
without network access.

## Preserved behavior

The refactor keeps the existing blocking requests, lazily checked WebView support,
expiry check, initialization lock, generator-identity check on forced recreation,
recursive retry policy, and runtime-wrapper exception handling. Candidate streaming
initialization failure retires that candidate and preserves the published session.
Successful replacement immediately schedules retirement of its predecessor.

Failures involving a predecessor's active requests, repeated retries after a
concurrent replacement, or late WebView callbacks are separate corrections. They
must first be reproduced through the module interface rather than folded into the
structural change.
