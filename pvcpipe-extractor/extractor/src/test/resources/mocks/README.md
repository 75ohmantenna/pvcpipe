# Recorded fixture privacy

These recordings contain public service responses used for offline extractor
replay. Preserve the public content, licenses and attribution the tests exercise.
Captured IPv6 addresses, response cookie values, visitor data and media tokens
have synthetic replacements. Consent markers, public client identifiers and
signature/deobfuscation inputs can be intentional protocol fixtures.

Before publishing new recordings, inspect requests, response headers, embedded
JSON/HTML, URLs and numeric request-body arrays. The recorder replaces IPv4
addresses in response bodies only. The sanitizer replaces known captured
identifiers, checks nested URL encodings and updates matching UTF-8 request
payloads. Run the first command to check, the second to apply replacements
and the third to test the sanitizer.

```sh
python3 tools/sanitize_recorded_mocks.py pvcpipe-extractor/extractor/src/test/resources/mocks
python3 tools/sanitize_recorded_mocks.py pvcpipe-extractor/extractor/src/test/resources/mocks --write
python3 -m unittest discover -s tools -p test_sanitize_recorded_mocks.py
```

Both the tool tests and corpus check run in `make ci`. Empty input, skipped
binary payloads and detected unsupported escape forms fail the corpus check.
The sanitizer covers known identifiers and cannot reconstruct cookie values
whose source headers are already redacted. Inspect IP addresses in request
fields, headers and final URLs separately.

Keep original captures outside Git, compare them against sanitized output and
run affected replay tests before publishing fixtures. Exclude account
credentials, private user activity and personal content.
