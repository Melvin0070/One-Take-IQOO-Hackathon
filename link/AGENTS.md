# :link — multicam

Tier 2. Pairing, key exchange, authenticated control, encrypted transfer between phones.

**Ranked last on cost, not on value.** This may never be built. What is worth doing anyway
is the ten-minute decision below.

## Decide the pairing method even if the code never ships

R10 is the infosec juror's question the moment multicam appears on any slide, and
*"we would use X"* is a much better answer than *"we would need to decide"*.

Three options are on the table:

1. **A QR session key with signed control messages and encrypted uploads** — the cheapest
   defensible answer, and the one that most directly satisfies R10's test: *a fourth phone
   cannot start, stop or receive a segment.*
2. TLS with a fingerprint pinned from the QR.
3. A platform nearby-connections API.

Write the decision into `docs/agents/decisions.md` when it is made.

## The permission problem

**`:link` needs `INTERNET`. The single-phone demo build does not have it, deliberately** —
that is P1, and the permission list is evidence shown to a juror.

Android requires `INTERNET` even for local sockets, so "no network permission" is not a
claim multicam can make. If multicam ships it ships as a **separate build flavour**, and the
line said on stage changes with it. Do not add `INTERNET` to the demo build's manifest —
`./gradlew guardPermissions` will stop you, and it is right to.

## Functional needs

Per-session device-to-device offset checked by a clap on all phones (±30 ms target). The
primary starts and stops everyone. Phones 2 and 3 record locally, send face and motion
scores each second, and after stop send **only the segments the edit list uses**. Export
waits for them.

## On stage

If multicam does not pass its check it is presented as roadmap. **Never as "angles picked
by hand"** — a CTO discounts everything said after that phrase.
