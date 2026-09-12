# Glossary

Borrowed from film sets because the words are exact and creators already half-know them.
**Use these words in the UI.** The code uses them too, so a type name and a screen label
never drift apart.

| Word | Meaning in One-Take |
|---|---|
| **Project** | One script plus every session recorded against it. Line ids stay stable across sessions, so a pickup joins the same cut |
| **Session** | One recording, from Record to Stop |
| **Script** | The pasted lines. Each line has a type |
| **Line** | One item of the script. The default line type |
| **Must-say** | A line that needs a **word-perfect** take — a disclosure, a qualification, a learning objective. Never split, never spliced |
| **Talking point** | Tier 2. Matched by meaning, not by words |
| **Cutaway** | Tier 3 |
| **Take** | One attempt at one line. A take can span several utterances |
| **Utterance** | A stretch of speech between two pauses, as cut by the voice detector |
| **Verdict** | The aligner's result for a closed take: clean, flubbed, or missing words — with a miscue reason |
| **Unread** | No take yet for this line. The state a line starts in |
| **Covered** | The line has a clean take |
| **Needed** | Flubbed, missed or scratched; still needs a take |
| **Pending** | The verdict has not arrived yet |
| **Circle** | Choose a take over the automatic pick |
| **Scratch** | Mark a take unusable — by voice, tap, remote, or automatically |
| **Off-frame** | The face left the frame during that take. **A note on the take, never a flag on the line** |
| **Video missing** | The take's verdict survived a crash but its video did not. It cannot be used in the cut |
| **Pickups** | A short session that records only the needed lines, into the same project |
| **Wrap** | Stop, ideally when every line is covered |
| **Safe to wrap** | Every line covered, must-say by its strict verdict. Record becomes Wrap |
| **Wrap report** | One page of coverage for the project |
| **Segment** | One entry in the edit list |
| **The cut** | The edit list, played |

---

## Words the UI must never show

**"similarity", "confidence", "utterance", "threshold", "alignment", "inference"** — and
anything else from the inside of the machine.

A creator reading their own flagged take sees a reason in plain words:

> missed "every Sunday"
> restarted
> said "seventy" for "seven"
> extra words

Not `similarity 0.78 < 0.85`. This is the "show your work" principle, and it is what turns
a flag from an accusation into information. `MiscueType` is the single enum behind all of
it — one enum in `:engine`, one presentation map in `:app`, not four lists.

## Words we never use about the product

- Not a **teleprompter**. vivo's camera has had one since 2022 and a dozen apps ship one.
  One-Take is a **script supervisor** — and for an OEM it is the layer that turns their
  teleprompter into one.
- Not **"AI editing"**. It is coverage, live.
- Never **"angles picked by hand"** about multicam. If multicam cannot pass its check it is
  presented as roadmap, not demoed. A CTO discounts everything said after "picked by hand".
- Never **"no render time"** about export. The no-render promise is about *playback on
  stop*, and export is measured and reported honestly.
