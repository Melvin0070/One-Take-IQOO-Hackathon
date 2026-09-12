# Pause-detection fixtures

`hinglish-vad-speech.wav` is 16 kHz mono signed 16-bit PCM synthesized locally on macOS with the Lekha voice.
The text is: “आज हम इस फोन का कैमरा टेस्ट करेंगे। The picture quality looks clear, और इसकी बैटरी पूरे दिन चलती है। अब हम दूसरी रिकॉर्डिंग शुरू करेंगे।”
It contains no user recording.
It tests speech-like input and code-switched material, not transcription accuracy or real-human language coverage.

`room-pause-test.mp4` contains an eight-second generated navy video with sections of that speech around a known 2.4–5.4 second pause.
A deterministic 90 Hz hum and low-amplitude pseudo-random noise continue through the pause.
`NoisyPauseTest` constructs related steady-noise, varying-noise, and soft-speech PCM cases from the WAV.
These controlled mixtures are not field recordings of fans or traffic.
The physical microphone test plays the MP4 through the phone speaker and measures what the recording pipeline actually captures.

`room-recording-regression.mp4` re-encodes the test's own speaker-to-microphone PCM capture over a generated navy frame.
The user confirmed ordinary room noise with no TV, music, or other speaker.
The recording contains the synthesized playback and a strong low-frequency room-noise band.
The regression checks the two deliberately introduced pauses and preserves the surrounding spoken spans.
No user video or face imagery is included.

`room-confirmation.wav` preserves decoded AAC PCM from a second test recording.
It protects against short uncertain VAD scores erasing every otherwise confirmable pause after encoding.
