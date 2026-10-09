@/home/user/.codex/RTK.md

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
