# Dependency audit — 2026-10-22

## Scope and compatibility

Audited both included Gradle builds, production/debug/test sources, XML widgets,
generated-code inputs, service registration and shrinker rules. Existing user
changes in `.gitignore`, `docs/building.md` and `.omp/` were left alone.

- Android minSdk **26**, targetSdk **37**, compileSdk **37.0**; unchanged.
- Gradle **9.8.2**, daemon JDK **25**, app/extractor Java toolchain **21**,
  AGP **9.4.1**, Kotlin **2.4.21**, KSP **2.3.12**; unchanged. Effective app Java
  source/target and debug/release Kotlin JVM targets are all **21**.
- Core-library desugaring remains `desugar_jdk_libs_nio:2.1.5`. It supplies selected
  Java library APIs, not Android framework APIs. Its presence does not make newer
  `android.icu` methods available on older operating systems.
- Only debug/release build types, no product flavors. Release uses R8/resource
  shrinking. The app substitutes the bundled extractor through the composite build.

## Changes and decision table

“Platform” means Android framework; AndroidX is a separately distributed first-party
library, **not built into Android**. Java/Kotlin standard-library substitutions are
identified separately. Remaining external libraries are not platform APIs.

| Dependency / purpose | Proposed replacement | Compatibility / behavior | Decision |
| --- | --- | --- | --- |
| Rhino `rhino-engine`, JSR-223 bridge | None | No `javax.script`, engine discovery or reflection callers. `utils/JavaScript` uses Rhino Context/Function directly. Keep Rhino core and its reflective/runtime rules. | Remove as unused; remove engine catalog alias and engine/JSR-223 shrinker rules |
| JSR-305 in `timeago-generator` | None | Generator imports only JDK/NanoJSON APIs; extractor and incorporated timeago parser still use annotations. | Remove unused generator declaration only |
| AndroidX LocalBroadcastManager direct declaration | Existing Gradle dependency constraint | No app/variant/test/resource callers. ExoPlayer UI → legacy-support-core-utils still needs it; retain the 1.1.0 pin. No exclusion. | Remove direct runtime ownership; retain transitively |
| Okio ByteString used just to encode search-filter bytes | Java `Base64.getEncoder().encodeToString` | Native Android API 26, also Java SE 8+. Same padded basic alphabet, no wrapping, followed by existing URL encoding. No locale, timezone or threading state. | Replace encoding; retain permissive Okio decoding and generated Wire runtime |
| Guava Stopwatch for thumbnail debug diagnostics | Java `System.nanoTime()` subtraction | Monotonic elapsed time, not wall clock; no mutable timer object. Timing stays gated by `Log.isLoggable`. Logs now use integer milliseconds instead of Guava's adaptive units. No thumbnail/cancellation changes. | Replace two local timer uses; retain Guava elsewhere |
| PrettyTime relative upload dates | Android DateUtils or ICU RelativeDateTimeFormatter | Both available at minSdk, but not behavior-equivalent; see below. | Retain |
| ProcessPhoenix process restart | Android activities/process APIs | Backup restore and history reset need a fresh process; App skips database/app initialization in the rebirth process. Replacing this requires reproducing manifest/process coordination, not just reopening an Activity. | Retain |
| NanoJSON (app/extractor/generator) | Android `org.json` | Standalone extractor/generator must run on JVM; different model/error semantics and broad callers. No Java SE JSON parser. | Retain |
| Jsoup | XML/HTML platform parsing | Real HTML selectors, tolerant DOM, entity decoding and TTML processing; XML parsers/Html spans are not equivalent. | Retain |
| OkHttp + URLConnection cookie adapter | HttpURLConnection | Shared interceptors, challenge handling, cookie behavior and Coil integration would require substantial custom implementation. | Retain |
| ExoPlayer core/DASH/HLS/database/datasource/media-session/UI | Android MediaPlayer/MediaCodec | Would lose adaptive streaming, datasource and player/session behavior. | Retain; Media3 is a separate first-party migration, not a built-in substitute |
| FCast sender SDK | Custom sockets/discovery | Authenticated v4 plus v2/v3 protocols, native UniFFI/JNA bridge and receiver state handling. | Retain |
| HlsDownloader + slimhls-converter | Platform media APIs | Manifest/segment processing and bundled FFmpeg remuxing are substantial functionality; native extraction packaging is intentional. | Retain |
| Coil core + network-OkHttp | ImageView/BitmapFactory | Would lose cache, lifecycle/cancellation and shared HTTP loader. | Retain |
| Groupie + viewbinding | RecyclerView adapters | Not a small utility substitution; many settings/feed/group screens. | Retain |
| Markwon core + linkify | Android Html/Linkify | `TextLinkifier.fromMarkdown` renders Markdown and links; HTML/plain-text branches are separate. | Retain |
| ACRA + AutoService registration | UncaughtExceptionHandler | Crash capture/report pipeline and generated sender discovery are not equivalent. | Retain |
| RxJava/RxAndroid, coroutines-rx3 | Executors / standard collections | Reactive DAO signatures, scheduler/cancellation and conversion semantics; broad rewrite. | Retain |
| kotlinx-serialization-json + plugin | Platform JSON | Serialized subscription format and generated serializers; broad behavioral migration. | Retain |
| LogcatToolkit | Runtime.exec / custom UI | Capture windows, retention, viewer, export and FileProvider integration. | Retain |
| challengeFloatsAway | WebView alone | Challenge orchestration/service/interceptor result handling, not just rendering. | Retain |
| Protobuf-javalite + Wire runtime | Manual binary codecs | Generated protocol structures, unknown fields and wire compatibility. | Retain |
| Rhino core | Platform JavaScript engine | No equivalent Android/Java21 standard runtime for extractor scripts; direct Context/Function and tokenizer callers. | Retain |
| JSR-305 in extractor | Standard annotations | No equivalent Java SE nullness annotations; public model/parser annotations remain. | Retain |

The Base64 decoder deliberately remains Okio: it accepts both standard/URL-safe
alphabets and whitespace. A Java basic decoder would reject previously accepted
input; a MIME decoder would ignore a different set of invalid characters. Keeping
the existing decoder avoids a compatibility wrapper for no dependency removal.
Okio remains required by Wire and OkHttp. Guava remains required by ExoPlayer and
ACRA transitives, and `YoutubeHttpDataSource` directly uses its predicates, filtered
map views and header constants. Neither utility change claims APK size savings.

## Remaining direct dependency inventory

These are retained first-party **AndroidX libraries**, not platform implementations:

| Artifacts | Representative real use / reason |
| --- | --- |
| AppCompat, Core KTX, Fragment KTX, Preference | MainActivity/BaseFragment/settings, per-app locale handling and compatibility APIs |
| CardView, ConstraintLayout | XML layouts (`feed_group_card_item`, `activity_local_player`, description layouts) and generated bindings |
| Lifecycle LiveData KTX + ViewModel KTX | DetailPagerViewModel, RouterActivity, lifecycleScope in error UI |
| DocumentFile | DownloadPreparation and storage-access-framework documents |
| RecyclerView, SwipeRefreshLayout, ViewPager2 | Feed lists/refresh, reordering and About/Licenses pager |
| Media | PlayerService media browser/session and audio-focus compatibility |
| Room runtime + rxjava3 | AppDatabase, reactive DAOs and generated implementations |
| Work runtime + rxjava3 | Notification/subscription workers and cancellation/retries |
| WebKit | PvcCfChallengeConfig WebView feature/cookie compatibility |
| Material Components (Google first-party, separate library) | Dialogs, navigation, snackbars, bottom sheets, XML widgets/themes |

Collection/Collection KTX, Emoji2/views-helper and SavedState/KTX remain version
constraints, not independently added runtime libraries. Kotlin stdlib and Android
viewbinding/parcelize runtimes are toolchain-generated dependencies. The bundled
extractor itself supplies service parsing, not a removable utility.

### Build and test dependencies (not production-runtime candidates)

| Scope | Dependencies and role |
| --- | --- |
| Build plugins | AGP, KSP, Parcelize, Kotlin serialization; extractor Protobuf plugin 0.10.0, protoc 4.36.2 and Wire plugin 5.5.1 generate different schema families |
| Processors / compile-only | Room compiler; AutoService annotations + KSP processor generate crash-sender service registration |
| Quality tools | Checkstyle 14.3.0 (JDK25 launcher), ktlint 1.8.0; buildSrc Kotlin DSL and Kotlin test-JUnit |
| App JVM tests | JUnit4, Mockito |
| App Android tests | AndroidX JUnit/runner/Room testing, AssertJ |
| Extractor tests | JUnit5 BOM/API/params/engine/platform launcher; OkHttp + cookie adapter for test downloader; Gson for recorded request/response fixtures |
| Generator | NanoJSON; timeago-parser sources are incorporated into extractor, not a separately included Gradle project |
| Debug runtime only | Stetho + OkHttp inspector (`DebugApp`); LeakCanary watcher/core and Plumber auto-installers. Manifest/provider initialization and reflective debug settings count as use. |

About licenses remain: they cover both variants, incorporated source (including
Apache Commons Text FuzzyScore and SearchPreference), and transitive libraries.
Rhino's existing notice still covers retained core; no notice became wholly obsolete.

## PrettyTime assessment

`Localization.relativeTime` calls **formatUnrounded(OffsetDateTime)**. Initialization
uses the UI locale (`getAppLocale`), not the preferred content language; startup and
MainActivity resume refresh it. Decades are explicitly disabled. Parsed service dates
use this path; unparsed dates retain the service's textual fallback. TimeAgoParser
also adjusts parsed years by a day to avoid displaying twelve months.

- [DateUtils](https://developer.android.com/reference/android/text/format/DateUtils)
  relative spans choose seconds/minutes/hours/days and eventually a calendar date,
  rather than preserving relative months/years. Its usual overloads lack an explicit
  Locale argument. Switching would change old upload labels and locale handling.
- [ICU RelativeDateTimeFormatter](https://developer.android.com/reference/android/icu/text/RelativeDateTimeFormatter)
  supports explicit Locale, pluralization and thread-safe immutable formatting from
  API **24**, including `format(quantity, Direction, RelativeUnit)`. However callers
  must choose units/cutoffs. `formatNumeric` is API **28**, not available natively on
  API26/27; desugaring does not backport it. ICU wording/CLDR data also varies with OS.
- PrettyTime's unrounded thresholds, just-now/future wording, average month/year
  lengths and long-range units are not simply supplied by ICU. Reimplementing these
  to remove a working locale library adds maintenance and requires a product decision
  on changed output. `java.time` alone provides no localized relative-time prose.

Retain PrettyTime and its ResourceBundle constructor shrinker rule. No time/date
behavior changes, so this migration adds no new time-boundary/locale/time-zone tests.
A future intentional ICU migration needs a fixed-clock matrix covering just-now,
minute/hour/day/week/month/year boundaries in both directions, leap years/DST,
multiple UI locales, and real API26/27 ICU behavior. Existing date adjustments must
be assessed at that point, not removed as incidental cleanup now.

## Verification record

Before edits, `make ci` passed: buildSrc tests, Checkstyle, ktlint, dependency order,
extractor Checkstyle/offline tests, app JVM tests, lintDebug, debug and release APKs.
Many baseline tasks reused cache/up-to-date outputs. Existing Gradle deprecation
warnings report incompatibility with Gradle 10; no toolchain upgrade was attempted.

Java Base64 is [native from API26](https://developer.android.com/reference/java/util/Base64),
so this use does not rely on desugaring. `System.nanoTime` is Java/Android standard
monotonic elapsed-time measurement; it does not consult locale or wall-clock time.

Post-change verification:

- `make ci` passed all five stages, including app JVM and extractor offline tests,
  lintDebug, debug assembly and release R8/assembly. Compilation also reported
  deprecated/unchecked API notes, the ACRA provider manifest `tools:replace` warning,
  and the JVM class-sharing warning; none failed the gate.
- Existing `YoutubeProtoBufferSearchParameterAccessorTest`: **8 passed**, **1 already
  disabled**. No live service/network tests were run.
- A throwaway JVM smoke executed `JavaScript.compileOrThrow` and `run` successfully
  while asserting the JSR-223 engine class was absent. Base64 matched Okio for seeded
  byte arrays of every length **0–1024**; actual search filters encoded/decoded with
  their date/type/sort/feature values preserved.
- Generator `classes` compiled. Its resolved runtime graph contains only NanoJSON;
  `rhino-engine` has no matching dependency in either app runtime variant.
  The release graph retains Rhino core, Okio/Wire, Guava and extractor JSR-305;
  LocalBroadcastManager resolves transitively at the constrained **1.1.0**.
- Effective Java source/target and both Kotlin JVM targets were queried from Gradle.
- A standalone `app_process` smoke loaded the built debug APK on the connected
  **API37 Pixel 6a**, invoked the actual thumbnail generation path with an empty
  frameset, verified no thumbnail was produced, and observed the enabled debug
  diagnostic `Generation of seekbarPreviewData took 0 ms`. The temporary log-tag
  property was restored. This does **not** verify bitmap downloads, player UI,
  real service playback or API26 hardware.
- Scoped final code diff reviewed; existing user changes were not modified.

No instrumentation, installation, uninstall or app-data reset was performed.
Temporary smoke sources/launchers and device copies were removed. API26/27 runtime
coverage remains unexercised; Base64 availability there is established by the
official API contract, not a claimed device run. No size/performance delta measured.
