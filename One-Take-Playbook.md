# One-Take Build Playbook

Written by `/plan-eng-review` on 2026-09-11 for the iQOO City Battles Chennai build,
Sep 12-13 2026. Read with `One-Take-Contract.md` (what to build) and
`One-Take-Design-v3.md` (why).

This is the operational document: who does what, in what order, with what gates. It
assumes the two constraint changes confirmed on Sep 11 — **pre-event code is allowed**
(Open Question 20, closed by phone) and **AI coding agents are effectively unlimited**.

---

## 0. What actually constrains you now

The design doc was written for scarcity of engineering hours. That is no longer the
binding constraint. These four are, and **no number of agents expands any of them**:

| Constraint | Size | Why agents don't help |
|---|---|---|
| **Gate-check minutes** | ~120 min total across three windows (18:15-18:45, 01:00-02:00, 08:30-09:00), minus 30 for the Tier 0 check and ~30 for re-runs = **~60 min for feature checks** | A 5-minute human-run check is 5 minutes whether one agent or fifty wrote the feature |
| **Loaner-device hours** | Zero until Sat 08:00, then three phones shared by three people | You cannot parallelize one phone |
| **Human ears for labeling** | 180+ takes to record, listen to and label by miscue type | Ground truth is human by definition |
| **Three people's review attention** | Three humans reviewing N agents | The real ceiling on parallel agent output |

**The single highest-leverage response: automate the gate checks.** Most feature checks
are assertions over the ledger, and the ledger lives in pure-JVM `:engine`. Write them as
instrumented tests that run unattended on a loaner. That converts human check-minutes into
machine minutes, and it is the only way 36 features get verified rather than shipped
flags-off scoring nothing. Treat the check harness as a Tier 0 item.

---

## 1. Friday night (Sep 11) — order matters

**Serial step first, everything else parallel behind it.**

### Step 0 (serial, ~1 hour, humans + one agent). Freeze the interfaces.

Type §4 of `One-Take-Contract.md` into `:engine` — roughly 120 lines of interfaces, sealed
types and data classes. **Nothing else starts until these compile.** They are the seams
that let eight lanes run without conflict.

### Step 1 (commit #1, before any feature code). The guards.

Outside-voice finding 11: many agents adding dependencies freely is exactly the mechanism
that breaks R17, P4 and ATTRIBUTION. These land first or they never land.

- [ ] **Manifest-diff check.** A Gradle task that dumps the merged manifest's permission
      list and fails the build if it differs from a checked-in allowlist. R17's acceptance
      test, automated. The doc's own cautionary tale is a Pune team whose library silently
      added `INTERNET`.
- [ ] **Dependency allowlist.** A Gradle task that fails on any dependency not in a
      checked-in list. P4 (no analytics, ads or crash SDKs) becomes enforced rather than
      promised.
- [ ] **Generated ATTRIBUTION.** A task that regenerates ATTRIBUTION from the resolved
      dependency graph with licenses. Event rule compliance, automated.
- [ ] `abiFilters "arm64-v8a"`, `org.gradle.parallel`, `org.gradle.caching`,
      configuration cache.
- [ ] Debug keystore committed, with the R19 README paragraph explaining why.

### Step 2 (parallel, agents). Lanes A-H.

Per `One-Take-Contract.md` §10. Launch all eight the moment Step 0 compiles.

**Invert the doc's ordering per outside-voice finding 6:** the ledger, event schema,
`FakeRecognizer` and the replay harness are the **Friday-night** deliverable, before any
feature. The doc schedules the replay harness for Saturday after the corpus exists, which
means every UI lane is blocked on speech work that hasn't happened.

### Step 3 (parallel, humans). The four things agents cannot do.

These are the Friday critical path. Split three humans across them.

| # | Probe | Why tonight | Decides |
|---|---|---|---|
| **P1** | **Concurrent audio capture.** Can one app run CameraX `Recorder` with audio enabled AND its own `AudioRecord` at the same time on your Snapdragon phone? Record 60 s, check both streams have real audio. | Highest-value 20 minutes of the weekend | Whether lip sync is free (clip the MP4) or must be engineered to 33 ms. See §2 below |
| **P2** | **NPU reality.** Load Face-Det-Lite through LiteRT with `Accelerator.NPU`. Log the accelerator actually used and per-inference microseconds. Compare against a forced-CPU run. | Earlier teams in this series failed here and shipped on CPU | R9, and 15% of the rubric |
| **P3** | **Two pause distributions.** Record the corpus; report within-line and between-line pause distributions **separately**, not one median. | Sets every threshold in the aligner | R6, the line-end pause, the hold limit |
| **P4** | **Stranger reads, held out.** Target 60+ clean and 30+ flubbed, including 20 consecutive clean reads of a 15-word must-say disclosure. Recorded, set aside unheard. | Outside-voice finding 4: the Sunday 01:00-06:30 slot has no strangers in it. If you do not get these tonight, the eval card reports a weak bound and must-say never reaches the stage | §7.3's card, and whether line 3 is a must-say line |

**P4 is the one most likely to be skipped and most expensive to skip.** §7.3's rule of
three: 20 clean reads with zero flags only supports "under 15%". Claiming "under 5%" needs
60+. WhatsApp voice notes from 15 people is a valid collection method.

### Step 4 (10 minutes, anyone). Close OQ21.

Does the iQOO 15's camera app ship vivo's teleprompter, and does it follow the voice? It is
assigned to check-in, which is after the pitch is written, and it is the single biggest
threat to the 20% novelty line. Public reviews answer it tonight.

---

## 2. The A/V fork — SETTLED by research Sep 11

**Build Architecture B. Probe first, but expect B. Capture A's data unconditionally so the
fallback is a playback-path swap, not a rewrite at hour 20.**

Android's own camera docs settle it: when camera buffers go **directly to the encoder**, the
platform compensates for the audio/camera timebase difference automatically. When your app
takes the buffers and encodes them later, that compensation is gone and you own the sync.
Let CameraX mux its own audio.

```
B (primary)   CameraX + withAudioEnabled() ─▶ MP4 with synced audio ─▶ clip it, that IS the cut
              AudioRecord(VOICE_RECOGNITION) ─▶ recognizer + a WAV kept anyway
              sync tolerance: HUNDREDS of ms, because cut boundaries land in pauses

A (fallback)  CameraX audio disabled ─▶ video-only MP4
              AudioRecord ─▶ recognizer AND the cut's audio
              sync tolerance: 33 ms, and CameraX gives you NO wall-clock anchor for MP4 t=0
```

Neither is documented-safe, but the failure modes are asymmetric: **B fails loudly and
detectably in code; A fails as a silent constant offset you find on stage.** Full reasoning,
both holes, and the clock-pairing table if you land in A: `One-Take-Contract.md` §3.

### P1, restated with exact assertions — 30 minutes, before any feature work

Start CameraX with `withAudioEnabled()`, start a `VOICE_RECOGNITION` `AudioRecord`, run 10
seconds. Assert **all three**:

1. `recordingStats.audioStats.audioState == AUDIO_STATE_ACTIVE` and `getAudioAmplitude() > 0`
2. `AudioRecord` buffers are **not all zero** — check directly
3. `AudioRecordingConfiguration.isClientSilenced() == false`

**Run both start orders.** The CDD's tiebreak for equal-priority capturers is "most recently
started wins"; if OriginOS reuses that path for same-app clients, order decides who gets
audio.

**Do not trust the callback alone.** `registerAudioRecordingCallback()` must be registered
*before* `startRecording()`, and it fires "only when the app is receiving audio and a change
occurs" — silence from frame zero may never fire it. Check the buffers.

Multicam (Tier 2) needs cross-device sync regardless, so the clap rig survives there either
way. This fork is the single-phone path only.

### Two more findings from the same research, both demo-relevant

**The instant cut has a keyframe problem.** Clipping a segment whose start is not on a
keyframe forces the player to "decode and discard data from the previous keyframe up to the
start position" — at the first frame **and at every segment boundary**. Your "no render bar"
promise is about `stop-to-first-frame`, and this is the thing that threatens it. Snap
in-points to keyframes where the pause allows, pre-warm the player before the Wrap tap, and
measure the real number rather than assuming 300 ms.

**Do not use `experimentalSetMp4EditListTrimEnabled` as an export speedup.** It trims via the
MP4 edit list with no re-transcode, and it carries a documented privacy consequence: **the
trimmed data is still in the file.** That would make The Vanish a lie and P3 false, and the
infosec juror can open the exported file. The other speedup,
`experimentalSetTrimOptimizationEnabled`, is disqualified by burned-in captions anyway.

## 3. Saturday

### 08:00-10:00 — bring-up harness, first 30 minutes on the loaner

Outside-voice finding 3: you are building blind against a device you first touch at 08:00.
Buy device characterization, not features, with that first half hour. Write a single
debug-panel screen that reports, in one place:

- [ ] `SENSOR_INFO_TIMESTAMP_SOURCE` value
- [ ] Whether concurrent capture works on THIS device (re-run P1)
- [ ] Which accelerator LiteRT actually chose, and per-inference microseconds
- [ ] `getCurrentThermalStatus()` and battery
- [ ] Whether the Bluetooth remote pairs and what keycode it emits
- [ ] Free storage, and estimated recordable minutes
- [ ] Whether a `mediaProcessing` foreground service survives (OQ14)
- [ ] The flag vector checksum and config version (see §5)

Every one of these is a Saturday surprise you would otherwise find at 23:00.

### 10:00-11:00 teach-in — ask in this order

1. **OQ1** — can the laptop compile during Red Light, driven through Office Kit? Sets the
   shape of the whole schedule.
2. **OQ22** — does airplane mode interrupt HackTracker's real-time logging, and can Office
   Kit mirror over USB? This is 25% of the rubric and it is currently scheduled to be
   answered *after* the demo beat is designed.
3. **OQ12** — may teams code during evaluation rounds? Exact submission cutoff?
4. **OQ15** — minutes per team at Eval 1 and 2, does the full panel see each team, may you
   mirror to your own laptop at the table?
5. **OQ2** — pre-converted models.

### Working order

Everyone takes the highest-ranked open item. Nobody starts a lower-ranked item while a
higher one they own is open. **No cut list** — one ranking, worked top-down, the clock
decides where the line falls.

### Integration policy (new — the doc has none)

Outside-voice finding 6: Red Light is a merge-debt accumulator. Agents keep producing
unbuilt, unreviewed diffs that land as a mountain when Green opens.

- **Named integration owner per Green block**, rotating. Their job is merging and keeping
  trunk green, not features.
- **Trunk-based, small commits, no long branches.** Every lane merges to trunk at least
  hourly during Green.
- **Trunk must build and pass `:engine` tests before any merge.** Two-second tests make
  this cheap; that is why `:engine` is pure JVM.
- **During Red, agents keep working but their output queues.** The integration owner drains
  the queue in the first 20 minutes of the next Green block, before new feature work.

---

## 4. Gate checks

### Automated (write these first, they are the multiplier)

The Tier 0 check is five back-to-back sessions of a three-line script with one deliberate
flub each, asserting: every flub prompts at its line-end pause, no clean line prompts,
Safe to wrap appears only at full coverage, playback starts on stop with no render step,
nothing crashes. **Most of that is assertions over the ledger.** Write it as an
instrumented test driven by a recorded audio fixture played through the device speaker or
injected at the `Recognizer` seam.

Do the same for every feature check that is ledger-observable: R20, R21, #13 scratch-that,
#14 must-say, #16 free-talk, #18 circle/undo.

### Human-only (budget these explicitly, they are the scarce resource)

Anything needing eyes, ears or hands: audible splice artifacts, caption legibility, the
remote from 1 m, the juror beat end to end, off-frame behaviour, thermal after five
sessions.

### Windows

18:15-18:45 (Tier 0 items 1-5 must pass), 01:00-02:00 (Tier 1), 08:30-09:00 (Tier 2, 30-min
cap). A flag turns on only when its own check passes **and** Tier 0 still passes. Anything
unverified stays off.

---

## 5. Demo reproducibility

Outside-voice finding 7: 25 feature flags plus separately-imported config means nobody can
reproduce the demo, and the "second frozen loaner runs the same build" fallback checks the
APK but not the flag vector or thresholds.

- [ ] The debug panel and the engine-stats overlay both show a **flag-vector checksum** and
      the **config version**.
- [ ] Before the demo, both frozen loaners are checked to show the **same two values**. Ten
      seconds, prevents the worst avoidable failure of the weekend.
- [ ] The eval card prints the build id, config version and flag-vector checksum it
      describes (§7.3 requires build *and* config; the checksum makes it checkable).

---

## 6. Demo-risk decisions already made

| Decision | Ruling | Why |
|---|---|---|
| **#15 filler splicing** | Flubbed takes only. The clean-take splice ships behind a flag, **off on the demo build** | An audible splice inside the juror's own clean read, in the payoff beat, is Premise 10's worst failure wearing a different hat |
| **Must-say on stage** | Only if 20 consecutive clean must-say reads by a non-teammate draw zero flags — and those reads must happen **Friday**, not in the Sunday 01:00-06:30 window where no strangers exist | Otherwise the gate cannot be run and the decision is made by default at 08:30 Sunday |
| **#28 calibration** | Ships, but calibration state joins the runtime config and model manifest (so R14 replay reproduces it), and is **off on the demo phone** (so §7.3's "the card describes this build" stays true) | Tier 3 items were never checked against Tier 0's guarantees |
| **Must-say never split** | R3's one-breath split never applies to a must-say line | A split one word off fails the word-perfect rule on a clean read |
| **Airplane mode** | **Open — your call, and it is the top teach-in question after OQ1** | It delivers a status-bar icon plus a caveat you must explain, against a possible 25% telemetry cost. R17's permission list plus delete-on-stage is stronger evidence anyway |

---

## 7. Fallback ladder for the pitch

Already in §11; restated as a ladder so it can be executed under pressure.

1. Juror reads cleanly → invite "scratch that" and a re-read. (Needs #13. If #13 is off,
   tap-to-scratch.)
2. Hall too loud for pause detection → the Bluetooth remote.
3. Remote fails → tap override.
4. Demo phone fails → second frozen loaner, same build, **same flag checksum**.
5. Both fail → play Sunday morning's backup recording and say plainly that it is a
   recording.

Rehearse 1, 2 and 4 at least once. Nobody rehearses fallbacks and everybody needs them.

---

## 8. Sleep

Staggered, one at a time, in the Sun 01:00-06:30 Red block: T1 02:00-03:30, T2 03:30-05:00,
T3 05:00-06:30. **This is 1.5 hours each.** Three people on 1.5 hours of sleep, directing
agents and reviewing output, is the real reason to automate the checks: at 04:00 the human
in the chair is the weakest link in the system, and a green test suite is worth more than
a careful reviewer.
