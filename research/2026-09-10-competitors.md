# One-Take: competitor research (round 1)

Checked 2026-09-10. Every row cites a page that was actually opened: with the gstack browse CLI unless marked (WebFetch). Short verbatim quotes are in quotation marks. **UNVERIFIED** means I only saw it in a search snippet, or the page was blocked or 404.

Scope: product and pitch only. SDKs and implementation were not evaluated.

Already verified before this round, not redone: Descript "Remove Retakes", Gling bad-take removal, DaVinci Resolve IntelliScript (all post-hoc).

---

## Master table

| Product | What it does (relevant part) | Live or post-hoc | Detects errors? | Source URL | Checked |
|---|---|---|---|---|---|
| **Pixel 11 Creator Suite teleprompter** (Google, Aug 12 2026) | In-camera teleprompter: "follows along as you speak". 9to5Google says it scrolls "automatically adjusting its pace and pauses to match your delivery". Also: Save to a project, Storyboard trim/rearrange, audio visualizer, Speech Enhancement, social grid. Pixel 11 and later only. | Live (scroll). Storyboard edit is post-hoc and manual. | **No.** None of the 3 pages mentions misread, skipped-line or retake detection. The blog's "never miss a word" is about keeping your place. | https://support.google.com/pixelcamera/answer/17372264 ; https://9to5google.com/2026/08/12/pixel-11-videography/ ; https://blog.google/products-and-platforms/devices/pixel/pixel-11-features/ | 2026-09-10 |
| **vivo X300 Ultra camera** (OriginOS 6) | GSMArena: "In the regular video mode, you get color filters, styles, beautification options, a teleprompter feature". Digital Camera World: "The built-in teleprompter is a great addition, and I wish more phones did this." Whether it follows your voice is **UNVERIFIED**. | Live (scroll) | None reported | https://www.gsmarena.com/vivo_x300_ultra-review-2957p5.php ; https://www.digitalcameraworld.com/tech/android-phones/vivo-x300-ultra-review | 2026-09-10 |
| **vivo X80 Pro camera** (2022) | "smart teleprompter solution integrated into its video function for its front and rear cameras". Up to 6000 characters. User adjusts "scrolling speed". | Live, fixed speed | No | https://www.vivo.com/eu/about-vivo/news/vivo-X80Pro-HiddenFeatures | 2026-09-10 |
| **iQOO 15** (OriginOS 6) | Official FAQ lists AI camera editing ("AI Erase, AI UHD upscaling, AI Image Expander, AI Color Adjustment"). System-wide "AI Captions (real-time speech-to-text, translation, text summarization)". **The FAQ does not mention a teleprompter.** Whether iQOO 15 has vivo's camera teleprompter is **UNVERIFIED**. | n/a | No | https://community.iqoo.com/in/thread/131152 ; https://community.iqoo.com/in/thread/130570 | 2026-09-10 |
| **Samsung Galaxy Z Fold8 / Flip8** (Jul 2026) | My FanCam ("automatically reframe video subjects"), Dual Recording, Camcorder Grip, Super Steady horizontal lock. The word "teleprompter" does not appear. | n/a | No | https://www.samsungmobilepress.com/articles/first-look-galaxy-z-fold8-ultra-galaxy-z-fold8-galaxy-z-flip8 (WebFetch; the browse CLI got an empty page) | 2026-09-10 |
| **Samsung Galaxy S26 / One UI 8.5** | Search snippets mention Creative Studio, Photo Assist, Advanced Audio Eraser, webcam mode. No teleprompter found. **UNVERIFIED**: pages not opened. | n/a | Not found | (search only: sammobile.com, androidauthority.com) | 2026-09-10 |
| **Apple iOS 27 Camera** | Beta code reportedly adds exposure scopes (histogram/waveform), focus peaking, highlight warnings and manual focus. No teleprompter mentioned. Unreleased and unconfirmed. | n/a | No | https://9to5mac.com/2026/09/08/camera-app-in-ios-27-reportedly-includes-four-major-pro-photography-features/ | 2026-09-10 |
| **PromptSmart Pro (VoiceTrack)** | Speech-recognition scrolling. "If you go off-script, VoiceTrack knows and will hold your place, waiting for you to return." "VoiceTrack language supported: English only." | Live | **Off-script only**, and only to pause the scroll. No flub flag, no retake prompt. | https://apps.apple.com/us/app/promptsmart-pro-teleprompter/id894811756 | 2026-09-10 |
| **Teleprompter.com (VoiceGlide)** | "on-device AI speech recognition", "works fully offline", 28 languages. Voice commands: "Go top", "Go restart" ("restarts the script"), "Go next", "Go previous", "Go current". Off-script: "the scroll waits for you". | Live | No. Restarting is a spoken command from the user, not something the app detects. | https://www.teleprompter.com/features/voice-scrolling ; https://www.teleprompter.com/faq/how-to-use-voice-scrolling-with-the-teleprompter-app | 2026-09-10 |
| **VoicePrompter** (iOS/Android/Mac) | "follows your voice word by word". "No audio ever leaves your iPhone." Voice commands "restart the script or jump back a paragraph". "speak a line from anywhere else in the script and it finds you there - it even scrolls backward". Floats over Instagram/TikTok camera. | Live | No. It re-locates you in the script but does not flag skipped lines. | https://voiceprompter.app/ios/ | 2026-09-10 |
| **ScriptFox** (web + Windows) | "The prompter follows your voice: your pace, your accent, your stumbles." "Stumble, restart, speed up. It keeps up." 31 "Hey Fox" voice commands, including "start recording". Studio (Pro, Windows desktop): "script-aware timeline, retake cleanup, filler + dead-air removal"; "the script you just read is already the timeline you're cutting". "English at launch." | Live tracking; **retake cleanup is post-hoc** in Studio | Tolerates stumbles live, cleans retakes afterwards. **Does not claim to prompt a retake during the take.** | https://getscriptfox.com/ | 2026-09-10 |
| **Elgato Prompter + Camera Hub Voice Sync** | "Prompter scrolls as you speak, changing pace when you do and even pausing if you go off-script." Requires NVIDIA RTX GPU or Apple M1. | Live | Off-script pause only | https://www.elgato.com/us/en/p/prompter (help article returned 403) | 2026-09-10 |
| **Speakflow Flow Mode** (web) | "Flow Mode follows your voice while you talk. If you pause somewhere, the teleprompter pauses." | Live | No | https://www.speakflow.com/blog/posts/2445aa24-6a2b-4091-a8a7-80098443dc98 | 2026-09-10 |
| **Captions app** (Mirage) | "As you record, Captions automatically adjusts the teleprompter speed to match your pace." "Reshoot specific segments, or use other Captions features like eye contact correction to fix issues later." | Live pacing. Fixes are post-hoc. | No live error detection claimed | https://captions.ai/features/ai-teleprompter | 2026-09-10 |
| **BIGVU** | App Store: "read text while scrolling in your screen & record video at the same time", "change text scrolling speed on the prompter", "AI eye contact fix". Pause-aware scrolling appears only in search snippets (**UNVERIFIED**). An "AI WordTrim" tool is listed in site navigation; what it does is **UNVERIFIED**. | Live | Not found | https://apps.apple.com/us/app/bigvu-teleprompter-captions-ai/id1124958568 | 2026-09-10 |
| **Descript teleprompter** (desktop/web/Rooms) | Manual "Scroll speed". "In Rooms, you'll need to manually click the Play button". Mobile availability **UNVERIFIED** (snippet says there is no mobile Rooms). | Live, manual | No (Remove Retakes is post-hoc, verified earlier) | https://help.descript.com/record/record-with-descript-s-built-in-teleprompter | 2026-09-10 |
| **CapCut mobile teleprompter** | Type your script, then "Adjust the scroll speed". | Live, fixed speed | No | https://photography.tutsplus.com/tutorials/how-to-quickly-use-the-teleprompter-in-capcut--cms-108923 | 2026-09-10 |
| **Instagram Edits / Instagram camera teleprompter** | In Edits since June 2025; moved into the main Instagram camera May 31 2026. Mosseri: "add a script that scrolls while you record. Helpful if you want to stay on message without doing a ton of takes." "Creators can also control the teleprompter speed." | Live, fixed speed | No | https://www.socialmediatoday.com/news/instagram-introduces-teleprompter-tool/821564/ | 2026-09-10 |
| **Riverside** | Marketing page only: "record it to perfection, at your own time and pace". Slider speed and mouse repositioning appear only in snippets (**UNVERIFIED**; support page returned 403). | Live | Not found | https://riverside.com/teleprompter | 2026-09-10 |
| **YouTube Create** | No teleprompter found. **UNVERIFIED** | n/a | Not found | (search only) | 2026-09-10 |
| **Blackmagic Camera / Final Cut Camera** | No built-in teleprompter found; forum posts pair them with separate prompter apps. **UNVERIFIED** | n/a | Not found | (search only) | 2026-09-10 |
| **Adobe Premiere mobile** | No teleprompter found. **UNVERIFIED** | n/a | Not found | (search only) | 2026-09-10 |
| **HeyGen / Synthesia / Loom** | HeyGen teleprompter page is a 404. A snippet says Loom has "no teleprompter". Synthesia takes scripts for avatars, not live recording. All **UNVERIFIED** | n/a | Not found | https://www.heygen.com/video/teleprompter-video-tool (404) | 2026-09-10 |
| **"Teleprompter Premium"** | Could not find an app by this exact name. **UNVERIFIED** | n/a | n/a | (search only) | 2026-09-10 |
| **Google Read Along** (Android/web; Google Classroom) | Reading buddy Diya "listen[s] and respond[s] to students with real-time feedback and encouragement as they read aloud". "Works without Wi-Fi". "11 languages". Jun 25 2026 Classroom rollout: "Real-time reading support: Learners get help with pronunciation as they read aloud". Content in 8 languages. | **Live** | **Yes**, it helps on struggled words. Exact error taxonomy **UNVERIFIED**. | https://support.google.com/readalong/answer/12279465 ; https://workspaceupdates.googleblog.com/2026/06/read-along-in-google-classroom-is-now-available-to-all-education-users-to-support-foundational-literacy.html | 2026-09-10 |
| **Microsoft Reading Progress** (Teams) | Students "record their reading on camera and submit it". Auto-detect "evaluates student recordings to identify likely mispronunciations and other reading errors". Error types: Omission, Insertion, Mispronunciation, Repetition, Self-correction. Metrics: Accuracy rate, Correct words per minute. Teacher can "Return for Revision". Cloud or device: **UNVERIFIED**. | **Post-hoc** | Yes, 5 miscue types | https://support.microsoft.com/en-us/education/getting-started-with-reading-progress-in-teams | 2026-09-10 |
| **Microsoft Reading Coach** | "As students read aloud, it offers real-time feedback on pronunciation, syllabification accuracy, and reading progress". Feedback "privately on their device", which is ambiguous about on-device processing (**UNVERIFIED**). Support page: a report appears after "Stop". | Live and post-hoc | Yes (mispronounced words; other types not listed on the pages read) | https://www.microsoft.com/en-us/education/blog/2024/12/support-independent-ai-powered-reading-practice-with-reading-coach/ ; https://support.microsoft.com/en-us/topic/use-reading-coach-in-immersive-reader-ead9a4e1-ef79-44ec-b7fe-62294bcfee01 (WebFetch) | 2026-09-10 |
| **Azure Pronunciation Assessment** (cloud API) | `EnableMiscue`: "the ErrorType result value can be set to Omission or Insertion". ErrorType values: "None ... Omission, Insertion, Mispronunciation, UnexpectedBreak, MissingBreak, and Monotone". "In continuous mode, the EnableMiscue option is not supported." A Feb 2025 Q&A thread reports streaming returns no omissions or insertions. | Per-utterance; streaming exists without miscue | Yes, for short scripted utterances | https://learn.microsoft.com/en-us/azure/ai-services/speech-service/how-to-pronunciation-assessment ; https://learn.microsoft.com/en-us/answers/questions/2157732/how-to-get-omissions-and-insertions-from-scripted | 2026-09-10 |
| **Amira** (Amira Learning) | "Amira listens as students read aloud ... She delivers intervention in the moment". "Delivering Real-Time Reading Diagnosis". English and Spanish. | **Live** | **Yes**, with intervention at the moment of error | https://amiralearning.com/amira-tutor | 2026-09-10 |
| **ELSA Speak** | "analyze your pronunciation and provide detailed feedback on sounds, stress, and intonation". Whether it gives live feedback mid-passage is **UNVERIFIED**. | Per-utterance | Yes (pronunciation) | https://elsaspeak.com/en/faqs/how-does-elsas-pronunciation-feedback-work | 2026-09-10 |
| **PowerPoint Speaker Coach** | "As you speak, Coach gives on-screen guidance ... about pacing, inclusive language, use of profanity, filler words, and whether you're reading the slide text." A Rehearsal Report follows. | Live and post-hoc | Yes, delivery issues (not script adherence) | https://support.microsoft.com/en-us/powerpoint/rehearse-your-slide-show-with-speaker-coach | 2026-09-10 |
| **ScriptE/S** (script supervisor software) | "automatically times the take from the moment you hit 'roll take' to the moment you hit 'cut take'. Write your comments ... and circle the good takes." "fastest way to line scripts". Reports: Lined Script, Editor Report, Daily Progress Report. | Live, **manual** | No speech automation | https://www.scriptesystems.com/scripte-for-mac-and-ipad | 2026-09-10 |
| **Avid Media Composer ScriptSync AI** | "indexes all text and audible dialog ... and then syncs each source clip to its associated line in the script". "locate all takes of any scripted or transcribed dialog line". | **Post-hoc** (edit suite) | Aligns takes to lines; no flub judgement claimed | https://www.avid.com/products/media-composer-scriptsync-option | 2026-09-10 |
| **Scriptation lining** | A snippet says it tracks coverage by recognising scene headings and character names in the PDF (text, not speech). **UNVERIFIED**: page blocked. | Manual | No | https://scriptation.com/features/lining-script-supervisors/ (blocked) | 2026-09-10 |

---

## Q1. Does any shipping product detect a flubbed, missed or misread line during recording and ask for a retake, or show coverage live?

**Not found.** Across the ~25 video and teleprompter products checked, live speech is used for three things only:

1. **Scrolling to the voice**: Pixel 11, PromptSmart, Teleprompter.com, VoicePrompter, ScriptFox, Elgato Voice Sync, Speakflow, Captions.
2. **Detecting off-script speech to pause**:
   - PromptSmart: "VoiceTrack knows and will hold your place".
   - Elgato: "pausing if you go off-script".
   - Teleprompter.com: "the scroll waits".
   - VoicePrompter: re-locates you anywhere in the script.
3. **User-spoken navigation commands**:
   - Teleprompter.com: "Go restart", "Go previous".
   - VoicePrompter: "restart the script or jump back a paragraph".
   - ScriptFox: 31 "Hey Fox" commands.

None of these pages claims to judge whether a line was delivered cleanly, prompt "again, line N", or show which lines are covered.

**Nearest misses:**
- **ScriptFox** is the closest in concept. Its prompter "follows ... your stumbles", and the take then lands on a "script-aware timeline" with "retake cleanup". The cleanup is a post-hoc Studio feature on the Windows desktop app, English only. No live retake prompt is claimed.
- **Pixel 11 Creator Suite** has the closest OEM shape: a voice-following prompter in the stock camera, clips saved into a project, and Storyboard. It has no line checking.
- **Captions** says "Reshoot specific segments", but that is a manual user action.

**Fixed-speed prompters with no voice at all:** CapCut, Instagram/Edits, Descript, vivo X80 Pro. Riverside is probably the same (UNVERIFIED).

**Not verified:** YouTube Create, Blackmagic Camera, Final Cut Camera, Adobe Premiere mobile, HeyGen, Synthesia, Loom and "Teleprompter Premium" were checked only by search. No teleprompter or error detection was found; all are marked UNVERIFIED.

**No "AI teleprompter" launch from 2025–2026 with mistake detection turned up.** Searches for stumble or flub detection while recording returned nothing. The App Store is full of voice-scroll clones: VoiceScroll, TeleScroll Voice, Voice Teleprompter PRO. These were seen in snippets only.

## Q2. Phone makers

- **Pixel 11 Creator Suite (Aug 12 2026)**
  - Google's support page: "Teleprompter: An in-camera teleprompter that follows along as you speak."
  - 9to5Google: "scrolls as you talk, automatically adjusting its pace and pauses to match your delivery."
  - Google blog: "dynamically scrolls as you talk ... speak at your own pace and never miss a word."
  - **This is voice-following scroll only.** None of the three pages mentions detecting misreads, skipped lines or retakes.
  - Other Creator Suite pieces:
    - "Save to a project". It needs Google Photos Auto Backup.
    - Storyboard, for manual trim and rearrange.
    - Audio visualizer, Speech Enhancement, social grid.
    - Pixel 11 and later only.
  - Android Authority's article could not be read (Cloudflare block).
- **Samsung**
  - Fold8/Flip8 press material has no "teleprompter"; creator features are reframing, dual recording and stabilisation.
  - Galaxy S26: no teleprompter found (UNVERIFIED, snippets only).
- **Apple iOS 27**: leaked beta code shows pro exposure and focus tools only. No teleprompter or script feature is reported.
- **vivo / iQOO (the host)**
  - vivo has shipped an **in-camera teleprompter in video mode since the X80 Pro (2022)**, with user-set scroll speed.
  - It is still there on the **X300 Ultra running OriginOS 6**, per GSMArena and Digital Camera World (2026 reviews).
  - Whether the OriginOS 6 version follows your voice is **UNVERIFIED**. No source found says it does.
  - For iQOO 15, the official FAQ and the OriginOS 6 camera guide list only photo AI (Erase, UHD, Image Expander, Magic Move, AI filter) and system "AI Captions (real-time speech-to-text ...)". A teleprompter on iQOO 15 is **UNVERIFIED**, but likely if it shares vivo's camera app (my inference).
  - No source shows vivo or iQOO detecting mistakes or auto-assembling takes.

## Q3. Live reading-error detection outside video, and the vocabulary to borrow

- **Google Read Along**
  - Still live and expanding: rolled out to all Google Classroom education users by July 3 2026.
  - Follows the reader live: "real-time feedback", "help with pronunciation as they read aloud".
  - Works offline after download. The help page says 11 languages; Classroom content covers 8.
  - The exact miscue categories were not on the pages read.
- **Microsoft Reading Progress**
  - **Post-hoc**. The student records and submits; Auto-detect marks errors afterwards and the teacher reviews.
  - Categories: **Omission** ("a word in the passage that the student skipped"), **Insertion** ("a word not written in the passage that a student added"), **Mispronunciation**, **Repetition** ("a word that a student reads more than once"), **Self-correction** ("reads incorrectly, recognizes their mistake, and reads again correctly").
  - Metrics: **Accuracy rate**, **Correct words per minute**. The action "Return for Revision" is the teacher's version of "again".
- **Microsoft Reading Coach**
  - Gives real-time feedback while reading, then a report after Stop.
  - "privately on their device" is ambiguous about where processing happens (UNVERIFIED).
- **Azure Pronunciation Assessment**
  - Cloud API. ErrorType values: **Omission, Insertion, Mispronunciation, UnexpectedBreak, MissingBreak, Monotone**.
  - Miscue detection is not supported in continuous mode (audio over 30 s).
- **Amira**: the strongest "live miscue plus immediate prompt" precedent. "listens as students read aloud ... delivers intervention in the moment".
- **ELSA**: per-utterance pronunciation feedback. Live mid-passage feedback is UNVERIFIED.
- **PowerPoint Speaker Coach**: live on-screen flags while you speak, including "whether you're reading the slide text". It is the inverse of One-Take, penalising verbatim reading.

**Vocabulary worth borrowing** (sourced):
- Omission = missed line or word
- Insertion = ad-lib
- Mispronunciation = misread
- Repetition
- Self-correction = the speaker restarts inside a line
- UnexpectedBreak / MissingBreak = pause problems
- Accuracy rate
- Correct words per minute
- From film (ScriptE): "line scripts" / lined script, "circle the good takes" (circle take), "coverage"

**Honest statement of what is and isn't new:**
- **Not new:**
  - Listening to someone read a known text and flagging omissions or misreads live (Amira, Read Along, Reading Coach).
  - Voice-following prompters (Pixel 11 and 8+ apps).
  - Aligning takes to script lines after the shoot (Avid ScriptSync; ScriptFox Studio; Descript, Gling and Resolve verified earlier).
- **Not found anywhere:** these combined inside a camera, during the take:
  - per-line clean or flubbed judgement,
  - a spoken prompt to redo a specific line,
  - a live coverage state,
  - an automatic cut of the latest clean take per line on stop.

## Q4. Voice-marked takes and script-supervisor software

- **Voice commands that exist are navigation, not take marking**:
  - Teleprompter.com: "Go restart", "Go previous".
  - VoicePrompter: "restart the script or jump back a paragraph".
  - ScriptFox: "Hey Fox, start recording", pause, jump.
  - None of these pages says a spoken phrase marks, rejects or deletes a take.
  - Searches for "scratch that", "bad take" and "mark that" as recorder or camera voice commands found only touch-based markers in voice-recorder apps. Result: **no voice-marked takes found** (this negative is search-based).
- **Script-supervisor software is manual**:
  - ScriptE/S times takes from "roll take" to "cut take". The supervisor types notes, lines the script and "circle[s] the good takes".
  - Scriptation (UNVERIFIED) recognises script text, not speech.
  - Speech automation exists only in post: **Avid ScriptSync AI** "syncs each source clip to its associated line in the script" and lets editors "locate all takes of any scripted ... dialog line". That is coverage per line, but in the edit suite, days later.

## Q5. Any capture tool that says coverage is complete or "safe to wrap"?

**None found.**
- Shot-list apps (CineFlo, Shot Lister; snippets only) track planned shots manually.
- ScriptE produces a manual "Daily Progress Report".
- Pixel 11's "Save to a project" plus Storyboard groups clips but does no completeness check.
- The idea that every script line has a clean take, so you can stop now, did not appear in any product read.

---

### Notes on method and failures
- Blocked or empty with browse:
  - androidauthority.com and 91mobiles.com (Cloudflare)
  - help.elgato.com and support.riverside.com (403)
  - captions.ai help (404), bigvu.tv teleprompter page (404), heygen teleprompter page (404)
  - scriptation.com (blocked)
- Retrieved with WebFetch after browse returned an empty page: Samsung mobile press, Reading Coach support.
- vivo X300 Ultra product page (vivo.com/en/products/x300-ultra) was read: no mention of a teleprompter.
