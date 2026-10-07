# Time ago parser raw resources

This directory holds JSON-formatted time-ago string data for the parser.
`times/` groups strings by time unit (for example, seconds and years).
`overview.json` is generated from those files; `unique_patterns.json` is the
input to the pattern-class generator.

The overview, coverage-checking, and pattern-generation Java sources are in
`../../timeago-generator/src/main/java/org/schabi/newpipe/timeago_generator/`,
not in this directory.