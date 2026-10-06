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
