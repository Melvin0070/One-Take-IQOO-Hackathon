# The contract

The types eight lanes compile against. **The source of truth is the code** —
`engine/src/main/kotlin/com/onetake/engine/` — and this page is the map, the rules that
are not expressible as types, and the protocol for changing any of it.

---

## Changing a type here

Freezing these was the only serial step in the build. That does not make them sacred; it
makes them **shared**, which means the cost of changing one is other people's compile
errors rather than your own.

**Do it, and announce it.** Change the type, fix every call site you can see, and put
`BREAKING:` in the commit subject with one line on what moved. The integration owner needs
to know that a red trunk is yours and expected.

**Do not** change one silently, and do not work around a wrong type by adding a parallel
one — two types that mean the same thing is how a build ends up with two coverage state
machines that disagree on stage.

---

## Files

| File | What is in it |
|---|---|
| `Time.kt` | The sample-index clock and its conversions |
| `Inputs.kt` | `AudioFrame`, `Word`, `RecognizerResult`, `VadEvent`, `VisionFrame`, `VideoAnchor`, `UserAction`, `EngineInput` |
| `Ports.kt` | `Recognizer`, `VoiceActivity`, `VisionSignal`, `CommandSpotter`, `RecognizerConfig` |
| `Script.kt` | `LineId`, `TakeId`, `LineType`, `ScriptLine`, `Script` |
| `Take.kt` | `Take`, `Verdict`, `MiscueType` |
| `Ledger.kt` | `LedgerEvent` and everything it carries |
| `Coverage.kt` | `LineState`, `CoverageState` |
| `EditList.kt` | `EditList`, `Segment`, `SegmentRole`, `CaptionSource` |
| `RuntimeConfig.kt` | Thresholds, timings, `FeatureFlags`, `flagVectorChecksum()` |
| `CoverageEngine.kt` | The single entry point |
| `Normalizer.kt` | One normalizer, three modes |

## The single entry point

```kotlin
class CoverageEngine(config: RuntimeConfig, script: Script, sessionId: String) {
  fun submit(input: EngineInput)   // SINGLE-THREADED, TOTALLY ORDERED
  fun drain()                      // flush the reorder window
  val events: Flow<LedgerEvent>    // the only output
  fun snapshot(): CoverageState    // pure fold over the ledger
  fun editList(): EditList
}
```

Replay, the eval harness and replay-driven UI development are the same call:

```kotlin
inputs.forEach(engine::submit)
```

## The ports

Each has a real implementation in an Android module and a fake in `:engine-fixtures`, and
the engine cannot tell them apart. That is what buys you a phone-free development loop.

```kotlin
interface Recognizer     { fun start(cfg: RecognizerConfig); fun accept(f: AudioFrame)
                           fun close(); val results: Flow<RecognizerResult> }
interface VoiceActivity  { fun accept(f: AudioFrame): VadEvent? }
interface VisionSignal   { val frames: Flow<VisionFrame> }
interface CommandSpotter { val commands: Flow<SpottedCommand>
                           fun accept(f: AudioFrame); fun close() }
```

---

## Rules that are not types

### Coverage state machine

Authoritative. `:engine` owns it as a pure fold; `:app` renders `CoverageChanged` and never
computes coverage.

| From | Event | To |
|---|---|---|
| unread | a take for this line closes | pending |
| unread | speech matches a later line (skipped) | needed |
| pending | verdict clean (must-say: word-perfect) | covered |
| pending | verdict flubbed or missing words | needed |
| needed | a new take for this line closes | pending |
| covered | a new take of *that* line closes | covered; replaces the cut take only if clean |
| covered | its only clean take is scratched | needed |
| needed | the scratch is undone | covered |
| any | the line's text is edited | needed; **id unchanged**, takes kept and marked "read against an earlier version" (R18) |
| any | the line is deleted | takes marked orphaned and kept (R24) |
| any | lines are reordered | ids unchanged; cut order re-derived from new positions (R24) |
| covered | its only clean take's video is lost | needed; take shows video missing (R13) |

**Wrap ready:** every line covered, must-say by strict verdict. **A pending verdict blocks
wrap ready until it lands.** The hold limit moves the strip; it never moves the coverage
state. Nothing claims coverage before its verdict.

### Aligner

1. **Joining.** Each utterance joins the open take, or opens a take for whichever line it
   matches best **across the whole script**, biased toward current / next / needed by
   `lineMatchMarginBias`. **Covered lines are candidates** — that is R20, and getting it
   wrong is the failure ranked worst in the whole design.
2. **Closing.** A take closes when silence passes the line-end pause, when the next
   utterance matches a different line, or on a tap or remote advance. Shorter pauses inside
   a line never close a take.
3. **One breath, two lines.** An utterance spanning lines N and N+1 marks **both covered**
   and emits **one** segment for the whole utterance. It splits coverage, not audio — so no
   word boundary is needed and no splice lands inside natural speech.
4. **A must-say line is never split.** Force the take to close at the must-say boundary.
5. **Verdicts.** Clean = line-length-aware similarity at or above the threshold and no
   restart. Flubbed = a restart, or similarity below the threshold. Missing words = the
   line's tail is absent when the take closes. Every verdict records a `MiscueType`.
6. **Scratches.** *Implicit:* a later clean take of the same line supersedes earlier ones.
   *Explicit:* an utterance consisting **only** of a command phrase scratches the open take,
   or the most recent closed take if none is open, and is itself excluded from the cut.
   **Guard: standalone utterance only** — a script line containing "cut" must never scratch.
7. **Filler splicing.** Flubbed takes only. Only where there is **>150 ms of silence on both
   sides**. Never on a must-say take. Clean-take splicing is behind a flag that is off on
   the demo build.
8. **Off-frame takes.** Never flagged, never marks a line needed. Loses to any other clean
   take in edit-list selection, and the review screen says why.
9. **Pending verdicts.** The strip holds on the current line until the verdict arrives or
   the hold limit passes. Past the limit the strip advances; a late flubbed verdict marks
   the line needed and prompts at the next line-end pause.
10. **Skipped line.** If speech matches the next line better than the current one, the
    current line is marked needed and tracking moves on.
11. **Ignored prompt.** Nothing blocks. The line stays needed and shows in the pickup list.
12. **If verdicts cannot keep up.** Live prompts switch off, the strip advances at every
    line-end pause, and needed lines collect in the pickup list — recorded before leaving
    the set. Degrade honestly; never pretend.

### Edit list

**Two separate rules.** Conflating them is R21.

- **Selection** — per line: the circled take if there is one, else the **latest clean take
  by take-close time across all of the project's sessions**.
- **Ordering** — the cut is ordered by **script line order**. Never by take-close time.

### Normalization

One `Normalizer` with three modes (normal, must-say strict, caption). Case, punctuation,
numbers, currency and dates (`"70k"` == `"seventy thousand rupees"`), a per-project alias
list for brand names, per-token fuzzy matching.

Its specification is R2's acceptance test: strangers read `"iQOO 15"`,
`"Snapdragon 8 Elite Gen 5"`, `"₹70k"`, `"2026"`, `"4K at 60 fps"` cleanly and get **zero
flags**.

### Runtime config

Thresholds per line type, hold limit, line-end pause, device offsets, command phrases,
brand aliases, recognizer choice, feature flags, and `configVersion`.

**Nothing tunable may be hardcoded at a call site.** Red Light has no rebuilds — new config
arrives over Office Kit and is imported like a model, SHA-256 verified.

`flagVectorChecksum()` is R25. **`FeatureFlags` field order is frozen** — the checksum reads
it positionally. Append only.

---

## Full text

This page is the distillation. When it is not enough:
[`One-Take-Contract.md`](../../One-Take-Contract.md) §4 (the surface), §5 (data model), §6
(state machine), §7 (aligner deltas). The reasoning behind each rule is in
[`decisions.md`](decisions.md).
