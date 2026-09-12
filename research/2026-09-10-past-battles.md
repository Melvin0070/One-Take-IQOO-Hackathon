# R5: What happened at the Bengaluru (Aug 29–30) and Pune (Sep 5–6) iQOO City Battles

Researched 2026-09-10. Every claim has a source I read, unless it is marked "snippet only". Anything I could not confirm is marked UNVERIFIED.

---

## 0. Bottom line

- **No public results exist yet for either city.** I found no winners, runners-up, Top 6, special awards or Most iQOO Usage winner, in either bucket, on any channel I checked:
  - the organizer site (`/`, `/guide`, `/cities`);
  - the iQOO community;
  - the Reskilll blog (newest post is Aug 22);
  - news and X search (only the August launch post);
  - LinkedIn search (profile snippets only);
  - GitHub (no repo claims a placement).
- **Two false leads, ruled out:**
  - A Reskilll blog line, "iQOO City Battles 2026, Bengaluru — Winner - Working Professional Track (Rs 1.5L prize)", is a made-up example inside a resume template published Aug 21, before the event. It is not a real winner.
  - The Quanta README's "🏆 Gryffindor" line is a formatting choice, not a win. The commit says "rewrite README in hackathon-winner format, mark team Gryffindor". Any placement is UNVERIFIED.
- **The best intel is in participants' public GitHub repos.** A Pune team (Airgap) and a Bengaluru team (Kavach) wrote down:
  - what an evaluator asked in Round 1;
  - what the handbook says;
  - what broke on the loaner phones.

---

## 1. Q1: Winners, and who competed

### 1.1 Results

| City | Bucket | Winner / 1st RU / 2nd RU | Top 6 | Most iQOO Usage |
|---|---|---|---|---|
| Bengaluru | Working professionals | Not public | Not public | Not public |
| Bengaluru | Students | Not public | Not public | Not public |
| Pune | Working professionals | Not public | Not public | Not public |
| Pune | Students | Not public | Not public | Not public |

Announced prize structure per city:
- Working professionals: ₹1.5L / ₹1L / ₹70K.
- Students: ₹1.5L / ₹80K / ₹50K.
- Awards on Sunday at 16:15 include "special honours" and the "Most iQOO Usage award".
- Top 6 per city go to the Finale, three per bucket. "Standout builds can also earn Finale slots."
- Source: https://iqoo.reskilll.com/

### 1.2 Teams that competed (placement unknown for all)

**Bengaluru, Aug 29–30**

- **Kavach** (`Atul-Chahar/KAVACH_IQOO`). Real-time, on-device scam-call warning in Hindi, Hinglish and English.
  - How it works: the Android offline SpeechRecognizer transcribes the call, and a rules engine with 180 markers scores it. An accessibility service keeps the mic working during calls. It uses overlays, a Quick Settings tile and a notification listener for messages.
  - Enforced "no INTERNET permission" (the build fails if it appears).
  - A Gemma model via LiteRT-LM is integrated but **switched off**: LiteRT-LM 0.16.1 throws `NoSuchMethodError` and kills the process. No camera, no NPU.
  - Sources: https://github.com/Atul-Chahar/KAVACH_IQOO (README.md, docs/EVALUATION.md, CLAUDE.md, docs/HANDOFF.md, docs/MOBILE_FIRST_SETUP.md, commit log)
- **Repo Guardian** (Team Apex OS, `AJAYMYTH/IQOO-Hackathon-2026`). On-device AI code reviewer for GitHub repos.
  - Stack: llama.cpp via JNI, Qwen2.5-Coder-3B GGUF, voice commands, one-tap pull requests.
  - README claims Hexagon NPU at 32–38 tok/s (UNVERIFIED). Developer Tools track.
  - Source: https://github.com/AJAYMYTH/IQOO-Hackathon-2026
- **Quanta** (team "Gryffindor", Smart Education; a commit points at VIT Pune).
  - Photo of a textbook page → ML Kit OCR on the phone → FastAPI backend with **Gemini 2.5 Flash (cloud)** → interactive diagram, notes and quiz. Flutter app.
  - README says "OCR runs on the iQOO's NPU" (UNVERIFIED). Its own CHANGELOG admits "No on-device model for topic classification yet".
  - Repo was bulk-pushed on Sep 2, after the event.
  - Source: https://github.com/JustRK-07/quanta (README.md, CHANGELOG.md, commits)
- **Ruko** (`Puneesh12/ruko-demo`). A static web concept demo used for idea screening, not the build itself.
  - Interrupts a UPI payment when speech suggests a scam call is coercing the user. A family member can cancel the payment "via iQOO Office Kit".
  - Source: https://github.com/Puneesh12/ruko-demo

**Pune, Sep 5–6**

- **Airgap** (`chinmayDipke/mobile-model`). Scam blocker for SMS, WhatsApp and UPI-app notifications.
  - Rules plus **Gemma 3 1B int4 via MediaPipe on the CPU (~370 ms)**, a camera check that warns when a UPI QR code can only send money, text-to-speech warnings, and a Round-1 audit fix that removed INTERNET and READ_SMS.
  - Tried the NPU and dropped it. Bucket not stated.
  - Sources: https://github.com/chinmayDipke/mobile-model (SETUP.md, CHINMAY-TODO.md, CONCERN.md, Device-Facts.md, DEMO-PROOF.md, commit log)
- **EyesUp** (students, Smart Living, `inslot2525-ctrl/EyesUp`). Voice copilot for gig drivers.
  - Reads gig-app notifications, extracts payout and distance via regex → ML Kit Entity Extraction → Gemma 3 1B, compares offers across apps, and speaks a verdict in Marathi, Hindi or English.
  - Uses a second phone to simulate notifications.
  - The public repo holds only planning docs committed before hacking started, so there is no build evidence.
  - Sources: https://github.com/inslot2525-ctrl/EyesUp (README.md, HANDOFF.md, PROGRESS.md, docs/*.md, gameplan)

### 1.3 Earlier editions (context only; winners not public)

- **Delhi NCR pilot, 6 June 2026**, COWRKS DLF Cybercity. 8 hours, ₹1.8L, 80 builders.
  - One track, "BrandForge", was an organizer-set **creator brief**: "An autonomous AI social media engine — the creator's digital twin."
  - Rubric then: Office Kit usage 25% (HackTracker score ×25), phone-first execution 25%, AI-native build 20%, problem fit 20%, craft & pitch 10%, plus the Most iQOO Usage award.
  - Source: https://iqoo-delhi.reskilll.com/
- **Bengaluru pilot, 13–14 June 2026**, Scaler School of Technology. 30 hours, ₹3.2L, 80 builders, same rubric shape.
  - Source: https://iqoo-bangalore.reskilll.com/ (this subdomain still shows the June page)
- **Drishti** (June Bengaluru; first commit 2026-06-13). Camera-based scene narration for visually impaired users.
  - Gemini/DeepSeek in the cloud, Ollama over LAN, Gemma 3 1B INT4 offline fallback, Sarvam TTS.
  - Source: https://github.com/LAVYA255/Drishti
- **CreatorOS** (repo created May 31, last push Jun 2, so likely the Delhi pilot; city is my inference). "AI Creator Revenue Operating System".
  - React Native/Expo app; FastAPI + LangGraph + Gemini 2.5 Pro backend.
  - Features: creator twin, trend detection from screenshots, voice note → content, engagement autopilot, brand matching. Cloud-heavy.
  - Source: https://github.com/mukulpythondev/creatoros

---

## 2. Q2: What jurors, mentors and organizers signal

### 2.1 Official (organizer site)

- "A well-built simple product beats a broken complex one, and the 25% covering phone use is read off device data, not self-reporting." (https://iqoo.reskilll.com/)
- Technical depth is defined as "Architecture, code quality, robustness, real use of the hardware." (https://iqoo.reskilll.com/guide)
- "A local or open-source model at the core earns brownie points, with the phone in the loop via Office Kit." (guide and /cities)
- Guide rules (https://iqoo.reskilll.com/guide):
  - "HackTracker captures counts and durations only (no keystrokes, screenshots, or browsing)"
  - "Original work only: code written during the event window."
  - "repos locked before Top 10 pitches"
  - "Organisers may verify a project was built inside the event window."

### 2.2 Handbook lines, as quoted by the Pune EyesUp team (secondhand, UNVERIFIED against the handbook itself)

Sources: https://github.com/inslot2525-ctrl/EyesUp (gameplan §2–§4, docs/RED_LIGHT_PLAYBOOK.md, PROGRESS.md E-002)

- "The highest on-device builds will be preferred for the Top 10."
- HackTracker "sits on the device through the build, reads model outputs, and logs inference calls, tokens and thermals in real time."
- "Do not tamper with HackTracker under any circumstances. All crash and tamper logs are detected and will be penalised."
- "Red light: Direct usage of laptops is restricted and will be monitored. Work on your phone, or access your laptop only via the Office Kit on your phone."
- Organizer tip, received in person: HackTracker is a 30-hour watch on the phone, so use the phone as much as possible.

### 2.3 Round-1 evaluator at Pune (from Airgap's CONCERN.md)

The team describes "a judge who works in cloud (AWS)". I can't tell which panelist that was (UNVERIFIED). The questions:
1. "It reads all my SMS. I don't want that. Put filters on it."
2. "A new scam pattern appears in the market tomorrow. How does it get caught?"
3. "How is the agent self-learning?"

What the team did in response (commit "Answer the evaluator: drop INTERNET, drop READ_SMS, add Gate 0"):
- Stripped a network permission that ML Kit's barcode library had quietly added.
- Removed READ_SMS, which they never used.
- Added a filter that ignores messages with no money signal, and a privacy counter on screen.
- Rewrote a claim they "could not defend".

Mentor round: one commit is titled "menotr feedback", with no body.

Their prep assumed "A Qualcomm judge will ask" about the NPU, and warned "Claiming the NPU without evidence is the one thing a Qualcomm judge would catch instantly." Bengaluru's mentor list included a Qualcomm Senior Developer Advocate.

### 2.4 Named panels (https://iqoo.reskilll.com/, city tabs)

- **Bengaluru jury:** Goutam Kurumella (AWS India, Head of Startup Solutions Architecture), Madhav Bissa (nasscom, Program Director AI), Pradipta Dash (Avashya, Co-founder & CTO), Siddhant Agarwal (ClickHouse, Senior DevRel), Venkat Ragothaman (Microsoft, Site Lead, Enterprise Security).
- **Bengaluru mentors:** Aditya Cheke (Kuku FM, Android), Basawa Reddy (Walmart), Belal Khan (AmEx), Gaurav Chaddha (PhonePe), Kartikey Rawat (Qualcomm), Monali Dambre (Sabio), Nitin Prakash (Upswing), Rivu Chakraborty (Mobrio Studio), Souvick Biswas (Walmart).
- **Pune jury:** Mayur Modi (Swarovski, Head of AI Foundry/MLOps), Ramandeep Chandna (EPAM, Systems Engineering Manager – AWS & GenAI), Suyog Kale (Rover AI, Co-founder).
- **Pune mentors:** Chinmay Kulkarni (Hybrowlabs), Nikhil Varma (BMC), Ravi Joshi (Deepwize), Sandeep Kurian (CoachFirst), Sarang Manohar (LTIMindtree), Sarang Pandit (Extrapreneurs), Satyam Kumar (Qualys), Vatsal Jain (vConstruct), Vishal Alhat (AWS).
- **Pattern:** enterprise, cloud, security, MLOps and devrel. No creators or media people on either panel.

No public LinkedIn or X posts by jurors, mentors, iQOO or Reskilll commenting on either battle were found. Searching the jury names alongside "iQOO" returned nothing.

---

## 3. Q3: Practical lessons (Red Light, Office Kit, HackTracker, loaner phones)

### 3.1 Loaner phone

- iQOO `I2501`, SoC `SM8850`, Android 16 (SDK 36), 15.6 GB RAM, 443 GB free.
  - Source: Airgap Device-Facts.md. The site says OriginOS 6.
- One phone per person. HackTracker is pre-installed and Office Kit is pre-paired. Phones stay in the venue. (guide)

### 3.2 Developer options, adb and sideloading worked in practice

- Airgap confirmed the model with `adb` on both laptops, pushed the model to `/data/local/tmp/llm/`, and ran tests with `adb shell am broadcast`. Their SETUP.md installs APKs by dragging them into Office Kit file transfer and tapping to install: "`adb install` works and earns us nothing."
- Kavach verified on hardware ("iQOO I2501, Android 16").
- EyesUp planned to enable USB debugging (plan only).
- No source mentions a sideloading block. Still UNVERIFIED for Chennai: ask at check-in.

### 3.3 NPU reality

- Airgap: "NPU findings: dispatch lib missing, model works on CPU at 370ms". They tried a chip-specific `.litertlm` through MediaPipe and LiteRT-LM: "Neither reached it". Their rule: "If asked: say it runs on the CPU. Never claim the NPU."
- Kavach: LiteRT-LM on the GPU crashed on first inference, so they turned it off.
- EyesUp says Gemini Nano (AICore) is not available on iQOO/Funtouch (UNVERIFIED).

### 3.4 On-device model failures seen

Source: Airgap commit log.
- MediaPipe `generateResponse` hangs intermittently ("five messages at ~2s each, then 0% CPU forever"). They added a 6-second timeout.
- `maxTokens` counts input plus output, so a small value crashed the native engine.
- A BroadcastReceiver process holding a 529 MB model was silently killed. Fix: run detection in a foreground service.
- The 1B model on its own was biased toward false positives. Fix: rules gate first, model second.

### 3.5 OS behaviour

- Background apps get killed (Funtouch/OriginOS). Whitelist from battery optimisation, enable autostart, lock the card in recents, and "never touch HackTracker's battery or notification settings".
  - Source: EyesUp DEVICE_AND_TOOLING_SETUP.md §4, gameplan R11.
- The carrier (Jio) silently filtered phishing-style SMS, so the team switched the demo to WhatsApp notifications. (Airgap DEMO-PROOF.md)

### 3.6 Red Light in practice

- **You cannot build the app on the phone.** Android Studio doesn't run on Android. Termux/Gradle can't build Compose. Android 15+ kills long-running background processes. (Kavach MOBILE_FIRST_SETUP.md)
  - During Red Light the laptop is driven through Office Kit remote control.
  - "Camera preview plus permissions is a lot of keystrokes and Remote PC makes that painful": do heavy typing in Green. (Airgap CHINMAY-TODO.md)
- **Useful Red Light work:**
  - tune JSON config on the phone in a text editor;
  - rehearse;
  - run model inference repeatedly (it is logged);
  - commit and move files through Office Kit.
  - Source: EyesUp RED_LIGHT_PLAYBOOK.md.
- **Schedule conflict (UNVERIFIED):**
  - The Pune handbook bar, read off a photo by EyesUp, gave 12h30 Green / 9h30 Red, with the last Green window ending **Sun 06:30**, i.e. a forced code freeze before Eval 2 at 09:00.
  - The main site says "~10.5h red · ~8.5h green".
  - Both say timings are announced at the venue. Confirm on day one.

### 3.7 Office Kit and HackTracker telemetry

- Both counts and durations are scored. Keep the session connected, and use each feature repeatedly:
  - file transfer for APKs and models;
  - clipboard for logs;
  - mirror while testing and at eval.
  - Sources: EyesUp playbook §5; Airgap SETUP.md; Kavach EVALUATION.md §5.
- Kavach built Office Kit into the product: the incident report exports for file transfer, the model arrives via Office Kit, and the demo runs on screen mirror.

### 3.8 Submission and pre-event rules

- Repos lock before the Top 10 pitches (~13:00–13:30 Sun). Submit during the early-morning Red block. (EyesUp gameplan R12; guide)
- **Pre-event build conflict:** Kavach's HANDOFF.md (Aug 28) says "The organisers' updated rules removed the pre-event build restriction". The current guide says code must be written in the window. UNVERIFIED which applies in Chennai: ask.

### 3.9 Logistics

- Pre-download models (550 MB–2.77 GB); venue Wi-Fi is unreliable.
- Bring a data-capable USB-C cable, power banks, and a second phone if the demo needs one.
- Record a backup demo video.
- Sources: EyesUp docs; Kavach MOBILE_FIRST_SETUP.md §6.

---

## 4. Q4: Overlap with a creator/video app

- **Bengaluru and Pune:** no creator, talking-head or video-editing project found.
  - GitHub repo searches for creator/video/reels/teleprompter combined with iQOO returned nothing.
  - Camera use in the projects I found was OCR (Quanta), QR scanning (Airgap) and scene narration (Drishti, June pilot).
- **Creator adjacency:**
  - The June Delhi pilot set a creator digital-twin track (BrandForge), and CreatorOS answered it with cloud social/revenue agents.
  - So organizers have already seen "AI for creators" pitches, but not on-device video.
- **Chennai repos seen only as code-search result lines, not read** (snippet only):
  - `Salmanmalvasi/legacyvault` (FinTech)
  - `mukesh-dev-git/iqoo-hack26` (Team Limitless, Developer Tools)
  - `Rakshi2609/dayloop` (student)
  - `RKNAGA18/groundwork-mobile`
  - `Zephiron0247/solvescandemo`
  - None mention video or creators in those lines.
- **Outside the hackathon** (repo descriptions from a GitHub listing, not read): `theyashwanthsai/roughcut` ("AI-native video editor for talking-head videos") and `ranahaani/i-hate-editing` (raw talking-head takes → finished video). Consistent with the known prior art (Descript, Gling).
- **Risk read:** direct overlap in the room is low. The bigger risk is a cloud/enterprise jury asking "why not Descript?" and "why on-device?".

---

## 5. Q5: Chennai jury and mentors

- The site's Chennai tab today reads: "Panel not announced ... The Chennai lineup goes up here the moment it is locked." (https://iqoo.reskilll.com/)
- The Chennai subsite (iqoo-chennai.reskilll.com) has an expired TLS certificate; with the certificate check bypassed it returned only an empty shell.
- The Reskilll Chennai blog lists no names: https://reskilll.com/blogs/iqoo-city-battles-chennai-phone-first-ai-hackathon-tamilnadu-sept-2026/
- Chennai venue: TBA on /cities.
- No LinkedIn or X reposts of a Chennai panel were found.
- **Discrepancy:** an earlier session's memory note lists a Chennai panel (Mahindra infosec leader, Aracor AI CTO, Target Capital VC). No public source confirms it today. Treat it as UNVERIFIED and re-check the site or WhatsApp on Sep 11.

---

## 6. Implications for our pitch and demo

Copy:
1. **Airplane-mode demo, done silently first, then explained.** Airgap's and EyesUp's docs both treat airplane mode as unfakeable proof.
2. **Put proof on screen:** engine name, model load time, per-clip inference time, "footage never left this phone". "A number beats an adjective."
3. **Least privilege, enforced.** No INTERNET permission, checked with `aapt2 dump permissions`. Watch for SDKs such as ML Kit or Firebase adding it back.
   - Expect "does it upload my face and voice?" from cloud and security jurors.
4. **Show a measured case where the AI earns its place.**
   - Airgap's version: rules 0/10 vs model 6/10 on unseen scams.
   - Ours: retake/filler detection accuracy on our own clips, plus one clean take the app correctly leaves untouched.
5. **Keep the model working all weekend.** HackTracker reportedly counts inference calls and tokens, so run it on every take, not as a rare fallback.
   - Camera and mic use come naturally to a video app: bank the 15%.
6. **Build Office Kit into the product and the workflow:**
   - laptop as a mirrored monitor or teleprompter;
   - send the finished cut to the laptop via file transfer;
   - install APKs via file transfer;
   - clipboard for logs;
   - stay connected.
7. **Prepared answers** for "why not Descript/Gling", "why on-device vs cloud", "how does it learn/improve", and business model. These jurors are enterprise, cloud and security people, not creators.
8. **Freeze early and submit the repo early** (before the Top 10 lock). Record a backup video. Rehearse 3× at stage distance.

Avoid:
1. **Claiming the NPU without proof.** Two teams could not reach it. Say CPU or GPU unless you have measured the NPU.
2. **Cloud-dependent core features.** "The highest on-device builds will be preferred for the Top 10" (secondhand quote).
3. **Over-claiming.** Synthetic data presented as real, "fine-tuned" when it's prompting, half-finished hardware paths left in the repo.
4. **Touching HackTracker's settings,** or a hot, throttling phone at demo time. Video plus an LLM means heavy thermals, and HackTracker reportedly logs thermals. Whether that helps or hurts the score is UNVERIFIED.
5. **Heavy typing during Red Light.** Plan Red windows for tuning, testing, recording sample takes and rehearsal.

Ask organizers at check-in:
- the Red/Green schedule;
- whether code written before the event is allowed;
- whether sideloading and adb are OK on the loaner;
- what HackTracker counts for Office Kit;
- the repo-lock time;
- the Chennai jury names.
