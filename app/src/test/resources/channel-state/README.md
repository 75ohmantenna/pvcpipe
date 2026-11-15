# Legacy Ready handler fixture

`legacy-ready.ser` is a synthetic Java-serialization fixture captured before adding
`restorationHandler` to `ReadyChannelTabListLinkHandler`. The baseline extractor
class has computed serialVersionUID `-4117307732798877956L` (source at
`81b415214`, before this change).

It was written with ObjectOutputStream using the original four-argument
constructor, URL `https://www.youtube.com/channel/UC1234567890123456789012/videos`,
ID `UC1234567890123456789012`, `ChannelTabs.VIDEOS`, and the test-only
`LegacyChannelTabBuilder(17)`. The builder has an explicit UID of 1 and retains
its original fields. All content is synthetic; no network response or user data
is included.

Do not regenerate this fixture with the new class: the regression must exercise
an absent restoration field and the old computed UID. Unknown/legacy handlers
intentionally keep their original extractor strategy, even if that means a
legacy handler still carries a large payload.
