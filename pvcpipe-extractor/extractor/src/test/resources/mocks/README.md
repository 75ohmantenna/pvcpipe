# Recorded fixture privacy

These recordings contain public service responses used for offline extractor
replay. Preserve the public content, licenses and attribution the tests exercise.
Captured IPv6 addresses, response cookie values, visitor data and media tokens
have synthetic replacements. Consent markers, public client identifiers and
signature/deobfuscation inputs can be intentional protocol fixtures.

Before publishing new recordings, inspect requests, response headers, embedded
JSON/HTML, URLs and numeric request-body arrays. The recorder only replaces IPv4
addresses in response bodies. The narrow maintenance tool below also replaces
known captured identifiers, checks nested URL encodings and updates matching
UTF-8 request payloads. It reports binary request payloads that require separate
review and exits unsuccessfully when any are skipped:

```sh
python3 tools/sanitize_recorded_mocks.py pvcpipe-extractor/extractor/src/test/resources/mocks
python3 tools/sanitize_recorded_mocks.py pvcpipe-extractor/extractor/src/test/resources/mocks --write
python3 -m unittest discover -s tools -p test_sanitize_recorded_mocks.py
```

The tool tests and corpus check are part of `make ci`. Run the affected replay
tests after a change. The tool is not a general privacy
scanner: a clean check cannot establish that arbitrary new recordings are safe.
Do not record account credentials, private user activity or personal content.

The maintenance tool does not replace IPv4 addresses. The recorder only replaces
IPv4 in response bodies; inspect request fields, response headers and latest URLs
separately. Empty input paths and skipped binary payloads fail the corpus check.

The check cannot reconstruct cookie values after their source headers were
already redacted. Keep original captures outside Git and compare sanitized output
against those originals locally before publication. Unsupported detected escape
forms fail the check and require review rather than yielding a clean result.
