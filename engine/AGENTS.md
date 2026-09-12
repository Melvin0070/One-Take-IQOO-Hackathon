# :engine — the brain

Pure JVM. **No `android.*`, ever** — enforced by `kotlin("jvm")`, so an `import android.`
is a compile error rather than something a reviewer has to catch.

```bash
./gradlew :engine:test     # ~2 seconds. Run it constantly.
```

## Why this module is the multiplier

There are roughly **sixty minutes of human gate-check time in the entire weekend**, against
25+ features. Agents add build capacity and *zero* check minutes — a five-minute human
check takes five minutes whether one agent or fifty wrote the feature.

Most feature checks are assertions over the ledger, and the ledger lives here. **Every check
you write as a test in this module is a human window handed back to the team.** Write the
acceptance test from `docs/agents/requirements.md` before the code.

## The rules that are load-bearing

1. **No `System.currentTimeMillis()`. No threads. No locks. No randomness.** Inside the
   fold, any of the four breaks R14 (deterministic replay), and none of them will show up
   in a test you wrote. The clock is the audio sample index; that is the only clock.
2. **`submit()` is single-threaded and totally ordered.** One channel, one consumer loop.
3. **Everything is recomputed from the append-only ledger.** Nothing cached across a
   session. That is why a crash cannot corrupt a project.
4. **One `Normalizer`, three modes.** Not three implementations. Same for `MiscueType`:
   this enum is the single source for the plain-word reasons, the eval card's miscue types,
   R4's per-verdict reason and the review screen's display.

## What to build, ranked

See `docs/agents/lanes.md#lane-a`. In short: the fold → the fakes and replay harness →
the normalizer → the aligner → the edit list → golden ledgers.

**Do the fakes before the aligner.** Seven other lanes are blocked on being able to drive
the engine without a phone.

## Where the spec is

- Types, state machine, aligner rules → `docs/agents/contract.md`
- Acceptance tests → `docs/agents/requirements.md` (R2, R3, R4, R5, R13, R14, R18, R20,
  R21, R22, R24 are all yours)
- Why a rule is the way it is → `docs/agents/decisions.md`
- Full text → `One-Take-Contract.md` §4, §5, §6, §7

## Changing a shared type

Allowed, and sometimes right — eight lanes compile against these, so the cost is other
people's compile errors, not yours. Change it, fix the call sites you can see, and put
`BREAKING:` in the commit subject with one line on what moved. Do not add a parallel type
that means the same thing.
