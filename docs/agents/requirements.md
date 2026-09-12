# Requirements — R1 to R25

**This is what "done" means.** Every requirement has an acceptance test, and most of them
are assertions over the ledger, which means they belong in `:engine`'s pure-JVM suite where
they run in two seconds instead of costing five minutes of a gate window.

There are roughly **sixty minutes of human check time in the entire weekend**. Automating a
check is not tidiness; it is the only way features get verified rather than shipped with
their flags off, scoring nothing.

Legend: **[auto]** should be a test. **[human]** needs eyes, ears or hands.

---

## The spine

| ID | Requirement | Acceptance test |
|---|---|---|
| **R1** | Lip sync in the cut stays within one frame (33 ms at 30 fps) across sessions and does not drift over a two-minute take | **[human]** 10 clapped sessions on one loaner: offset spread ≤ 33 ms; a two-minute session's start and end offsets agree within a frame. Under Architecture B this mostly collapses — see [architecture](architecture.md#av-architecture-b) |
| **R2** | Clean reads of lines with numbers, currency, dates and brand names are not flagged | **[auto]** `"iQOO 15"`, `"Snapdragon 8 Elite Gen 5"`, `"₹70k"`, `"2026"`, `"4K at 60 fps"` read cleanly by strangers → **zero flags** |
| **R3** | Reading two lines in one breath covers both | **[auto]** both lines covered, and the cut plays the whole utterance as **ONE** segment with no internal splice. R3 splits **coverage, not audio** ([L4](landmines.md)) |
| **R4** | Similarity is a defined, line-length-aware measure, and every verdict carries a miscue reason | **[auto]** reason accuracy measured on labeled flubs |
| **R5** | Thresholds lean to precision, and the card is not graded on its own tuning data | **[auto]** false-flag rate on **held-out** stranger clean reads at shipped thresholds, with N and its 95% upper bound. **≤ 5% claimable only at N ≥ 60 with zero flags.** Must-say is its own slice. Recall reported |
| **R6** | Prompts land in the pause, or the app degrades honestly | **[human]** the prompt is on screen **before the reader starts the next line** in at least 18 of 20 line ends per condition (quiet, hall noise). Measures the **prompt**, not the pause |
| **R7** | Loud-hall sessions still close takes, or overrides cover it | **[human]** on noisy recordings, takes close at line ends; the remote advances and scratches reliably |
| **R8** | Short lines and commands do not produce invented text; commands work only as standalone utterances | **[auto]** one-word lines verdict correctly; `"scratch that"` alone scratches; **a script line containing "cut" never scratches** |
| **R9** | Every session uses the NPU, and its signal does a real job | **[auto+human]** each session log names model, processor and per-inference time. **The app never disables it; if the count is zero, the log names the thermal status that caused it** |
| **R10** | Multicam is paired, authenticated and encrypted | **[human]** a fourth phone on the same network cannot start, stop or receive a segment |
| **R11** | Models and config are verified and private; release has no debug panel | **[auto]** tampered model rejected at import; config changed in shared storage has no effect; release build shows no debug entry |
| **R12** | The bare loop is small enough to land, with a written fallback | **[human]** installed on all loaners by the deadline, or the fallback runs |
| **R13** | A crash never corrupts a project, and never claims coverage it cannot play | **[auto]** kill mid-session: project opens; at most the current session's video is lost; every take pointing at lost video shows **video missing**, its line falls back to **needed**, and the cut skips it |
| **R14** | Deterministic replay, claimed only as far as it is measured | **[auto]** the same **recorded recognizer output**, script and config give identical ledgers twice. Live recognition is only claimed deterministic once it passes the same test end to end |
| **R15** | Five back-to-back sessions on a warm phone without silent fallback | **[human]** thermal state and processor per model logged across the Tier 0 check |
| **R16** | Tier 0 playback may skip edge fades; export keeps them | **[human]** |
| **R17** | The demo build asks only for permissions it uses | **[auto]** `./gradlew guardPermissions` — the **merged** manifest lists camera, microphone and foreground-service permissions and nothing unexplained; the build fails if a library adds one |
| **R18** | An edited script line cannot keep an old verdict | **[auto]** edit a covered line's text: the line returns to **needed**, its takes are kept and marked "read against an earlier version", and **its line id is unchanged** so a pickup still joins the same cut |
| **R19** | The debug keystore in the repo is explained where a reader will find it | **[human]** the README says what the committed key is, that it signs debug builds only, and that no release artifact is signed with it |

## Added by the Sep 11 engineering review

| ID | Requirement | Acceptance test |
|---|---|---|
| **R20** | A take of an already-covered line joins **that** line, not the current one | **[auto]** read lines 1,2,3 then re-read line 1 with a flub. Assert: line 1 has two takes; **line 3 keeps its clean take and is never marked needed**; the cut plays line 1's first (clean) take. *This is the failure Premise 10 ranks worst* |
| **R21** | The cut is ordered by script line order, never by take-close time | **[auto]** cover line 3, then record a pickup for line 1. Assert the cut plays **line 1 then line 3** |
| **R22** | Verdict latency is logged as three components, not one | **[auto]** every take logs VAD-endpoint, decode-finalize and align separately; the eval card reports all three |
| **R23** | A mid-session audio route change is recorded | **[auto]** connect the Bluetooth remote mid-session; assert an `AudioRouteChanged` event lands in the ledger |
| **R24** | Deleting or reordering script lines is non-destructive | **[auto]** delete a covered line: its takes are kept and marked **orphaned**. Reorder lines: ids unchanged, cut order follows the new positions |
| **R25** | Both frozen demo loaners carry the same build, config version and flag vector | **[human, 10 s]** the engine-stats overlay shows a flag-vector checksum and config version; both loaners show the same two values before the demo |

---

## Privacy promises

These are promises made on stage to an infosec juror. **They are not engineering
preferences and you do not get to trade them away for a feature.**

- **P1.** The single-phone product path makes **no network calls**. The demo runs in
  airplane mode; if the phone is mirrored to a laptop, Wi-Fi is re-enabled by hand for the
  mirror alone **and we say so out loud** rather than implying the radios are off. The
  merged permission list is checked before submission — a team in this series shipped an
  "offline" app whose library had quietly added `INTERNET`.
- **P2.** Raw video, audio, transcripts and ledgers stay in app-private storage until an
  explicit export.
- **P3.** Delete project removes raw files, ledger, transcripts and app-made exports. *This
  is why `experimentalSetMp4EditListTrimEnabled` is banned — it leaves trimmed data in the
  file, and the juror can open it.*
- **P4.** No third-party analytics, ads or crash-reporting SDKs. Enforced by
  `./gradlew guardDependencies`.
- **P5.** Model files and config are verified at import and kept in app-private storage;
  release builds have no debug panel.

---

## Known gaps

Three codepaths have **no test and no handling, and all three fail silently.** Whoever
picks up the owning lane owns the fix.

| Codepath | Failure | Fix | Lane |
|---|---|---|---|
| `:capture` storage | 1080p ≈ 100 MB/min fills the disk mid-session → **silent truncation** | Poll `RecordingStats.getNumBytesRecorded()`, threshold stop, truncated marker | E |
| `:asr` recognizer silent | Model failed to load; VAD says speech, no finals ever arrive | "Not hearing you" chip after 4 s | C |
| `:media` playback before finalize | Media source built before the MP4 is finalized → **black frame** | Gate on `VideoRecordEvent.Finalize`, show "preparing" | F |

Two more that are handled but worth knowing: deleting a project while an export holds
handles (cancel the export first), and OriginOS killing the `mediaProcessing` service
(keep export foreground-only with visible progress).

---

## The Tier 0 check

Five back-to-back sessions of a three-line script with one deliberate flub each. Assert:

1. every flub prompts at its line-end pause
2. **no clean line prompts** — this is the one that matters most
3. Safe to wrap appears only at full coverage
4. playback starts on stop with **no render step**
5. nothing crashes

**Most of that is assertions over the ledger.** Write it as an instrumented test driven by
a recorded audio fixture — played through the device speaker, or injected at the
`Recognizer` seam. Then do the same for every feature check that is ledger-observable: R20,
R21, #13 scratch-that, #14 must-say, #16 free-talk, #18 circle/undo.

**Human-only, and therefore budgeted explicitly:** audible splice artifacts, caption
legibility, the remote from 1 m, the juror beat end to end, off-frame behaviour, thermal
after five sessions.
