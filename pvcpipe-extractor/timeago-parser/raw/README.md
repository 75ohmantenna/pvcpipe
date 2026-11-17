# Time-ago parser raw data

Source data for the relative-date parser compiled into the extractor.

- `times/` has one file per unit, from `seconds.json` to `years.json`, with
  the strings for that unit in each language.
- `overview.json` is generated from the files in `times/`.
- `unique_patterns.json` is the input of the generator that writes the
  pattern classes.

Run `GenerateOverview`, `GeneratePatternClasses` and `CheckAll` from the
`pvcpipe-extractor/` working directory. Their paths are relative to that
directory. The entry points are in the
[generator sources](../../timeago-generator/src/main/java/org/schabi/newpipe/timeago_generator/).
`GeneratePatternClasses` writes the Java pattern classes and `PatternMap`
under `timeago-parser/src/main/java/`. Normal app builds compile those checked-in
sources directly.
