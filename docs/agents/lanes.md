# Lanes

Eight lanes, one per module group. **They exist so that agents do not conflict:** no two
lanes share a file. Launch them in parallel; the only serial step in the whole build was
freezing the `:engine` interfaces, and that is done.

**How to pick work:** take the highest-ranked open item in your lane. Nobody starts a
lower-ranked item while a higher one they own is open. There is no cut list — one ranking,
worked top-down, and the clock decides where the line falls.

| Lane | Module | Depends on | Human |
|---|---|---|---|
| [A](#lane-a--the-brain) | `:engine` + `:engine-fixtures` | nothing | T2 |
| [B](#lane-b--the-evidence) | `:eval` | Lane A's types | T2 |
| [C](#lane-c--speech) | `:asr` | Lane A's `Recognizer` / `VoiceActivity` | T2 |
| [D](#lane-d--the-npu) | `:npu` | Lane A's `VisionSignal` | T1 |
| [E](#lane-e--capture) | `:capture` | Lane A's `AudioFrame` / `VideoAnchor` | T1 |
| [F](#lane-f--media) | `:media` | Lane A's `EditList` | T1 |
| [G](#lane-g--the-product) | `:app` | `:engine-fixtures` replay only | T3 |
| [H](#lane-h--multicam) | `:link` | Lane A's types | T3 |

**Two known cross-lane hazards.** Lanes A and B both touch `:engine` types — B consumes, A
defines. Lane G and Lane A both want to define how a `MiscueType` is worded: the enum is
A's, the presentation map is G's. One enum, one map, not four lists.

---

## Lane A — the brain

`:engine`, `:engine-fixtures`. Pure JVM, no Android, ~2 s test suite.

**This lane is the multiplier.** Most gate checks are assertions over the ledger, and the
ledger lives here. Every check you can write as a `:engine` test is a five-minute human
window you get back — and there are only about sixty of those minutes in the whole weekend.
Write the checks as you write the features.

Ranked:

1. **The fold.** `CoverageEngine.fold()` — [Contract §6](../../One-Take-Contract.md)'s
   table, exactly. Start from `CoverageState.initial(script)`. Pure: no I/O, no clock, no
   randomness, or R14 is not claimable.
2. **`FakeRecognizer`, `FakeVad`, `FakeVision`, the replay harness.** Do these *before* the
   aligner, not after. Every other lane is blocked on being able to drive the engine
   without a phone, and the design doc's original ordering put this after the corpus
   existed — which blocks all seven other lanes on speech work.
3. **The normalizer.** One class, three modes. R2's acceptance test is its spec: strangers
   read `"iQOO 15"`, `"Snapdragon 8 Elite Gen 5"`, `"₹70k"`, `"2026"`, `"4K at 60 fps"`
   cleanly and get **zero flags**.
4. **The aligner.** [Contract §7](../../One-Take-Contract.md) plus §10's aligner rules.
   Joining, closing, verdicts with a `MiscueType`, implicit and explicit scratches.
   Acceptance tests that matter most: **R20** (a re-read of a covered line joins *that*
   line) and **R21** (the cut is ordered by script line order, never by take-close time).
5. **The edit list.** Selection then ordering — two rules, not one.
6. **Golden ledgers.** Check in a ledger from a known session; assert replay reproduces it
   byte for byte. That is R14's test and it costs nothing after step 2.

**Definition of done for each:** the R-number's acceptance test passes as a `:engine` test,
and `./gradlew :engine:test` is green.

## Lane B — the evidence

`:eval`. A JVM CLI: corpus in, eval card out, one command, under ten minutes.

1. **The card itself.** Held-out stranger reads only. Report **N and the 95% upper bound** —
   "under 5%" is claimable only at N ≥ 60 with zero flags; 20 clean reads with zero flags
   only supports "under 15%". Must-say is reported as its own slice. Recall is reported
   alongside precision.
2. **The naive baseline.** The raw transcript compared with the script at one fixed
   word-error threshold. **This is the single line on the card that shows what the aligner
   earns** — without it the card shows absolute numbers a CTO cannot calibrate. Maybe
   twenty lines, same corpus, near-zero marginal cost.
3. **R22's three latencies** — VAD-endpoint, decode-finalize, align — reported separately.
   A single number cannot be tuned, and the Red blocks exist to tune it.
4. The card prints the **build id, config version and flag-vector checksum** it describes.

**The card must never be graded on its own tuning data.** Thresholds are tuned on
teammates' reads. Stranger reads are held out and are the only data the card reports. This
is the first thing an NLP CTO asks about.

## Lane C — speech

`:asr`. sherpa-onnx **CPU** build. **Read [landmines](landmines.md) L1–L5 and L15 first** —
this module has more silent-failure modes than the rest of the repo combined.

1. **Vendor the static-link AAR** and smoke-test streaming ASR on the loaner inside the
   first 30 minutes. L1 is a product-killer and it is silent.
2. **Silero VAD** behind `VoiceActivity`. Raise `maxSpeechDuration`. `pop()` promptly (L3
   exits the process).
3. **Streaming zipformer** behind `Recognizer`. Count samples yourself — the binding drops
   absolute time (L5).
4. **Keyword spotting** behind `CommandSpotter` for "scratch that". Not gated behind VAD.
   Runtime keywords via `createStream(keywords)` so Red Light can tune the phrase list
   without a rebuild.
5. **Measure RTF on-device** and report it. Nobody has published this number and the whole
   UX depends on it.
6. **A "not hearing you" signal after 4 s of VAD-says-speech-but-no-finals.** This is one
   of three known critical gaps — today a dead recognizer is completely silent.

## Lane D — the NPU

`:npu`. LiteRT + Qualcomm accelerator. The **only** module that links QNN.
**Read [landmines](landmines.md) L6–L8 first.**

1. **Prove it ran on the NPU.** The `Accelerator.NONE` trick is the only route, and it is
   undocumented — confirm it on-device. A startup self-test that fails **loudly** to a red
   banner. Log `create` success, measured `model.run()` time and `Build.SOC_MODEL` per
   session.
2. **Face-Det-Lite behind `VisionSignal`.** `[1, 480, 640, 1]` uint8 **grayscale**, not RGB.
3. **The signal must do a real job**, or the chip reads as a prop to a CTO. Its job is the
   off-frame note on a take (`Segment` selection loses to any other clean take). That is
   R9's whole argument.
4. **Engine-stats overlay data**: model, processor, µs per inference, verdict latency,
   thermal state, flag-vector checksum, config version. This goes on stage.
5. Thermal listener. `SUSTAINED_HIGH_PERFORMANCE`, not `BURST`.

**Never label a CPU fallback "NPU".** Different `CompiledModel` instance, visibly different
label. That is the failure this lane exists to prevent.

## Lane E — capture

`:capture`. CameraX, AudioRecord, foreground service, WAV, the timebase anchor.
**Read [landmines](landmines.md) L9, L12, L13 first.**

1. **The concurrent-capture probe.** Thirty minutes, before any feature code. Both start
   orders, all three assertions, check the buffers directly. This decides whether lip sync
   is free or engineered.
2. **Architecture B**: CameraX with `withAudioEnabled()` muxes its own audio; the MP4 *is*
   the cut source. **Write the WAV unconditionally anyway** so the fallback is a
   playback-path swap, not a rewrite at hour 20.
3. **The foreground service.** Started while an activity is visible, or it is a
   `SecurityException` (L12).
4. **`VideoAnchor`** — emitted exactly once per session. The only place video time enters
   the engine.
5. **The storage guard.** Poll `RecordingStats.getNumBytesRecorded()`, stop at a threshold,
   mark the session truncated. Today a full disk truncates **silently** — critical gap.
6. **`audio-route-changed` into the ledger** (R23). The remote connecting moves the mic.

## Lane F — media

`:media`. Media3 playback from the edit list, Transformer export, captions.
**Read [landmines](landmines.md) L10, L11 first — several obvious APIs are deprecated or
disqualified.**

1. **Playback from the edit list.** Playlist API + `ClippingConfiguration`. Snap in-points
   to keyframes where the pause allows; pre-warm before the Wrap tap. **Measure
   `stop-to-first-frame` and log it** — that number is the "no render bar" claim.
2. **Gate playback on `VideoRecordEvent.Finalize`.** Building the media source before the
   MP4 is finalized gives a black frame. Critical gap; show "preparing".
3. **Export** with burned-in captions via `OverlayEffect` + `TextOverlay`. Foreground-only,
   visible progress, no background promise. **Never `experimentalSetMp4EditListTrimEnabled`.**
4. **Measure export time per filmed minute**, cold and after ten minutes of recording. No
   published number exists for 1080p with overlays. If it is slow, say so on the card.

## Lane G — the product

`:app`. Compose, every screen, the debug panel, DI, ATTRIBUTION.

**You are not blocked on speech.** Build every screen against replayed ledger events from
`:engine-fixtures` — that is what `debugImplementation(project(":engine-fixtures"))` is
for. Wire to the real engine later; the interface is identical.

Screens, ranked: **Capture** (the strip under the lens, coverage count, Record→Wrap) →
**Script** (paste) → **Review** (cut player, lined script, take reasons, pickups) →
**Projects** → **Debug panel** → **Wrap report** → **Export**.

Non-negotiable UX rules, from §5.4:

- **Eyes on the lens.** Everything glanceable from 0.5–1.5 m. No dialogs while recording.
- **Never interrupt speech.** Prompts appear only in the pause after a line.
- **Show your work.** Every flag gives its reason **in words** — "missed 'every Sunday'",
  "restarted", "said 'seventy' for 'seven'". Never "similarity", "confidence" or
  "utterance". See [glossary.md](glossary.md).
- **Never colour alone.** Covered / needed / pending each get an icon *and* a word.
- **Mirrors well.** Capture and review stay legible mirrored to a laptop over Office Kit.
- **The UI renders `CoverageChanged`. It never computes coverage.** That machine is Lane
  A's, and two implementations will disagree on stage.

**The debug panel is a Tier 0 item, not a nicety** — it is how Red Light tunes thresholds
without a rebuild, and it carries the **flag-vector checksum and config version** that R25
requires both demo loaners to show.

## Lane H — multicam

`:link`. Tier 2, ranked last **on cost**, not on value.

The one thing worth doing even if the code never ships: **decide what pairing actually
uses.** Three options are on the table — a QR session key with signed control and encrypted
uploads; TLS with a fingerprint pinned from the QR; a platform nearby-connections API. The
QR-derived key with authenticated control messages is the cheapest defensible answer and
the one that most directly satisfies R10: *a fourth phone can't start, stop or receive a
segment.* Ten minutes of decision, and it is the infosec juror's question the moment
multicam appears on any slide.

**`:link` needs `INTERNET`, which the single-phone build does not have.** If multicam
ships, it ships as a separate build flavour and the stage line changes accordingly. Do not
add `INTERNET` to the demo build's manifest.

---

## Tier ranking

Full list and pass criteria: [`One-Take-Design-v3.md` §10 "Tiers"](../../One-Take-Design-v3.md).

**Tier 0 — the spine.** Capture service · VAD + recognition · script mode with coverage on
the strip · aligner + ledger + prompts · playback on stop · overrides · NPU vision + engine
stats · review with lined script and pickups · heat/battery · export · delete project.

**Tier 1** — why flagged · "scratch that" · must-say + wrap report · filler marks ·
free-talk · reframe · circle/undo · setup coach chips.

**Tier 2** — talking-points mode · multicam · caption accuracy pass.

**Tier 3 / Finale backlog** — Hinglish + Tanglish · caption presets · cutaways · NLE
handoff · personal calibration · on-device LLM features · script check · brief mode.

Three items were **re-scored out of Tier 3** on the honest criterion "does this help win
Chennai": **#28 personal calibration → high Tier 1** (evaluators in this series asked "how
is the agent self-learning?" verbatim, and thresholds are already runtime config, so
per-creator offsets are cheap), **#35 script check → Tier 1** (splitting over-long lines
improves paste, the first thing a juror sees), **#36 brief-mode report → Tier 2** (the VC
is one of three jurors in the room).
