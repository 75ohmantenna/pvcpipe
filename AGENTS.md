# Working in PVCPipe

## Source of truth

- Establish behavior from implementation, relevant callers, configuration and
  behavioral tests. Inspect test doubles and assertions before generalizing their
  evidence. Correct stale guides and comments, but report possible code defects
  rather than documenting them as intentional contracts.
- `pvcpipe-extractor/extractor/src/main/java/org/schabi/newpipe/extractor/ServiceList.java`
  defines supported services and their persistent IDs. Removed IDs remain retired.
- `version.properties` is the shared app/extractor version source.
  `buildSrc/src/main/kotlin/PvcVersion.kt` computes and validates the Android code.
- Read `app/build.gradle.kts` for SDK levels, application IDs, packaging and signing,
  and `settings.gradle.kts` for the bundled extractor's composite-build substitution.
- `Makefile` defines the local release gate: run `make ci` before committing a
  release. Instrumentation is separate and subject to the safety rules below.
- `docs/README.md` separates current guides from dated observations. Historical
  failures and hardware results stay historical; add dated follow-ups rather
  than claiming older runs verify newer code.
- The main player controls operate active FCast playback. The cast dialog selects,
  switches or disconnects receivers; do not add a second playback-control panel.
  Follow `docs/playback.md` and `app/src/main/java/org/schabi/newpipe/player/cast/`.
- Verify significant behavior by running the changed path. Distinguish controlled
  protocol peers and emulator results from real receiver/hardware evidence.
- Playback audit findings 4–5 remain unresolved unless new evidence establishes
  otherwise; see `docs/playback.md` under "Unresolved navigation defects".
  Keep dated audit observations separate from current guarantees.

## RTK shell commands

Prefer dedicated file/search/edit tools when available. When using the shell,
apply these RTK rewrites explicitly; there is no automatic Codex hook:

| Command | Use |
| --- | --- |
| `git ...` | `rtk git ...` |
| `ls -la ...` | `rtk ls -la ...` |
| `cat file` | `rtk read file` |
| `sudo cat file` | `sudo rtk read file` |
| `grep ...` | `rtk grep ...` |
| `find ...` | `rtk find ...` |
| `wc -l ...` | `rtk wc -l ...` |
| `docker ...` | `rtk docker ...` |
| `kubectl ...` | `rtk kubectl ...` |
| `gh ...` | `rtk gh ...` |
| `npm run ...` | `rtk npm run ...` |
| `pnpm ...` | `rtk pnpm ...` |

Rewrite each part of a compound command. Never double-prefix an RTK command.
Do not prefix interactive tools (`vim`, `nano`, `top`, `htop`, `ssh`) or commands
without relevant output (`mkdir`, `chmod`, `rm`). RTK's own `gain`, `discover`
and `proxy` commands need no rewrite.

## Remote submission restriction

- The only permitted submission destination for this project is
  `github.com/75ohmantenna/pvcpipe`.
- Never push, open or update pull requests, create issues, post comments, publish
  releases, or make any other remote mutation against
  `github.com/bravepipeproject/BravePipe` or another upstream repository.
- This restriction also applies to direct URLs, GitHub CLI `--repo` overrides,
  API calls, and any upstream remote that is added later.
- Do not bypass, remove, or weaken the local `pre-push` guard, or change the
  GitHub CLI default away from `75ohmantenna/pvcpipe`.
- Run `gh` commands outside the sandbox so they can access the keyring.

## Android device-test safety

- Before running instrumentation on a user's device, inspect the selected Gradle
  task's installation and cleanup behavior, the test runner, and test database/data
  isolation. Do not assume `connectedDebugAndroidTest` preserves installations:
  in this repository it was observed uninstalling both the debug app and its test
  package after testing. See `docs/local-playlist-mutations.md`.
- On an existing user installation, never run a test path that uninstalls packages
  or clears app data. If an update is needed, verify the installed package and
  signing compatibility before `adb -s <serial> install -r`; use targeted direct
  instrumentation with isolated, disposable test data where appropriate.
  If preservation cannot be established, use a separate test environment.
- Reinstalling an APK does not recover private data deleted by an uninstall.
