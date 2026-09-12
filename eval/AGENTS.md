# :eval — the evidence

A pure-JVM CLI. Corpus in, eval card out, **one documented command, under ten minutes**.

```bash
./gradlew :eval:run --args="--corpus corpus/ --out eval-card.md"
```

## What the card has to say

This card goes in front of a CTO who evaluates models for a living. Everything on it is
measured on the build it names.

1. **Held-out stranger reads only.** Never the tuning data.
2. **N and the 95% upper bound.** 20 clean reads with zero flags supports "under 15%".
   Claiming "under 5%" needs N ≥ 60. State it that way — the honesty is worth more than
   the number.
3. **Must-say as its own slice.** A word-perfect rule has a different false-flag profile,
   and it is the slice that decides whether a must-say line goes in front of a juror.
4. **Recall alongside precision.** Precision-first is a choice; hiding recall is a tell.
5. **The three latencies separately** (R22) — VAD-endpoint, decode-finalize, align. A single
   number cannot be tuned.
6. **The naive baseline.** The raw transcript compared with the script at one fixed
   word-error threshold. **This is the single line that shows what the aligner earns** —
   without it the card shows absolute numbers a CTO cannot calibrate. It is ~20 lines and
   runs on the same corpus, so the marginal cost is near zero.
7. **Build id, config version and flag-vector checksum.** The card describes one build. Say
   which.

## What not to do

- Do not report a number from a run against a different build or config than the one named.
- Do not round a bound in your favour.
- Do not drop the sample size because it is small. A small N stated honestly is evidence; a
  small N hidden is the thing that loses the room.
