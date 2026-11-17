# Legacy Ready handler fixture

`legacy-ready.ser` is a synthetic Java-serialization fixture captured before adding
`restorationHandler` to `ReadyChannelTabListLinkHandler`. The baseline extractor
class at `81b415214` computed a `serialVersionUID` of
`-4117307732798877956L`.

It was written with ObjectOutputStream using the original four-argument
constructor, URL `https://www.youtube.com/channel/UC1234567890123456789012/videos`,
ID `UC1234567890123456789012`, `ChannelTabs.VIDEOS`, and the test-only
`LegacyChannelTabBuilder(17)`. The builder has an explicit UID of 1 and retains
its original fields. All content is synthetic; no network response or user data
is included.

Keep this fixture as written by the baseline class. The regression exercises
the absent restoration field and the old computed UID. Restoring unknown or
legacy handlers preserves their extractor strategy and can retain a large
payload.
