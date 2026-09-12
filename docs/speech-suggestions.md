# Filler and self-repair suggestions

This milestone extends the existing engine-backed edit flow with reviewable speech suggestions.
The original media remains untouched, and every new speech suggestion starts disabled.
Live Director prompts, scripts, and inference optimization are separate work.

## Evidence and detection

The native recognizer supplies word timing and recognition confidence from its token output.
Words are never assigned timestamps by dividing a caption evenly.
Live window offsets and saved-media alignment must shift word timing alongside caption timing.
Invalid, uncertain, or stale word evidence cannot produce a cut.
Legacy captions without word evidence remain readable but do not produce word-level suggestions.

The pure Kotlin engine detects a small filler allowlist, adjacent repeated words, and repeated short prefixes.
It proposes removal of the earlier repetition while retaining its replacement.
Ordinary lexical words such as “like”, “so”, and Hindi “hum” are not filler triggers.
A repetition may be intentional emphasis; recognition confidence measures the recognized words, not the probability that removing them improves the take.
Review and explicit Apply are required.

## Storage and review

Timed words are preserved in the existing caption events with optional fields for legacy compatibility.
Detected candidates become disabled cuts in the existing edit plan, with timestamps, explanatory text, and recognition confidence.
Suggestions conflicting with an existing cut are omitted rather than merged or widened.
Apply and Undo use the existing durable edit events and export path.
Preview plays the relevant original interval with surrounding context, including when that interval is currently cut from the edited view.

Editing a caption invalidates its previous word evidence.
Reanalysis removes stale pending speech suggestions while retaining explicitly applied edits and other user decisions.
The bounded implementation uses the existing non-overlapping cut list; overlapping alternative suggestions are not presented in this milestone.

## Validation

Engine tests exercise lexical false positives, confidence and timing gates, repeated prefixes, stable candidate identity, overlap, and caption mapping after cuts.
App tests exercise optional word serialization, project reopen, Apply/Undo, and caption correction.
Device validation covers the native word output plus review and export with controlled transcript fixtures.
These checks are not a real-speaker Hinglish recognition-accuracy benchmark.
