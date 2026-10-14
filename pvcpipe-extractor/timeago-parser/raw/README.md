# Time-ago parser raw data

Source data for the relative-date parser compiled into the extractor.

- `times/` has one file per unit, from `seconds.json` to `years.json`, with
  the strings for that unit in each language.
- `overview.json` is generated from the files in `times/`.
- `unique_patterns.json` is the input of the generator that writes the
  pattern classes.

The generators are not in this directory. `GenerateOverview`,
`GeneratePatternClasses` and `CheckAll` are in
`../../timeago-generator/src/main/java/org/schabi/newpipe/timeago_generator/`.
Their file paths are relative to `pvcpipe-extractor/`, so run them with that
working directory. `GeneratePatternClasses` writes the Java pattern classes
and `PatternMap` under `timeago-parser/src/main/java/`; the extractor adds
that directory to its main source set. Generation is not a dependency of a
normal app build.
