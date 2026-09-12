# Qualcomm Whisper transcription integration

This branch connects the engine's audio features and token decoder to the pinned Qualcomm Whisper Tiny graph pair on the owner's iQOO 15.
It depends on the separate audio-feature, token-decoder and model-installation changes.
Production caption registration remains separate from this strict transcription API.

## Execution boundary

Audio decoding, mel features, token selection and text/timestamp parsing run on the CPU.
The neural encoder and decoder execute through QNN HTP with NPU_REQUIRED.
This is expected heterogeneous execution: moving model inference to NPU does not move every application operation there.
Execution reports identify each graph independently and are emitted only by actual engine sessions.
No media or transcript is included in those reports.

Each utterance owns fresh self-attention caches and consumes encoder cross-attention outputs.
The transcriber is serialized, checks cancellation between graph calls, and releases both contexts on close.
An already executing native graph call cannot currently be interrupted; cancellation takes effect when that call returns.
The current API accepts at most 30 seconds of mono 16 kHz PCM and does not silently truncate longer input.

## Device check

Build with the local QAIRT SDK and install both APKs using `adb install -r`.
Use the model installer's verified output or stage the pinned encoder.bin, decoder.bin and vocab.bin in the app-private `files/qnn-whisper-test/` test directory.

```sh
adb shell am instrument --user 0 -w -r \
  -e requireWhisperTranscription true \
  -e class com.example.one_take.QnnWhisperTranscriptionTest \
  com.example.one_take.test/androidx.test.runner.AndroidJUnitRunner
```

The opt-in test uses the repository's synthetic spoken-video fixture, checks recognizable text and ordered bounded segment times, and requires actual NPU execution reports from both graphs.
It preserves recordings and stores only its fixture result in the application cache for local inspection.
Segment timestamps must not be represented as model-aligned per-word timings.

## Validation record

The first physical-device contract run failed because the audio/token pipeline was not connected.
That establishes the missing behavior before integration.
The first assembled pipeline run passed the real-audio contract on I2501 in 13.511 seconds.
It produced nine ordered model-timed segments and completed transcription in 9,407 ms for the synthetic 30-second fixture.
Cancellation after an actual decoder execution, reuse of the transcriber, caption persistence, and MP4 export passed.
The exported frame was inspected and showed the generated captions.
The installed-bundle test then passed together with transcription in 14.698 seconds.
It installed the public archive inside Android, verified all five artifacts, reused the same directory on reinstall, and handed the verified directory to inference.
The combined committed-component build passed 242 unit tests and 28 Python bundle tests.
Android runtime lint had no issues; app lint had no errors and 30 existing warnings.
The first combined device suite passed all 15 tests after foregrounding resumed the app from an OEM background freeze; its 137.794-second duration includes that interruption.
The device tests now explicitly foreground the app before opted-in QNN checks.
The final foreground suite passed all 15 tests in 41.881 seconds without an external resume action.
A separate existing CPU caption-and-export test passed in 4.312 seconds on the same fixture, reporting 2,497 ms for CPU transcription and 1,533 ms for export.
The installed NPU path reported 9,390 ms for transcription.
The timing boundaries differ: CPU timing includes audio decoding and session preparation, while NPU timing starts after those steps.
These single diagnostic runs do not establish a controlled benchmark, but they show that this implementation does not yet demonstrate a speed improvement.
Performance investigation remains required before default activation.
This fixture also does not establish multilingual accuracy, word alignment or sustained thermal behavior.

## Remaining production work

Keep issue #3 open until production recorder integration, multilingual quality, word timing, overlapping windows, long recordings, cancellation/recovery, persistence/export and sustained-device checks pass.
Do not infer speed or thermal improvement from one fixture run.

## Decoder timing investigation

One diagnostic run reported 9,452 ms total transcription time, 117 ms for one encoder call, and 7,784 ms for 86 decoder calls.
These graph-call durations include wrapper overhead and are not kernel-only timings or NPU occupancy.
The decoder call path is the main measured latency component in that run and needs profiling before default activation.
The decoder currently leaves no-speech probability suppression to the caller; quiet-room and silence behavior must be validated before publishing captions automatically.
