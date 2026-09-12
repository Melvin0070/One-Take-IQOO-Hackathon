# TODOS

Created by `/plan-eng-review` on 2026-09-11. Items surfaced during the review that are
real work but sit outside the immediate build. Not a backlog dump: each has enough context
to act on cold.

---

## 1. Get the pre-event-code confirmation in writing

**What:** One WhatsApp or email line from whoever at the organizers confirmed by phone on
Sep 11 that pre-event code is allowed.

**Why:** `One-Take-Design-v3.md` states the strict "no app code before Sat 11:00" position
in four places (§15 header, Appendix A build-rules table, Open Question 20, §9 agenda item
5). A teammate reading the doc at 02:00 will find four statements contradicting what the
team is actually doing. One written line collapses that to a single source of truth, and it
is the only defence if the question is raised at submission.

**Depends on:** whoever made the call.

---

## 2. Update the doc's four "no pre-event code" statements

**What:** Edit §15's header, Appendix A's build-rules table row, Open Question 20 and §9
agenda item 5 to reflect the closed question.

**Why:** §9 agenda item 5 currently tells eng review to write the contract "as prose and
tables, not Kotlin" specifically because typing pre-written code was a gray area. That
constraint is gone, so the contract can carry real signatures — which
`One-Take-Contract.md` now does. Leaving the old wording in place means someone will
follow it.

**Depends on:** TODO 1.

---

## 3. Confirm the Bluetooth selfie remote's button count and keycodes

**What:** Pair the remote with a phone, log every keycode it emits, and check whether it has
one button or two.

**Why:** Tier 0 item 6 asks the remote to do **two** things (advance and scratch), and the
§11 kit list names a single "Bluetooth selfie remote". Most selfie remotes have one button.
If it is one button, the mapping has to be short-press = scratch (the demo beat), long-press
= advance, and that needs deciding before it is discovered on Saturday.

**Also:** handle a set of keycodes rather than one — `VOLUME_UP`, `VOLUME_DOWN`, `ENTER`,
`MEDIA_PLAY_PAUSE`, `CAMERA`, `HEADSETHOOK` — and log any unhandled keycode to the debug
panel so an unknown remote can be supported in thirty seconds on Saturday. Return `true`
from `onKeyDown` for volume keys or the system volume panel appears over the viewfinder.

**Context:** Open Question 16 asks whether the remote pairs and arrives as a volume-key
event; it does not ask how many buttons it has.

---

## 4. Decide the Office Kit mirroring path before rehearsing the airplane-mode beat

**What:** Open Question 22, unresolved. Does Office Kit mirror over a USB cable rather than
Wi-Fi, and does airplane mode interrupt HackTracker's real-time logging?

**Why:** The §11 beat says "airplane mode is on" while Wi-Fi carries the mirror and
Bluetooth carries the remote. §7.2 already handles this honestly in words. But if
HackTracker logs in real time and airplane mode interrupts it, the demo costs telemetry on
25% of the rubric (creative phone use plus Office Kit are both HackTracker-scored). That is
a scoring decision, not a privacy one, and it has to be made before the beat is rehearsed
twice on Sunday.

**Depends on:** teach-in.

---

## 5. Media3 Transformer export throughput on the loaner

**What:** Measure export time per filmed minute for 1080p with burned-in captions, cold and
after ten minutes of recording.

**Why:** §15 item 6 schedules this on your own phone. Appendix B's only data point is
Google's benchmark of 10 s of 720p in about 1.3 s on a Pixel 9 Pro XL **with no overlays**.
Captions are an overlay and 1080p is not 720p, so the real number could be several times
that. §14 and the Success Criteria both quote "export time per filmed minute" as a measured
number, and the pitch promises no render bar for *playback* but says nothing about export.
If export is slow, the honest fix is to say so on the card rather than discover it during
Eval 2.

**Depends on:** loaner access, Saturday.

---

## 6. Decide what `:link` multicam pairing actually uses

**What:** R10 lists three options on the table (QR session key with signed control and
encrypted uploads; TLS with a pinned fingerprint from the QR; a platform nearby-connections
API). Pick one.

**Why:** Multicam is ranked last, so this may never be built at Chennai. But R10 is the
infosec juror's question if multicam appears on any slide, and "we would use X" is a much
better answer than "we would need to decide". The QR-derived key with authenticated control
messages is the cheapest defensible answer and the one that most directly satisfies "a
fourth phone can't start, stop or receive a segment".

**Depends on:** nothing. Ten minutes of decision, valuable even if the code never ships.

---

## 7. Write the naive baseline for the eval card

**What:** §7.3 says the card "puts a naive baseline beside the aligner (the raw transcript
compared with the script at one fixed word-error threshold)". That baseline is a separate
code path in `:eval` and nobody owns it.

**Why:** It is the single line on the card that shows what the aligner *earns*. Without it
the card shows absolute numbers a CTO cannot calibrate. It is maybe twenty lines in the
pure-JVM `:eval` module and it runs on the same corpus, so the marginal cost is near zero
once `:eval` exists.

**Depends on:** `:eval` module (Lane B).
