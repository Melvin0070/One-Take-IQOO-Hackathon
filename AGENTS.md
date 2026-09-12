# One-Take — agent guide

**A script supervisor in the viewfinder.** While you film, it knows which lines of your
script still lack a clean take, asks for exactly those between lines, tells you when it is
safe to wrap, and plays the finished, captioned cut the moment you stop. On the phone,
offline.

Built for the iQOO City Battles, Chennai, **Sep 12–13 2026**. Android, Kotlin, Compose.
Three humans and many agents. The three source documents are
[`One-Take-Design-v3.md`](One-Take-Design-v3.md) (why),
[`One-Take-Contract.md`](One-Take-Contract.md) (what, authoritative) and
[`One-Take-Playbook.md`](One-Take-Playbook.md) (who, when, gates).

---

## Read this in five minutes

1. This file — the invariants, the map, the working agreement.
2. [`docs/agents/landmines.md`](docs/agents/landmines.md) — verified traps that each cost
   somebody a morning. **Read before touching `:asr`, `:npu`, `:media` or `:capture`.**
3. Your module's `AGENTS.md` (e.g. [`asr/AGENTS.md`](asr/AGENTS.md)).
4. [`docs/agents/lanes.md`](docs/agents/lanes.md) — pick up work without asking.

Everything else is reference you pull when you need it. The index is
[`docs/agents/README.md`](docs/agents/README.md).

---

## You have a lot of latitude here

This repo is a **starting point, not a cage.** The scaffold was generated from the Contract
in one pass by one agent; it has not been through a build on the target device, and it is
very likely wrong somewhere. If a better design is in front of you, take it.

**Change freely, no permission needed:** any implementation, any file layout inside a
module, any Gradle setting, dependency versions, the module split itself, test strategy,
UI structure, threading inside a module, naming, the scaffold's TODO stubs — all of it. If
`engine/src/main/kotlin/.../CoverageEngine.kt`'s reorder-window approach is wrong, replace
it. If a module boundary is costing more than it buys, collapse it.

**Say so in the commit, then proceed:** anything that changes a type in
[`docs/agents/contract.md`](docs/agents/contract.md) (other lanes compile against it), adds
a dependency, adds a permission, or changes a number that appears on the eval card.

**Stop and ask a human:** only if the change would make a claim we make on stage untrue —
the privacy promises (P1–P5), the requirement acceptance tests (R1–R25), or a number
already printed on the eval card. Those are promises to a jury, not engineering choices.

The invariants below are short on purpose. They are the things where the failure is
**silent** — where being wrong doesn't show up until you are on stage. Everything not on
that list is yours.

---

## Invariants

Seven. Each one exists because its failure mode is invisible until it is expensive.

1. **`:engine` never imports `android.*`.** Enforced by the compiler — it uses
   `kotlin("jvm")`, so the Android SDK is not on its classpath. This is what makes the
   brain testable in two seconds instead of two minutes on a device, which is what makes
   automated gate checks possible at all.

2. **One clock: the audio sample index.** 16 kHz mono, one sample = 62.5 µs. Every ledger
   event, take boundary and edit point is a `Long` sample index. Wall-clock appears in log
   lines and nowhere else. A `System.currentTimeMillis()` in the fold is the one thing that
   breaks deterministic replay (R14), and it will not show up in any test you write.

3. **VAD indices cut audio. ASR timestamps do not.** Transducer timestamps are 40 ms-grid
   emission peaks over BPE sub-word tokens with undocumented lag — not word boundaries. Use
   them for *which line, roughly where*. Cut on VAD silences. Crossing the two produces a
   clipped word inside the payoff beat. ([landmines L4](docs/agents/landmines.md))

4. **`CoverageEngine.submit()` is single-threaded and totally ordered.** One channel, one
   consumer loop. No locks, no threads, no randomness inside the fold. Replay, the eval
   harness and replay-driven UI are then the same line: `inputs.forEach(engine::submit)`.

5. **Nothing is destructive.** Scratch, edit, delete-line, lost video and undo all *set a
   field*. Raw files and the ledger are always kept. Derived state is recomputed from the
   append-only log, which is why a crash cannot corrupt a project (R13).

6. **Precision over recall, everywhere.** A clean read flagged in front of a juror costs
   more than a missed flub — the pickup list still catches the flub. When a threshold is a
   judgement call, pick the one that flags less. Every automatic decision is visible in
   words and reversible in one tap.

7. **Nothing ships unverified.** Every feature above Tier 0 sits behind a flag in
   `FeatureFlags`, off by default. A flag turns on when its own check passes *and* the
   Tier 0 check still passes. No number reaches a slide unless it was measured on the
   build it describes.

---

## Where things are

```
:engine          PURE JVM. Interfaces, aligner, ledger, coverage fold, normalizer,
                 edit-list rules, config. Depends on nothing. The brain.
:engine-fixtures Corpus loader, golden ledgers, FakeRecognizer, FakeVad, FakeVision.
:eval            JVM CLI. corpus -> eval card, in one command.
:asr             sherpa-onnx CPU: Silero VAD, streaming transducer, keyword spotting.
:npu             LiteRT + Qualcomm accelerator. The ONLY module that links QNN.
:capture         CameraX video, AudioRecord, foreground service, WAV, timebase anchor.
:media           Media3 playback from the edit list, Transformer export, captions.
:link            Multicam pairing, key exchange, encrypted transfer. Tier 2.
:app             Compose UI, every screen, debug panel, DI wiring, ATTRIBUTION.

experiments/     The pre-event prototype. NOT in the build. A mining reference for
                 working CameraX / VAD / JNI / Media3 patterns. See experiments/AGENTS.md.
config/          Permission + dependency allowlists, debug keystore.
docs/agents/     This context pack.
```

The module graph **is** the parallelization plan: lanes in different modules do not touch
the same files, so agents do not conflict. If you need to change a file in someone else's
module, that is a signal the seam is in the wrong place — say so rather than reaching
across.

## Which document answers which question

| Question | Read |
|---|---|
| What am I building, in what order? | [`docs/agents/lanes.md`](docs/agents/lanes.md) |
| What are the exact types? | [`docs/agents/contract.md`](docs/agents/contract.md), then `engine/src/main/kotlin/` |
| What does "done" mean for this feature? | [`docs/agents/requirements.md`](docs/agents/requirements.md) — R1–R25, each with its test |
| Why does this API not work the obvious way? | [`docs/agents/landmines.md`](docs/agents/landmines.md) |
| Was this already decided? | [`docs/agents/decisions.md`](docs/agents/decisions.md) — **check before re-litigating** |
| What word does the UI use for this? | [`docs/agents/glossary.md`](docs/agents/glossary.md) |
| How do I run / test / verify? | [`docs/agents/workflow.md`](docs/agents/workflow.md) |
| How do the pieces fit? | [`docs/agents/architecture.md`](docs/agents/architecture.md) |
| The full product argument | [`One-Take-Design-v3.md`](One-Take-Design-v3.md) |

---

## Commands

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

./gradlew :engine:test              # the brain. ~2s. Run this constantly.
./gradlew verify                    # tests + assembleDebug + guards. Before every commit.
./gradlew guards                    # R17 permissions + P4 dependency allowlist
./gradlew generateAttribution       # regenerate ATTRIBUTION.md from the resolved graph
./gradlew :app:assembleDebug
./gradlew :eval:run --args="--corpus corpus/ --out eval-card.md"

adb install -r app/build/outputs/apk/debug/app-debug.apk   # NEVER uninstall: wipes models
```

`./gradlew verify` is the gate. If it is red, you are not done — say it is red rather than
describing the change as complete.

---

## Working agreement

**Trunk-based, small commits, merge hourly.** Long branches are how a hackathon loses four
hours at 23:00. Trunk must build and pass `:engine:test` before any merge.

**Conventional Commits**, no attribution trailers:
`feat(engine): close takes on line-end pause`, `fix(asr): raise maxSpeechDuration`.

**Comment only non-obvious *why*** — intent, trade-offs, the landmine you just avoided.
Never restate what the code does. The existing comments in `:engine` are the house style:
each one explains a decision that will otherwise be undone by the next reader.

**When you finish a unit of work**, say in two lines what changed and what you verified.
If something is unverified, say that instead of implying it passed.

**When you are blocked**, do everything that doesn't depend on the answer first, then state
the assumption you made and keep going. Do not stall a lane waiting for a human — three of
them are asleep in shifts.

### Things that will waste your time if nobody tells you

- **Do not add a dependency casually.** `./gradlew guardDependencies` will fail you.
  Answer three questions in the commit: does it add a permission, does it make a network
  call, does it ship a second ONNX Runtime or QNN runtime. A second `libonnxruntime.so`
  fails at `dlopen` — at runtime, on the phone, at 23:00 — not at build time.
- **Do not add a manifest permission** without adding it to
  `config/allowed-permissions.txt` with a reason. `INTERNET` in the single-phone build
  breaks P1, which is a promise made on stage to an infosec juror.
- **Do not tune a threshold on data the eval card reports.** Thresholds are tuned on
  teammates' reads. Every stranger read is held out and is the only data the card reports.
- **Do not write a number on a slide that you did not measure on the build it describes.**
- **Do not use `experimentalSetMp4EditListTrimEnabled`** — the trimmed data stays in the
  file, which makes The Vanish a lie and P3 false, and the juror can open the file.

---

## Current state, honestly

**What is verified:** `./gradlew verify` is green — `:engine`'s tests pass, all nine modules
compile, `:app:assembleDebug` produces a 44 MB APK, and both guards run against the real
merged manifest and the real resolved dependency graph. Verified on macOS with Android
Studio's bundled JDK 25 and SDK 37, on 2026-09-12.

**What the guards already caught, on their first real run:** Media3 declares
`ACCESS_NETWORK_STATE` and `WAKE_LOCK`, so the merged manifest of an app we call offline
contained a network permission. Both are now stripped with `tools:node="remove"` — see D33
in [`docs/agents/decisions.md`](docs/agents/decisions.md). That is the exact failure P1 and
R17 exist to catch, and it was in the build within an hour of the build existing.

**What does not exist:** the product. `:engine` has the frozen types from Contract §4 and
`TODO("Lane A: ...")` at every behavioural boundary. `:engine-fixtures` has real, working
fakes — those unblock seven lanes, so they were built for real rather than stubbed. The six
Android modules are shells with their port implementations declared and their landmines
documented at the call site.

**What is unproven:** everything on hardware. Nothing has run on the target device, and
several pinned choices — sherpa 1.13.8, LiteRT `qualcomm_runtime_v81`, Architecture B
concurrent capture, the `Accelerator.NONE` trick — are **researched but unverified on this
phone**. The bring-up harness in
[`docs/agents/workflow.md`](docs/agents/workflow.md#device-bring-up) exists to prove or kill
each of them in the first thirty minutes on a loaner.

Assume the scaffold is wrong before you assume the device is.
