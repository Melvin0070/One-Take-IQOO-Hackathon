---
description: Run the Tier 0 gate check and report pass/fail per assertion
---

Run the Tier 0 check from `docs/agents/requirements.md#the-tier-0-check`.

Five back-to-back sessions of a three-line script with one deliberate flub each. Assert:

1. every flub prompts at its line-end pause
2. **no clean line prompts** — the one that matters most, because a false flag in front of
   a juror costs more than a missed flub
3. Safe to wrap appears only at full coverage
4. playback starts on stop with no render step
5. nothing crashes

Most of this is assertions over the ledger, so prefer running it as an automated test driven
by a recorded audio fixture over doing it by hand. Human gate minutes are the scarcest
resource in the build — roughly sixty of them against 25+ features.

Report **per assertion**, not as a single verdict. A flag turns on only when its own check
passes *and* Tier 0 still passes; anything unverified stays off. If you ran it on a laptop
rather than a loaner, say so — it changes what the result means.
