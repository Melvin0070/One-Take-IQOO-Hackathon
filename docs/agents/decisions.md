# Decisions already made

**Check this before re-deriving anything.** Every entry below was argued and settled, most
of them against research or a real artifact. Re-litigating one costs an hour you do not
have, and several of them look wrong until you know the reason.

If you think one is wrong, that is allowed — say which entry and why in the commit, and
change it. What is not allowed is silently doing the rejected thing because it looked
obvious.

---

## Architecture

**D1. Architecture B, not A.** CameraX muxes its own audio; the MP4 *is* the cut source.
Android's docs say the platform compensates for the audio/camera timebase difference only
when buffers go directly from camera to encoder. Both are undocumented for our case, but
**B fails loudly in code; A fails as a silent constant offset discovered on stage.** Capture
A's WAV unconditionally anyway so the fallback is a playback-path swap.

**D2. One master clock: the audio sample index.** Not wall-clock, not video PTS, not a
per-subsystem clock. See [architecture](architecture.md#the-time-model). Video enters
through exactly one `VideoAnchor` per session.

**D3. `:engine` is pure JVM, enforced by `kotlin("jvm")`.** Not a convention — the Android
SDK is not on the classpath, so `import android.` is a compile error. This is what makes
the two-second test suite, and the two-second test suite is what makes automated gate
checks possible.

**D4. `submit()` is single-threaded and totally ordered.** No locks, no threads, no
randomness in the fold. Replay, evaluation and replay-driven UI become one line of code.

**D5. The coverage state machine lives in `:engine`, owned by the brain lane.** The UI
*renders* `CoverageChanged` and never computes coverage. The original plan gave "coverage
states" to the UI owner and "aligner" to the engine owner — one machine, two owners — and
with agents that becomes two implementations that disagree on stage.

## Speech

**D6. Streaming zipformer transducer on the CPU is the spine.** Whisper is a fixed 30 s
non-streaming window and cannot meet R6's prompt-in-the-pause bar under any tuning. Its only
possible job is a Tier 2 after-stop accuracy pass.

**D7. NPU speech recognition is not a switch, and probably not at all.** AI Hub's Whisper
and Qwen3 exports are **per-chipset QNN context binaries** that do not run through LiteRT.
It is a wholly separate runtime integration, not a configuration change. Premise 7's
"switch recognition to the NPU" was written before this was known.

**D8. sherpa-onnx v1.13.8 minimum, static-link AAR, vendored in `libs/`.** v1.13.7 is
silently broken on SM8850; the JitPack coordinate silently gives you the dynamic variant.
[L1, L2](landmines.md).

**D9. ASR timestamps never cut audio; VAD indices do.** This changed two rules. R3 splits
**coverage, not audio**. Filler splicing happens only on VAD silences with >150 ms of
silence on both sides. [L4](landmines.md).

**D10. Keyword spotting for commands, not the recognizer.** Short commands risk invented
text on a transducer. The KWS model is separate, not gated behind VAD, and its phrase list
is runtime-tunable via `createStream(keywords)`.

## The edit

**D11. Selection and ordering are two separate rules.** Selection: the circled take, else
the latest clean take by close time across all sessions. Ordering: **script line order,
always**. The original wording was ambiguous and the natural reading scrambles the cut —
a pickup of line 1 recorded last would play last.

**D12. A must-say line is never split by the one-breath rule.** A split landing one word off
fails the word-perfect rule on a clean read, on the disclosure line, in front of a juror.
Force the take to close at the must-say boundary instead.

**D13. A must-say take is never spliced for fillers.** Otherwise brief mode ships a
legally-required disclosure the creator never said contiguously — which is exactly what
brief mode claims to prevent.

**D14. Filler splicing applies to flubbed takes only.** The clean-take splice ships behind
`fillerSplicingCleanTakes`, **off on the demo build**. An audible splice inside the juror's
own clean read, in the payoff beat, is Premise 10's worst failure wearing a different hat.

**D15. `experimentalSetMp4EditListTrimEnabled` is banned.** It trims via the MP4 edit list
with no re-transcode — and **the trimmed data is still in the file**. That makes The Vanish
a lie and P3 false, and the infosec juror can open the exported file. The other speedup,
`experimentalSetTrimOptimizationEnabled`, is disqualified by burned-in captions anyway.

**D16. `ConcatenatingMediaSource` is deprecated; use the playlist API plus
`ClippingConfiguration`.** `CompositionPlayer` would be natively right for Architecture A
but is `@ExperimentalApi` and early preview — do not bet the demo on it.

## The NPU

**D17. `CompiledModel.Options(setOf(Accelerator.NPU, Accelerator.NONE))` is the only way to
prove NPU execution.** Every obvious check lies: `getAvailableAccelerators()` reports
registration not health, `isDeviceSupported()` is a string comparison, and asking for NPU
alone silently appends CPU. This is the exact mechanism that made earlier teams in this
series ship on CPU while believing otherwise. [L6](landmines.md).

**D18. Pin the LiteRT AAR and its `.so` files to the same tag, and use `qualcomm_runtime_v81`.**
Never `litert:+`. Google's own example ships `v79`, which is the wrong Hexagon version for
SM8850. Copy the `.so` files into `jniLibs` and skip Play AI Packs entirely.

**D19. The vision signal must do a real job.** Its job is the off-frame note on a take,
which loses to any other clean take in edit-list selection. Without a job the chip reads as
a prop to a CTO, and that is 15% of the rubric.

**D20. R9 reports a per-inference time and a count, never a latency SLA.** "Never zero" was
a promise about hardware we do not control. If the count is zero, the log names the thermal
status that caused it. Same evidence, survives being asked.

## Product and evaluation

**D21. Precision over recall.** A clean read flagged in front of a juror costs more trust
than a missed flub, which the pickup list still catches. Every automatic decision is visible
in words and reversible in one tap.

**D22. Thresholds are tuned on teammates' reads only. Every stranger read is held out and
is the only data the eval card reports.** An eval card graded on its own tuning data is the
first thing an NLP CTO asks about.

**D23. Sample sizes are stated honestly.** 20 clean reads with zero flags supports "under
15%". Claiming "under 5%" needs N ≥ 60. The card reports N and the 95% upper bound.

**D24. A must-say line goes in front of a juror only if 20 consecutive clean must-say reads
by a non-teammate draw zero flags.** Otherwise line 3 is a normal line and must-say is
shown in the wrap report instead.

**D25. Personal calibration ships but is off on the demo phone**, and calibration state
joins the runtime config and model manifest so replay reproduces it. Otherwise "the card
describes this build" stops being true.

**D26. Chennai demos in English.** Hinglish and Tanglish move to the Finale backlog — no
juror in this room scores them.

**D27. Nothing goes on stage unmeasured.** Projected scores, vendor benchmarks and
plausible numbers are planning aids. Only numbers measured on the build they describe reach
a slide.

## Process

**D28. No cut list — one ranking, worked top-down.** Everyone takes the highest-ranked open
item; nobody starts a lower one while a higher one they own is open. The clock decides
where the line falls, rather than a tired person deciding at 16:20.

**D29. Trunk-based, small commits, merge hourly. Trunk must build and pass `:engine:test`
before any merge.** Long branches are how a hackathon loses four hours at 23:00, and a
named integration owner per work block keeps trunk green rather than writing features.

**D30. Automate every gate check that is ledger-observable.** There are ~60 minutes of human
check time in the weekend against 25+ features above Tier 0. Agents add build capacity and
**zero** check minutes. A five-minute human check is five minutes whether one agent or fifty
wrote the feature.

**D31. Pre-event code is allowed** — confirmed with the organizers by phone on Sep 11. The
design doc states the opposite in four places; those statements are superseded. Get one line
of that confirmation in writing so nobody re-litigates it at 02:00.

**D32. The pre-event prototype is an experiment, not a foundation.** It is quarantined in
`experiments/`, out of the Gradle build, and kept as a mining reference. It ships a second
ML runtime stack (MediaPipe + whisper.cpp) that the dependency allowlist and the one-NPU-
runtime rule both reject, so it could not stay in the build.

**D33. Media3's `ACCESS_NETWORK_STATE` and `WAKE_LOCK` are removed, not allowlisted.**
Found by `guardPermissions` on the first merged-manifest run, 2026-09-12: Media3 declares
both (ExoPlayer's bandwidth meter and `setWakeMode`), so an app we call offline shipped a
network permission in its merged manifest — precisely the failure P1 and R17 exist to
catch, and precisely what happened to a team in this series. Both are stripped with
`tools:node="remove"` in `app/src/main/AndroidManifest.xml`. **If Media3 turns out to need
`ACCESS_NETWORK_STATE` at runtime, put it back AND say so on stage — do not silently
allowlist it.** We play local files only, and the screen stays on via
`FLAG_KEEP_SCREEN_ON` rather than a wake lock.

---

## Still open

These are genuinely undecided. If you resolve one, write it down here.

| # | Question | Who decides |
|---|---|---|
| 1 | Can laptops compile during Red Light? | Teach-in. Shapes the whole schedule |
| 2 | Does airplane mode interrupt the event's real-time telemetry logging, and can Office Kit mirror over USB? | Teach-in. **25% of the rubric**, and it is currently scheduled to be answered *after* the demo beat is designed |
| 3 | Does the iQOO 15's camera app ship vivo's teleprompter, and does it follow the voice? | Public reviews answer it in ten minutes. Biggest threat to the novelty line |
| 4 | How many buttons does the Bluetooth selfie remote have, and what keycodes? | Pair it and log. Most selfie remotes have **one** button, and the plan asks it to do **two** things. Handle a set — `VOLUME_UP/DOWN`, `ENTER`, `MEDIA_PLAY_PAUSE`, `CAMERA`, `HEADSETHOOK` — and log unhandled keycodes to the debug panel. Return `true` from `onKeyDown` for volume keys or the system volume panel appears over the viewfinder |
| 5 | What does `:link` pairing actually use? | Ten minutes of decision. The QR-derived key with authenticated control messages is the cheapest defensible answer |
| 6 | Media3 Transformer export throughput at 1080p with burned-in captions on the loaner | Measure, cold and warm |
| 7 | Is airplane mode on during the demo? | Trades a status-bar icon plus a caveat against a possible telemetry cost. The permission list plus delete-on-stage is stronger evidence anyway |
