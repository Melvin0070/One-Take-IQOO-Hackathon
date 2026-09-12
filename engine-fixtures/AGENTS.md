# :engine-fixtures — fakes, corpus, golden ledgers

Pure JVM. Depends on `:engine` and nothing else.

**This module unblocks seven other lanes, so build it early.** Everyone else needs to drive
the engine without a phone: the UI lane builds screens against replayed ledger events
before the aligner exists, `:eval` runs the corpus, and every acceptance test needs a
deterministic recognizer.

## What goes here

- **`FakeRecognizer`** — replays a text file as `RecognizerResult`s at chosen sample
  indices. This is the seam where recorded sessions re-enter the system, so it is also the
  seam R14's replay test runs through.
- **`FakeVad`** — emits `SpeechStart`/`SpeechEnd` at chosen sample indices.
- **`FakeVision`** — emits `VisionFrame`s, including the face-leaves-frame case.
- **Corpus loader** — reads recorded sessions (audio + recognizer output + labels) off disk.
- **Golden ledgers** — a ledger from a known session, checked in. Assert replay reproduces
  it exactly. That is R14's test, and it costs nothing once the fakes exist.
- **Script builders** — terse helpers so a test reads as the scenario it describes, not as
  twenty lines of construction.

## The one property that matters

A test written against these fakes must be **completely deterministic**. Same inputs, same
ledger, byte for byte, every run. If a test here is ever flaky, that is not a flaky test —
it is a real non-determinism in `:engine` that would have broken replay on stage, and it is
the most valuable bug this module will ever find.

## Held-out data

The corpus is split, and the split is not cosmetic. **Thresholds are tuned on teammates'
reads. Stranger reads are held out and are the only data the eval card reports.** Keep the
loader honest about which is which — an eval card graded on its own tuning data is the
first thing an NLP CTO asks about.
