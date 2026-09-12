# CLAUDE.md

The project spec lives in `AGENTS.md` so that Claude Code, Codex and anything else read the
same file. It is imported below — read it as if it were written here.

@AGENTS.md

---

## Claude Code specifics

**Module instructions load on demand.** Each module has its own `AGENTS.md` and a
`CLAUDE.md` that imports it. Reading a file under `asr/` pulls in `asr/CLAUDE.md`
automatically; you do not need to open it by hand.

**Before a long task, check `docs/agents/decisions.md`.** It records what is already
settled and why. A surprising number of "obvious" improvements here were tried and rejected
for a stated reason — Architecture A vs B, NPU Whisper, filler splicing on clean takes,
`ConcatenatingMediaSource`. Re-deriving them costs an hour each.

**Prefer `./gradlew :engine:test` over building the app.** The brain is pure JVM and its
suite runs in about two seconds; `:app:assembleDebug` pulls the NDK and Compose. Most of
what you want to verify is an assertion over the ledger, so it belongs in `:engine`.

**Subagents.** `.claude/agents/` has two that are project-specific:
- `contract-auditor` — checks a diff against the R1–R25 acceptance tests and the seven
  invariants. Use it after implementing anything in `:engine`.
- `landmine-check` — checks a diff against `docs/agents/landmines.md` before it merges.
  Cheap, and it catches the failures that are silent on a laptop.

**Slash commands.** `/lane`, `/verify`, `/bringup`, `/gate` — see `.claude/commands/`.

**Parallelism.** The module graph is designed so eight lanes run without touching the same
files. Launching several agents on different modules in one message is the intended use.
Two agents in the same module will conflict; don't.

**Permissions.** `.claude/settings.json` allowlists the Gradle, adb, git and file commands
this project uses, so routine work does not prompt. Anything not on that list still asks —
which is correct, especially for `adb uninstall` (wipes models and sessions from a loaner)
and anything that writes outside the repo.
