# :app — the product

Compose UI, every screen, the debug panel, DI wiring, ATTRIBUTION.

## You are not blocked on speech

`debugImplementation(project(":engine-fixtures"))` exists so you can build every screen
against **replayed ledger events** before the aligner is finished. The interface is
identical when you wire to the real engine. Do not wait.

## The one architectural rule

**Render `CoverageChanged`. Never compute coverage.** The state machine is a pure fold in
`:engine`. Two implementations will disagree, and they will disagree on stage.

Same for `MiscueType`: the enum is `:engine`'s, the **presentation map is yours**. One map,
in one file.

## Screens, ranked

**Capture** (the strip under the lens, coverage count, Record→Wrap) → **Script** (paste) →
**Review** (cut player, lined script, take reasons, pickups) → **Projects** → **Debug
panel** → **Wrap report** → **Export**.

Each needs its empty, pending, partial, error and degraded states designed, not just its
happy path. The degraded states are where the honesty principle lives: "models loading,
Record waits, with a visible reason"; "hot phone: face tracking at a low duty cycle, never
zero"; "verdicts lagging: prompts at the end".

## UX rules that are not negotiable

1. **Eyes on the lens.** Everything glanceable from 0.5–1.5 m, in the strip under the lens.
   **No dialogs while recording.**
2. **Never interrupt speech.** Prompts appear only in the pause after a line.
3. **Show your work.** Every flag gives its reason **in words** — "missed 'every Sunday'",
   "restarted", "said 'seventy' for 'seven'". Accepting a false flag is one tap.
4. **Everything automatic is reversible.** Scratch, circle, undo. Raw files always kept.
5. **Honest states.** Pending looks pending. Nothing claims coverage before its verdict.
6. **Never colour alone.** Covered / needed / pending each get an icon *and* a word. Large
   type. A haptic option, tested so the vibration is not audible in the recording.
7. **Mirrors well.** Capture and review stay legible mirrored to a laptop, so a second
   person — and the judges — can watch coverage live.

**Never show "similarity", "confidence" or "utterance".** See `docs/agents/glossary.md` for
the vocabulary, which is borrowed from film sets because creators already half-know it.

## The debug panel is Tier 0, not a nicety

It is how thresholds get tuned during windows where no rebuild is possible, and it carries
the **flag-vector checksum and config version** that R25 requires both demo loaners to show
before the demo. Ten seconds of checking that prevents the worst avoidable failure of the
weekend: two phones on the same APK with different flags.

**Release builds have no debug panel** (P5).

## The manifest

`app/src/main/AndroidManifest.xml` is checked against `config/allowed-permissions.txt` by
`./gradlew guardPermissions`, on the **merged** manifest. Adding a permission means adding
it to the allowlist with a reason. `INTERNET` breaks P1, which is a promise made out loud
to an infosec juror.
