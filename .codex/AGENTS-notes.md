# Notes for Codex CLI

`AGENTS.md` at the repo root is the spec; Codex reads it natively, merging the root file
with any nested `AGENTS.md` in the directory you are working in. Each module has one — read
yours before editing it.

## Sandbox and approvals

Most work here is ordinary file editing plus Gradle. Two categories genuinely need care and
are worth approving deliberately rather than blanket-allowing:

- **`adb uninstall` / `pm uninstall`** — wipes models and sessions from a loaner. Re-importing
  a model over file transfer costs ten minutes. Always `adb install -r` instead; the
  committed debug keystore exists so that works from any machine.
- **`git push`** — trunk is shared and three people merge to it hourly.

Gradle needs network access on a cold build (it downloads the wrapper distribution and
dependencies). If you are running fully sandboxed with no network, expect the first
`./gradlew` invocation to fail on `Could not create parent directory for lock file
~/.gradle/...` or a dependency resolution error — that is the sandbox, not the build.

## Before you start

```
AGENTS.md                     the invariants, and what you are free to change
docs/agents/lanes.md          pick up work without asking
<module>/AGENTS.md            your module
docs/agents/landmines.md      read the sections your module's AGENTS.md names
docs/agents/decisions.md      check before re-deriving anything
```

## The one habit that matters here

`./gradlew :engine:test` runs in about two seconds because the brain is pure JVM. Most of
what you want to verify is an assertion over the ledger, so it belongs there rather than in
an instrumented test on a phone. There are roughly sixty minutes of human device-check time
in the entire build; every check you automate is a minute handed back.
