# Capture-time pause decisions

This milestone adds silence candidates to the existing live engine and reversible edit workflow.
Scripts, filler/flub detection, model replacement, and speed optimization remain deferred.

## During recording

The existing mono microphone stream feeds a pure Kotlin streaming detector independently of Whisper.
Pause detection also runs when captions are disabled or no caption model is installed.
The primary detector consumes complete 512-sample (32 ms) frames across arbitrary input chunks.
A bundled, checksum-verified Silero v6.2.0 model processes 16 kHz audio through the existing native runtime, independently of caption transcription.
The model is 885,098 bytes and needs no runtime download.
Probabilities at or above 0.2 mark speech, at or below 0.15 mark non-speech, and the uncertain band breaks pending pause runs.
A fresh classifier belongs to each microphone stream and each saved-audio analysis.
It requires speech before and after an interior non-speech interval longer than 1.2 seconds.
It preserves 200 ms at each end of that interval.
Leading, trailing, all-quiet, invalid, and ambiguous audio produces no candidate.
Unknown frames split non-speech intervals and are never included in a cut.
For a speech classifier, the longest already-qualified interval is retained until speech resumes, with later intervals winning ties.
Only one interval is retained, and invalid PCM or classifier exceptions discard it and reset the speech anchor.
If Silero cannot be created, the app falls back to bundled WebRTC VAD mode 0 using 320-sample frames.
If that runtime is also unavailable, the previous near-silence policy remains the fallback.
These fallbacks preserve recording availability but can miss pauses that the neural model would find.
Voice activity is not transcription or speaker identification.
Background voices, music, and speech-like noise can prevent cuts; quiet or obscured speech can still be misclassified.
Saved-audio confirmation and Undo remain necessary and do not guarantee that a shared classifier error is impossible.

Each closed candidate enters the durable capture journal in the RECOGNIZER clock.
It updates the potential-pause count on the capture screen without altering playback or media.
Candidate ranges remain separate from final cuts and survive project adoption with the same session UUID.

## After Stop

A per-take barrier stops and awaits that take's microphone before source finalization.
It replays the complete retained candidate snapshot through the coordinator, recovering startup and final-read candidates while deduplicating already journaled observations.
The barrier captures its microphone session, so a delayed old recorder cannot drain a newer take.
Sessions that never recorded a real CameraX Start event cannot accept candidates.

The finalized recording is decoded on its MEDIA timeline.
Existing audio alignment estimates the microphone-to-media offset without depending on ASR success.
Only durably recorded candidates are eligible for promotion.
Mapped candidates are intersected with non-speech independently measured in the saved audio using a fresh classifier state.
This is a second audio-timeline check using the same algorithm, not an independent model.
An intersection must be at least 200 ms and may never extend beyond either interval or the media duration.
If alignment is unavailable, existing saved-audio pause analysis provides the fallback.
If alignment succeeds but no candidates are confirmed, the final edit plan has no cuts.

The app persists confirmed cuts only when no edit plan already exists, preserving user decisions.
Review uses the existing per-cut Undo/Apply and Restore all controls.
Playback and export consume the same engine-backed edit plan, and the original file remains untouched.
With captions disabled, the app analyzes edits without loading Whisper or automatically exporting a copy.
The existing export action can save an edited copy without captions.

## Validation

Unit tests cover arbitrary chunk boundaries, speech padding, false-positive rejection, clock/lifecycle guards, replay, alignment offsets, reference intersection, and durable adoption.
Device tests exercise actual recording with captions disabled and the engine-to-review-to-export path using a synthetic pause fixture.
The fixture separates deterministic cut correctness from ambient microphone noise.

Silero model provenance and its MIT license are retained under `app/src/main/cpp/third_party/silero-vad/`.
The WebRTC fallback source is pinned in `app/src/main/cpp/third_party/libfvad/SOURCE_PIN.md`, with its BSD license and patent grant retained.
The controlled Hinglish speech/noise fixtures are described in `app/src/androidTest/assets/VAD_FIXTURES.md`.
They are synthetic test material, not a real-human or language-accuracy benchmark.
