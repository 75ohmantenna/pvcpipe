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

A supplied timeout scheduler lets tests advance the 30-second generator-creation,
streaming-token and player-token deadlines without waiting in real time.
Diagnostics preserve production logging without introducing Android dependencies
into the session implementation.
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

## Ownership and recovery

Replacement publishes only after visitor data and the streaming token are ready.
Every player attempt owns its selected session until success, failure, or timeout.
A replaced generator retires exactly once after its final owner releases it. Short
per-session ownership locks let completed requests return while another request
waits for replacement initialization. External retirement runs outside both locks.

Each request gets at most one recovery attempt. A stale failure may reuse a
replacement published by another request, but failure of that recovery is terminal.
An attempt on a newly initialized session also fails without another recreation.

## Browser initialization and callbacks

Initialization follows explicit phases on the main queue. Duplicate, premature,
and closed-generation callbacks cannot start new transport work or publish a
canceled generator. Nonfatal browser construction, loading, parsing, and JavaScript
evaluation failures terminate initialization; an already constructed generator
closes its browser and other resources.
A fatal JavaScript initialization error still closes an already delivered generator.

Each token request uses a unique callback ID independent of its input identifier.
Canceled, duplicate, or reordered callbacks cannot consume a different request,
including another request for the same identifier. JavaScript receives identifiers
only as UTF-8 bytes and uses local variables; quotes and Unicode cannot alter the
script or callback identity.
