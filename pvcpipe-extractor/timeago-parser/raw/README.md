# Time-ago parser raw data

The JSON files the time-ago parser is generated from.

- `times/` has one file per unit, from `seconds.json` to `years.json`, with
  the strings for that unit in each language.
- `overview.json` is generated from the files in `times/`.
- `unique_patterns.json` is the input of the generator that writes the
  pattern classes.

The generators are not in this directory. `GenerateOverview`,
`GeneratePatternClasses` and `CheckAll` are in
`../../timeago-generator/src/main/java/org/schabi/newpipe/timeago_generator/`.
