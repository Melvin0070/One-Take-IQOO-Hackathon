# Workflow

## Setup

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :engine:test
```

Both exports are needed and both failures look like Gradle problems rather than
environment problems:

- no `JAVA_HOME` → `Unable to locate a Java Runtime`. There is no system JDK on these
  machines, only Android Studio's bundled one.
- no `ANDROID_HOME` → `SDK location not found`. Alternatively put `sdk.dir` in
  `local.properties`, which is gitignored.

Put both in your shell profile. Claude Code and Codex read them from `.claude/settings.json`
and `.codex/config.toml` respectively, so agents get them automatically.

## Commands

| Command | When |
|---|---|
| `./gradlew :engine:test` | Constantly. ~2 s. This is the brain and most of your assertions live here |
| `./gradlew verify` | **Before every commit.** Tests + `assembleDebug` + guards |
| `./gradlew guards` | R17 permissions + P4 dependency allowlist |
| `./gradlew guardPermissions` | After adding anything to the manifest, or any dependency |
| `./gradlew generateAttribution` | After a dependency changes. Event rule compliance |
| `./gradlew :app:assembleDebug` | Before installing |
| `./gradlew :eval:run --args="--corpus corpus/ --out eval-card.md"` | The eval card, one command |
| `adb install -r app/build/outputs/apk/debug/app-debug.apk` | **`-r`, always** |

**Never `adb uninstall`.** It wipes models and sessions from the loaner, and re-importing a
78 MB model over Office Kit costs you ten minutes you do not have. The committed debug
keystore exists precisely so `-r` works from any laptop and from CI.

`./gradlew verify` is the gate. **If it is red, you are not done** — say it is red and show
the output rather than describing the change as complete.

## Commits

Conventional Commits, scoped by module, no attribution trailers:

```
feat(engine): close takes on line-end pause
fix(asr): raise maxSpeechDuration so long lines are not force-split
refactor(capture): move the WAV writer behind the port
docs(agents): record the multicam pairing decision
```

Types: `feat`, `fix`, `chore`, `refactor`, `docs`, `test`, `perf`.

**Small commits, merged to trunk at least hourly.** No long branches. Trunk must build and
pass `:engine:test` before any merge — the suite is two seconds, which is the whole reason
the brain is pure JVM.

If your work queues while the build is frozen for a check window, the integration owner
drains the queue in the first twenty minutes of the next open block, before new feature
work starts.

## Comments

Comment only non-obvious **why** — intent, trade-offs, the landmine you just avoided.
Never restate what the code does. The comments already in `:engine` are the house style:
each one explains a decision that would otherwise be undone by the next reader, and several
name the specific failure they prevent.

A comment worth writing:

```kotlin
// Raise this: the default 5 s is a forced-split hack that SILENTLY overrides
// your threshold and minSilenceDuration mid-session. Creators read long lines.
```

A comment not worth writing:

```kotlin
// set the max speech duration
```

## Adding a dependency

`./gradlew guardDependencies` fails on anything not in `config/allowed-dependencies.txt`.
Before you add a line there, answer three questions **in the commit message**:

1. **Does it add a manifest permission?** Run `guardPermissions` after. `INTERNET` in the
   single-phone build breaks P1, which is a promise made on stage.
2. **Does it make a network call, ever?**
3. **Does it ship a second ONNX Runtime or a second QNN runtime?** A second
   `libonnxruntime.so` fails at `dlopen` — at runtime, on the phone, at 23:00 — not at
   build time.

Small, well-known, zero-transitive-dependency packages are fine. Large trees, native build
steps, or anything overlapping an existing dependency: ask first.

## Feature flags

Every feature above the spine sits behind a field in `FeatureFlags`, **off by default**.

A flag turns on when the feature's own check passes **and** the Tier 0 check still passes.
Anything unverified stays off — a feature shipped with its flag off scores nothing, but a
feature shipped on and broken costs the spine.

**`FeatureFlags` field order is frozen.** `flagVectorChecksum()` reads it positionally, so
inserting a flag in the middle changes every previously-recorded checksum. Append only.

## Verifying honestly

The one thing that matters more than anything else in this repo: **report what actually
happened.**

- Tests failed → say so, with the output.
- Ran on a laptop but not a phone → say which.
- The number came from a vendor benchmark, not your measurement → say so, and do not put it
  on a slide.
- A check was skipped → say it was skipped.

Three people will be running on 1.5 hours of sleep by Sunday morning, directing agents and
reviewing output. At 04:00 the human in the chair is the weakest link in the system, and a
green test suite is worth more than a careful reviewer. That only holds if the suite is
telling the truth.

## Device bring-up

**The first thirty minutes on a loaner buy device characterization, not features.** Every
item below is a surprise you would otherwise find at 23:00. Build one debug-panel screen
that reports all of it in one place:

- [ ] `SENSOR_INFO_TIMESTAMP_SOURCE` value
- [ ] **Concurrent capture works on THIS device** — [L9](landmines.md)'s three assertions,
      both start orders, buffers checked directly
- [ ] **ASR is not silently empty** — [L1](landmines.md). A product-killer with no log line
- [ ] Which accelerator LiteRT actually chose, and per-inference microseconds —
      [L6](landmines.md)'s `Accelerator.NONE` route
- [ ] Streaming zipformer RTF, measured. Nobody has published this number
- [ ] `CamcorderProfile.get(cameraId, QUALITY_1080P).videoBitRate` → real MB/min
- [ ] `getCurrentThermalStatus()` and battery
- [ ] Whether the Bluetooth remote pairs, and **what keycode it emits** — log unhandled
      keycodes so an unknown remote is supported in thirty seconds
- [ ] Free storage, and estimated recordable minutes
- [ ] Whether a `mediaProcessing` foreground service survives
- [ ] **The flag-vector checksum and config version** (R25)

## Gate checks

Windows are short and shared. Three of them, roughly 120 minutes total, minus the Tier 0
check and its re-runs — **about sixty minutes for feature checks, against 25+ features.**

Which is why: **automate everything ledger-observable.** The Tier 0 check itself — five
back-to-back sessions of a three-line script with one deliberate flub each — is mostly
assertions over the ledger. Write it as an instrumented test driven by a recorded audio
fixture, played through the device speaker or injected at the `Recognizer` seam. Same for
R20, R21, scratch-that, must-say, free-talk, circle/undo.

Budget the human-only checks explicitly, because they are the scarce resource: audible
splice artifacts, caption legibility, the remote from 1 m, the juror beat end to end,
off-frame behaviour, thermal after five sessions.

Full criteria: [`requirements.md`](requirements.md#the-tier-0-check).

## Working with other agents

The module graph exists so that lanes do not conflict. **One agent per module.** Two agents
in the same module will produce conflicting edits to the same files, and merging them costs
more than the parallelism bought.

Needing to edit another module's file is a signal that the seam is in the wrong place. Say
so — proposing a better boundary is welcome; reaching across one silently is not.

When you change something another lane compiles against — anything in
[`contract.md`](contract.md) — say so in the commit subject so the integration owner knows
to expect breakage elsewhere.
