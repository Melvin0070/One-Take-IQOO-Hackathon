# The agent context pack

Everything an agent needs to be productive here without reading 200 KB of design document.
Start at [`../../AGENTS.md`](../../AGENTS.md); come here for depth.

| Doc | Read it when |
|---|---|
| [architecture.md](architecture.md) | You need to know how the pieces fit — the spine loop, the module graph, the time model, the A/V decision, threading, persistence |
| [contract.md](contract.md) | You need the exact types, the coverage state machine, or the aligner rules. Also: **how to change a shared type** |
| [requirements.md](requirements.md) | You need to know what "done" means. R1–R25, each with its acceptance test, plus the privacy promises and the three known critical gaps |
| [lanes.md](lanes.md) | You are picking up work. Eight lanes, ranked items, definitions of done |
| [landmines.md](landmines.md) | **Before writing any `:asr`, `:npu`, `:capture` or `:media` code.** Fifteen verified traps that fail silently or at runtime |
| [decisions.md](decisions.md) | **Before re-deriving anything.** 32 settled decisions with reasons, plus what is genuinely still open |
| [workflow.md](workflow.md) | Commands, commit style, dependency rules, feature flags, device bring-up, gate checks |
| [glossary.md](glossary.md) | You are naming something, or writing UI text. Includes the words the UI must never show |

## The source documents

These three are the origin; the pack above is distilled from them and should agree with
them. Where it does not, the source document wins and the pack is a bug.

- [`One-Take-Design-v3.md`](../../One-Take-Design-v3.md) — **why.** Problem, users, the
  competitive claim, premises, product experience, trust and evaluation, market, the tier
  list, the demo and pitch. ~137 KB
- [`One-Take-Contract.md`](../../One-Take-Contract.md) — **what.** Authoritative on module
  boundaries, interfaces, the time model, the state machine, and the verified library
  research. ~50 KB
- [`One-Take-Playbook.md`](../../One-Take-Playbook.md) — **who and when.** The operational
  document: constraints, ordering, integration policy, gate checks, fallbacks. ~16 KB

Plus [`../../TODOS.md`](../../TODOS.md) — real work that sits outside the immediate build,
each item with enough context to act on cold.

## If you are reading this cold

The shortest useful path:

1. [`../../AGENTS.md`](../../AGENTS.md) — 5 minutes, and it tells you what you are free to
   change
2. [lanes.md](lanes.md) — find your lane, take its highest open item
3. Your module's `AGENTS.md`
4. [landmines.md](landmines.md) — the section for your module
5. Write the acceptance test from [requirements.md](requirements.md) first, then the code

The single most useful thing to internalise: **most gate checks are assertions over the
ledger, and the ledger is pure JVM.** Every check you write as a `:engine` test is a
five-minute human window you hand back to a team that only has about sixty of them.
