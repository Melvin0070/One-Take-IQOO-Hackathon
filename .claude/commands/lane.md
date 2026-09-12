---
description: Pick up a lane and work its highest-ranked open item
argument-hint: "[A-H or module name, e.g. C or :asr]"
---

Work lane **$1** of the One-Take build.

Before writing anything:

1. Read `docs/agents/lanes.md` and find lane $1 — its module, its ranked items, and the
   acceptance tests that define done.
2. Read that module's `AGENTS.md`.
3. Read the sections of `docs/agents/landmines.md` that its `AGENTS.md` names. These are
   verified traps that fail silently or at runtime; skipping them costs hours.
4. Check `docs/agents/decisions.md` for anything already settled in this area, so you do
   not re-derive a rejected approach.
5. `git log --oneline -15` and `git status` — someone may already be in this lane.

Then:

- Take the **highest-ranked open item** in the lane. Do not start a lower one while a
  higher one is open.
- Write its acceptance test from `docs/agents/requirements.md` first where the requirement
  is ledger-observable. Those tests are worth more than the feature: there are ~60 minutes
  of human gate-check time in the whole weekend.
- Work only inside this lane's module. Needing to edit another module's file means the
  seam is wrong — say so rather than reaching across.
- `./gradlew verify` before you commit. If it is red, say it is red.

You have wide latitude on implementation, file layout and approach. The scaffold was
generated in one pass and has never been run on the target device — assume it is wrong
before you assume the device is. What needs announcing in the commit, rather than
permission: changes to shared types, new dependencies, new permissions, or a number that
reaches the eval card.
