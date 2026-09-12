# One-Take: market and jury research (R4)

Compiled 2026-09-10 for the iQOO City Battles Chennai pitch (Sep 12–13, 2026).

**Evidence labels**
- **[READ]:** I opened and read the page (gstack browse, or WebFetch/PDF when browse failed).
- **[SNIPPET]:** search-result text only. I did not open the page.
- **UNVERIFIED:** not confirmed from a primary or read source. Don't put it on a slide as fact.

About 45 pages were read. Search snippets were used only where marked.

---

## 1. Jury intel (professional, public information only)

### 1.1 Aracor AI and its CTO

**Company facts**
- **What it is:** Aracor calls itself "an AI-native dealmaking platform" for "diligence to negotiation, and from closing through exit". It covers evaluations, contract review, obligation tracking, due-diligence management and post-signing oversight. [READ] [aracor.ai/company](https://aracor.ai/company), Aracor, undated.
- **Buyers:** family offices, in-house legal teams, VC and PE firms (same page). FinSMEs says "early adopters include PE & VC funds, and SaaS companies in the US and abroad." [READ] [FinSMEs, Mar 25, 2025](https://www.finsmes.com/2025/03/aracor-ai-raises-4-5m-in-pre-seed-funding.html)
- **Mission:** "evidence-linked and reproducible outputs, and keeps verification current as documents change". Vision: "continuously verified legal intelligence". [READ] aracor.ai/company
- **Founders and board:**
  - Katya Fisher, Founder & CEO. She is also Executive Vice Chairman and CLO of Constructor Group.
  - Dr. Serg Bell, co-founder. He founded Acronis, Parallels, Virtuozzo and Runa Capital.
  - Gordon Caplan, co-founder, ex-Co-Chairman of Willkie Farr & Gallagher.
  - Advisors include Maggie Vo (GP/CIO, Fuel Venture Capital) and Nick Brown (ex-AstraZeneca AI).

  [READ] aracor.ai/company
- **Stage and size:**
  - $4.5M round announced February 2025, led by Fuel Venture Capital and Maggie Vo; Miami-based. FinSMEs calls it pre-seed. [READ] FinSMEs, Mar 25, 2025
  - Tracxn lists it as "Seed", $4.5M in 1 round (Feb 13, 2025), founded 2023, 22 employees as of Jul 31, 2026. It ranks Aracor 28th of 367 competitors (Harvey, Wordsmith, Ivo and others). [READ] [Tracxn, last updated Aug 25, 2026](https://tracxn.com/d/companies/aracor-ai/__o3_5Zl9PeRrguemtqVXlY6DH65KoCf-TKgyfGt2fThI)
  - I found no Series A as of today.
- **Products:** Deal Intelligence, Deal Verifier and Redact [SNIPPET]. Deal Verifier™ for term-sheet comparison is confirmed in the Artificial Lawyer piece below. [READ]
- **Security posture:** this is highly relevant to One-Take.
  - "Aracor enforces Zero Data Retention by default … aligned with ISO 27001, SOC 2, and GDPR."
  - Three deployment options: (1) cloud LLMs managed by Aracor (OpenAI, Anthropic, Gemini) under ZDR; (2) the customer's own API key; (3) "Your Private LLMs Hosted by You or by Aracor … on-premises or within your virtual private cloud".

  [READ] [aracor.ai/security](https://aracor.ai/security), Aracor, undated.
- **Open-weight model:** in Aug 2025 Aracor integrated OpenAI's open-weight GPT-OSS 120B for security-focused and on-prem deployments. Katya Fisher said:
  - "Many prefer models that never leave their controlled environments, ensuring data privacy and compliance."
  - "the real differentiator isn't how smart the LLM is but rather how securely it handles data."

  [READ] [Artificial Lawyer, Aug 20, 2025](https://www.artificiallawyer.com/2025/08/20/aracor-taps-gpt-oss-for-security-focused-dealmakers/)

**CTO: Lesly Arun Franco**
- **Background:** Aracor's first hire, promoted to CTO. He is "one of the few industry specialists in natural language processing (NLP)". At AstraZeneca he "gained extensive experience in data privacy, compliance, and the practical implementation of generative AI". His quote: "creating systems that empower professionals to tackle their biggest challenges with confidence and precision … ensure that our solutions remain intuitive." [READ] [Grit Daily, updated Jan 20, 2025](https://gritdaily.com/aracor-ai-appoints-ai-expert-lesly-arun-franco-cto/)
- **Location:** based in Chennai; holds a Deep Learning Nanodegree and a B.Tech in IT. [SNIPPET, LinkedIn/The Org] Location is UNVERIFIED but likely, given he judges the Chennai round.
- **Public views on accuracy:**
  - "Search is a massive problem. Our platform ingests thousands of legal documents, each requiring precise retrieval and accurate citations."
  - The team "evaluated several vendors" before choosing Qdrant (open source, self-hostable, hybrid and metadata-filtered search).
  - The case study says "High-quality citation and retrieval accuracy have significantly increased user trust."
  - It claims 90% faster due-diligence workflows, a 70% cut in turnaround and 40% fewer legal hours. These are customer-reported, in a vendor case study.

  [READ] [Qdrant blog, Daniel Azoulai, May 13, 2025](https://qdrant.tech/blog/case-study-aracor/)
- **Evaluation methodology:** I found no public post by him on eval method, benchmarks or hallucination rates. UNVERIFIED beyond the quotes above.

**What he'll probe:**
- He is an NLP/retrieval person whose company sells "evidence-linked, reproducible" verification and privately hosted models. Expect: WER or alignment accuracy on Indian-accented and code-mixed speech; precision and recall of the "clean line" verdict; what evidence backs each verdict (timestamps, transcript diff); why these on-device models; and failure modes.
- Present One-Take's per-line ledger as "evidence-linked" in his vocabulary: every verdict carries the audio span, the ASR text and the diff against the script.

### 1.2 Mahindra Group infosec leadership

- **Shivani Arni:** "started a new role as Enterprise CISO at Mahindra Group" (dated Nov 8, 2024). Previously VP – Head of Information Security at TransUnion CIBIL, plus HDFC Bank, KPMG India, Accenture, Deloitte and Genpact. Expertise: "risk management, IS and security auditing, regulatory compliance, architecture security reviews, and vulnerability assessments." [READ] [CIO&Leader, Nov 8, 2024](https://www.cioandleader.com/shivani-arni-starts-a-new-position-as-enterprise-ciso-at-mahindra-group/)
  - Earlier title: Deputy Group CISO. [SNIPPET, Elets CIO, ~Mar 2024]
- **Her public themes** (all [SNIPPET], not opened):
  - Supply-chain attacks on hardware components and service providers (talk, May 16, 2022; bankinfosecurity/devicesecurity author page).
  - Cybersecurity and AI in manufacturing ("Cyber Sovereignty Conclave – Chapter 1", Digitaltech Media LinkedIn, ~Sep 2025).
  - "Security is an afterthought everywhere" (UST D3 post, ~Oct 2025).
  - How "AI-enabled Google SecOps empowers her team" (Google Cloud LinkedIn video, ~Oct 2025).
- **Other Mahindra infosec voices:** Pankaj Srivastava, national head of cyber defense and forensics at Mahindra Defence Systems, sat on a DPDP Act panel. [SNIPPET, bankinfosecurity.asia] The group-level AI governance model evaluates AI projects "for both technical feasibility and strategic impact". [SNIPPET, cio.inc]
- **Juror identity:** which Mahindra infosec leader is on the Chennai jury is UNVERIFIED. Shivani Arni is the most senior public group-level infosec figure I found.
- **What they'll probe:**
  - Security designed in rather than bolted on ("afterthought").
  - Supply chain: model provenance and hash-verified model import.
  - Third-party data processors.
  - DPDP consent for faces and voices, including bystanders.
  - Local attack surface (multicam pairing).

  Lead with the threat model, not a feature list.

### 1.3 Target Capital: NOT IDENTIFIED (UNVERIFIED)

- Seven searches (Tracxn, Crunchbase, LinkedIn, Inc42, Entrackr, YourStory, and city names) found no India VC called "Target Capital".
- **Near-misses:**
  - Target Global, a Berlin/London/Tel Aviv VC with €3B+ AUM (Delivery Hero, Revolut). [SNIPPET]
  - "Target in India", Target Corp's accelerator. [SNIPPET, Tracxn]
  - Global Target Ventures, NYC-based with an India team. [SNIPPET, Tracxn]
- The event pages I found don't name the jury. [SNIPPET, reskilll.com Chennai blog: Sept 12–13, 30 hours, ₹6L prizes]
- **Action:** get the juror's name from Reskilll/iQOO before Friday, then check their LinkedIn. The thesis, sectors, check size, portfolio and views on consumer/creator/OEM plays are all UNVERIFIED.
- **Until then, prepare the standard VC set:** who pays, how much, how you reach them, why now, why you, and what Google, CapCut or Descript do next.

---

## 2. India creator economy: credible 2024–2026 numbers

| Metric | Figure | Source |
|---|---|---|
| Monetized creators; spend influenced | "over 2–2.5 million monetized content creators influencing more than $350–400 billion in consumer spending"; "$1 trillion+ in creator-influenced consumption by 2030"; survey base 1,900+ consumers and 60+ brands | [READ] [BCG, "From Content to Commerce: Mapping India's Creator Economy", May 3, 2025](https://www.bcg.com/publications/2025/india-from-content-to-commerce-mapping-indias-creator-economy) |
| Only 8–10% monetize effectively; brand deals ≈ ¾ of creator income | as stated | [SNIPPET] attributed to BCG via secondary sites. Not on the BCG landing page I read. UNVERIFIED |
| YouTube payouts in India | "paid more than INR 21,000 Crores to creators, artists, and media companies across India" in the last 3 years; "over 100 Million channels have uploaded content in the last year"; "more than 15,000 of those channels have over 1 Million subscribers"; INR 850 crore investment over the next 2 years; 45 billion hours of watch time from viewers outside India last year | [READ] [Google India blog (Neal Mohan at WAVES), May 5, 2025](https://blog.google/intl/en-in/products/platforms/youtubes-india-bet-inr-21000-crore-paid-out-to-indian-creators-commits-inr-850-crores-to-power-indias-creator-nation/) |
| Influencer marketing spend (EY) | "projected to reach INR3,375 crore by 2026, with a CAGR of 18%"; 86% of influencers expect significant income growth in 2 years | [READ] [EY India, "How influencer marketing is impacting brands in India", Apr 2, 2024](https://www.ey.com/en_in/insights/media-entertainment/how-influencer-marketing-is-impacting-brands-in-india) |
| Influencer marketing spend (Kofluence) | "₹3,000-3,500 Cr in 2025, sustaining a 22% CAGR … projected to reach ₹4,500-5,000 Cr by 2027"; report page headline "₹3,500Cr" annual spend in 2026 | [READ] [Adgully, May 14, 2026](https://www.adgully.com/post/15568/kofluence-launches-2026-influencer-marketing-report); [READ] [Kofluence report page](https://www.kofluence.com/influencer-marketing-research-report/) |
| Creator base | "4.0M-4.4M+ active professionals"; Instagram 3.3–3.7M; 15.2% registered as a business or GST individual; 61.1% of surveyed creators are nano (1K–10K) | [READ] Adgully, May 14, 2026 (Kofluence) |
| Formalization | "39.3%" of brands "route influencer contracts through legal review", "near-zero just two years ago"; 13.3% link influencer spend to formal revenue targets | [READ] Kofluence report page |
| Creator AI use | 59% of creators use AI tools regularly or sometimes; 17.3% never do | [READ] Adgully / Kofluence |
| Campaign cost (useful pricing anchor) | Average cost per campaign: Metro ₹3.8L–₹4.5L; Tier 2 ₹1.3L–₹1.6L; Tier 3–4 ₹35K–₹90K ("Kofluence ARR 2026 Platform Data and BCG India Consumer Markets Report") | [READ] Adgully, May 14, 2026 |
| Redseer "creator economy crossed ₹3,000 crore in 2025" | as stated | [SNIPPET] only. UNVERIFIED |

**Caveats**
- **Kofluence contradicts itself.** The report page says "750K+ creators, 1,000+ surveys" and elsewhere "300+ creators surveyed". The press release says "over 2 million creators". Cite the ₹ figures, not the sample size.
- **Bain "Creators to Commerce":** I could not find a Bain report by that name. The report that exists is **BCG's "From Content to Commerce"** (May 2025). The reviewer's citation looks misattributed.

### Educator-creators

| Platform | Figure | Source |
|---|---|---|
| Classplus | "over 1 Lakh coaching institutes across 1,100+ Indian cities have successfully set up their online business through apps built by Classplus" | [READ] [Classplus About page](https://classplusapp.com/aboutus.html). Page footer is ©2021, so possibly stale |
| Classplus | "1 lakh+ educators across 3,000+ cities", ₹265 Cr FY24 revenue | [SNIPPET] (Edmingle blog). UNVERIFIED |
| Physics Wallah | 4.13M unique transacting (paying) online users in FY25; flagship channel 13.7M subscribers (Jul 15, 2025); 98.8M subscribers across its YouTube channels; 888 channels and handles, 119.27M followers and 22.85B YouTube views (Jun 30, 2025); 198 offline centres in 109 cities | [READ] [Storyboard18 (DRHP-based), 2025](https://www.storyboard18.com/brand-marketing/physicswallah-reports-4-13-million-paying-users-in-fy25-diversifies-across-13-education-segments-82106.htm). "207 active YouTube channels" is [SNIPPET] |
| PW faculty count | not found | UNVERIFIED |
| Graphy | "2,000+ course creators" vs "200+ active creators" | [SNIPPET]; sources conflict. UNVERIFIED |
| Teachmint | "20 million users across 17 languages, 50+ countries" | [SNIPPET] (Entrepreneur India). UNVERIFIED |

**Educator takeaway:** the educator-creator base is large, but the countable units are institutes and learners, not individual scripted-video creators. PW runs 200+ channels, which is a studio, not a solo-creator workflow.

---

## 3. Brand briefs, revisions and content approval

**What I could NOT find:** any credible, primary survey that measures how often influencer deliverables need revisions or reshoots because a talking point or disclosure was missed. The same goes for a brand rejection or approval rate for creator drafts, in India or globally. Everything below is vendor blog content:
- "Standard professional practice is one to two included revision rounds. When content requires three or more revision rounds, the root cause is almost always an insufficient brief … the brand failed to communicate mandatory elements before production began." [SNIPPET] influencers-time.com. UNVERIFIED
- Vague briefs lead "to 3 to 5 revision rounds"; "personalized briefs see 34% fewer revision requests" (vendor's own platform data). [SNIPPET] partnrUP. UNVERIFIED
- "62% of failed marketing campaigns stemmed from unclear briefs" (attributed to Adobe 2025). [SNIPPET]. Classic recycled stat. UNVERIFIED; don't use.
- In a CreatorIQ survey, 44% of creators want "better communication" and 39% want "timely feedback". [SNIPPET] UNVERIFIED

**Adjacent stats I did read**
- **ASCI enforcement (regulator sample, not brand approvals):**
  - FY2024-25: "ASCI investigated 1,015 influencer ads, of which 98% required modification"; influencer violations were 14% of ads processed; 121 violations on LinkedIn for undisclosed paid partnerships. [READ] [Mediabrief, May 28, 2025](https://mediabrief.com/asci-2024-25-annual-complaints-report/)
  - H1 FY2025-26 (Apr–Sep 2025): "ASCI investigated 1,173 influencer advertisements, with 98% requiring modification"; "Nearly 59% promoted products that are disallowed by law"; "76% of India's top digital stars, as per the Forbes list, were found in violation of the disclosure norms"; influencer voluntary compliance "hit 90%"; "violations mainly included failing to disclose paid collaborations." [READ] [ASCI press release, Nov 11, 2025](https://www.ascionline.in/wp-content/uploads/2025/11/Press-Release-ASCI-Half-Yearly-Complaints-Report-2025-26.pdf)
  - FY25 disclosure failure split: 56.8% "carried no label at all", 43.2% "placed disclosures within hashtags". [SNIPPET]; exchange4media returned 403. UNVERIFIED
- **Brand side:** 39.3% of brands route influencer contracts through legal review. [READ] Kofluence

**Content-approval features in creator platforms**
- **Aspire:** "review, approve, and publish creator content with a single click", with triggers and reminders. [SNIPPET] (aspire.io / comparison blogs)
- **CreatorIQ, GRIN:** reviewer assignment, SLA tracking and "compliance-scanning layers". [SNIPPET] (influencers-time.com)
- **Upfluence:** content performance and campaign management; approval tooling less automated than Aspire. [SNIPPET]
- **Kofluence, Qoruz, Winkl, Grapevine:** discovery, outreach and campaign management. I found no public detail on draft-approval or compliance-check features. UNVERIFIED

**Verdict for (d):**
- *Evidenced:* disclosure non-compliance is widespread and enforced, the rules are specific, and brands are adding legal review.
- *Not evidenced:* that missed brand talking points cause measurable revision or reshoot cost. Present that as a hypothesis to test with 3–5 creators or agency managers, not as a market fact.
- *Approval tooling happens after the shoot, on the draft.* No platform found checks mandatory lines at capture time. That is One-Take's gap, but it also means nobody has shown demand for it.

---

## 4. Regulated speech

### 4.1 ASCI Guidelines for Influencer Advertising in Digital Media

[READ] [ASCI guidelines PDF (effective Jun 14, 2021; Addendum I dated 15.07.2021; Addendum II dated 17.08.2023)](https://www.ascionline.in/wp-content/uploads/2023/08/GUIDELINES-FOR-INFLUENCER-ADVERTISING-IN-DIGITAL-MEDIA.pdf)

- **1.2 Placement:** "Disclosure must be upfront and prominent so that it is not missed by an average consumer."
  - Disclosures "are likely to be missed if they appear only on an ABOUT ME or profile page, or bios, **at the end of posts or videos**, or anywhere that requires a person to click MORE."
  - "Disclosure should not be buried in a group of hashtags or links." Platform tools count "in addition to" the influencer's own disclosure.
- **1.2e Video without accompanying text:** the "label needs to be superimposed over the picture/video". Minimum duration:
  - ≤15 s: at least **3 seconds**. Some blogs wrongly say 2 seconds.
  - 15 s to 2 min: **1/3 of the length**.
  - ≥2 min: "the entire duration of the section in which the promoted brand or its features, benefits etc., are mentioned".
- **1.2f Live streams:** "the disclosure label should be **announced at the beginning and the end** of the broadcast."
- **1.2g Audio:** "the disclosure must be **clearly announced at the beginning and at the end** of the audio, and before and after every break."
- **1.3 Permitted labels:** Advertisement, Ad, Sponsored, Collaboration, Partnership, Employee, Free gift, "Paid Partnership" tag (Instagram), Affiliate, "Includes Paid Promotion" tag (YouTube). Language: "in English OR in the language as the advertisement itself".
- **1.5 Responsibility:** shared by advertiser and influencer. The advertiser "shall, where needed, call upon the influencer to delete or edit an advertisement or the disclosure label". This is a revision trigger that sits with the brand.
- **2 Due diligence:** influencers "are advised to review and satisfy themselves that the advertiser is in a position to substantiate the claims".
- **Addendum II (Aug 17, 2023), health and financial influencers:**
  - BFSI stock or investment advice requires SEBI registration, with the "SEBI registration number … stated with their name & qualifications". Other finance advice needs an IRDAI license, CA, CS or similar.
  - Health and nutrition: a medical degree, or certified nurse, nutritionist, dietician, physiotherapist, psychologist, etc.
  - Qualifications must be shown "**Superimposed on the visuals prominently and upfront, or mentioned as the opening remark in videos**"; for podcasts, "called out at the beginning".
  - **This is a literal must-say line at a fixed position.**
- **April 2025 update:** LexOrbis says ASCI updated the health/finance guidance "under Addendum 2 on April 7, 2025", announced Apr 28, 2025. It separates non-technical generic promotion (allowed without qualifications) from technical advice (qualification required). [READ] [LexOrbis, May 6, 2025](https://www.lexorbis.com/asci-updates-influencer-guidelines-for-health-and-finance-sectors-strikes-balance-between-expertise-and-expression/)
  - **Date conflict:** the ASCI PDF dates Addendum II to Aug 17, 2023. Cite "ASCI Addendum II (2023; clarified April 2025)".

### 4.2 SEBI finfluencer framework

- **Aug 2024 notifications:** amendments notified in "three separate notifications":
  - "No person regulated by the Board … shall have any direct or indirect association, with another person who provides advice or any recommendation … unless the person is registered … or makes any claim, of returns or performance … unless … permitted."
  - Association covers money transactions, client referrals and IT-system interaction.
  - Carve-out: investor education, if no recommendations or return claims are made.
  - SEBI said it had removed 15,000+ unregulated finfluencer "content sites" in the prior three months.

  [READ] [Business Today, Aug 31, 2024](https://www.businesstoday.in/personal-finance/investment/story/sebi-tweaks-regulations-for-finfluencers-removes-content-of-over-15000-unregulated-entities-443789-2024-08-31)
- **Oct 22, 2024 circular:** carve-out for "Specified Digital Platforms" with preventive and curative mechanisms. [SNIPPET] (Lexology/Business Standard)
- **Jan 29, 2025 circular/FAQs:** pure educators may not use market price data from the past **three months** when naming securities. [SNIPPET] The corplawupdates page confirms the "January 2025 circular" set a 3-month usage rule. [READ]
- **2026 update:** SEBI circular HO/47/17/12(11)2025-MRD-POD3/I/11107/2026, dated **May 8, 2026**, "replaces the 1‑day sharing and 3‑month usage rules with a uniform **30‑day lag** for sharing and using market price data in education", adds an audit-trail requirement, and gives NISM a 1-day window. **Effective July 1, 2026.** [READ] [CorpLawUpdates, May 8, 2026](https://www.corplawupdates.in/updates/sebi-30-day-lag-educational-price-data-norms-2026). Secondary source; circular not opened on sebi.gov.in.
- **Relevance to One-Take:**
  - Finance educators who script lessons need an opening registration/qualification line (ASCI), must avoid recommendation language, and must keep price references properly lagged (SEBI).
  - A script-level "must-say / must-not-say" check fits.
  - The claim that it keeps creators compliant is UNVERIFIED legally; frame it as a checklist aid, not compliance.

### 4.3 CCPA / Department of Consumer Affairs: "Endorsements Know-hows!"

- **Release:** issued Jan 20, 2023 (PIB Release ID 1892527).
  - "disclosures must be prominently and clearly displayed in the endorsement, making them extremely **hard to miss**."
  - Material connection includes money, trips, barters, free products, discounts, gifts, and family, personal or employment relationships.
  - "terms such as 'advertisement,' 'sponsored,' or 'paid promotion' can be used."
  - Influencers should not endorse products they haven't used, or where due diligence wasn't done.

  [READ] [PIB release, copy hosted by BASAI](https://basai.org/wp-content/uploads/2024/12/endorsement-guidelines-for-Celebs-and-Social-Media-Influencers.pdf); original: [PIB PRID 1892527](https://www.pib.gov.in/PressReleasePage.aspx?PRID=1892527), which returned empty in browse.
- **Format-specific rules and penalties:**
  - The guide "sets out specific requirements for endorsements made through different forms such as pictures, videos, live stream". Under the CPA, endorsers face penalties and can be "prohibited from making endorsement … for up to one year, which may extend up to three years" for later contraventions. [READ] [Khaitan & Co, Jan 31, 2023](https://www.khaitanco.com/thought-leaderships/Advertising-in-the-digital-age:-Endorsement-requirements-for-celebrities-and-influencers)
  - Exact video rule: "superimposed over the image or video"; live streams disclosed "continuously and prominently during the entire stream". [SNIPPET] UNVERIFIED (full know-hows PDF not read).
  - Penalty amounts of up to ₹10 lakh, and ₹50 lakh for repeat offences. [SNIPPET] UNVERIFIED

---

## 5. DPDP Rules (context only)

- **Notification:** "On 14 November 2025, the Ministry of Electronics and Information Technology ("MeitY") published various notifications in the Official Gazette" bringing the DPDP Act and Rules into force. [READ] [Shardul Amarchand Mangaldas, Nov 21, 2025](https://www.amsshardul.com/insight/enforcement-of-the-dpdp-act-and-notification-of-the-dpdp-rules/). PIB pages returned empty or 403.
- **Three enforcement dates** (same source):
  - **Nov 14, 2025:** establishment of the Data Protection Board (4 members, NCR) and related amendments.
  - **Nov 14, 2026 (12 months):** registration of Consent Managers, and the Board's powers over them.
  - **May 14, 2027 (18 months):** substantive obligations, including notice and consent and personal data breach reporting.
  - Grievances must be resolved within 90 days.
- **Faces and voices:** the Act defines personal data as data about an individual identifiable by or in relation to that data. I did not re-read the Act text this session (UNVERIFIED wording). A video with an identifiable face or voice would plausibly be personal data; a third person in frame adds consent questions.
  - Pitch line: the product path sends nothing off the device. **Don't claim DPDP compliance.**
  - As of today the substantive obligations are ~8 months out.

---

## 6. Pricing benchmarks

| Product | Price (as read) | Source |
|---|---|---|
| Descript | Free $0. Hobbyist $16/person/mo annual ($24 monthly). Creator $24 ($35). Business $50 ($65). USD as rendered | [READ] [descript.com/pricing](https://www.descript.com/pricing), accessed Sep 10, 2026 |
| Captions (Mirage) | Free; Max $24.99/mo; Scale $69.99 / $139.99 / $279.99/mo. Teleprompter is included on the Free tier | [READ] [captions.ai/pricing](https://www.captions.ai/pricing) |
| Gling | Free (1 hr AI-edited media/mo). Plus $20/mo monthly or $10/mo annual. Pro $40 / $20. Elite $100 / $50 | [READ] [gling.ai/pricing](https://www.gling.ai/pricing) |
| BIGVU (rendered in **INR**) | Free ₹0 (720p, watermark). Starter ₹742/mo (₹8,900/yr). AI Pro ₹1,099/mo (₹13,188/yr). Max ₹3,999/mo (₹47,988/yr). Includes teleprompter | [READ] [bigvu.tv/pricing](https://bigvu.tv/pricing) |
| Instagram Edits | Meta launched it Apr 22, 2025 (page updated Dec 17, 2025). Free-at-launch and no watermark are [SNIPPET]; the Meta post I read doesn't state a price | [READ] [Meta Newsroom](https://about.fb.com/news/2025/04/introducing-edits-streamlined-video-creation-app/) |
| CapCut Pro India | ₹399–₹833/mo per third-party trackers; ₹5,600/yr claimed | [SNIPPET] UNVERIFIED |
| PromptSmart Pro | $9.99/mo Starter, $19.99/mo Team; ~$99.99/yr | [SNIPPET]; promptsmart.com/pricing rendered no prices. UNVERIFIED |
| Teleprompter Premium | from $59.99/yr | [SNIPPET] (SourceForge). UNVERIFIED |

**Anchors:**
- Global talking-head tools sit at roughly **$10–$25/month** on annual plans.
- The only India-localized price I read (BIGVU) starts at **₹742/month**.
- Meta Edits sets a free floor for basic editing.

---

## 7. OEM precedent and vivo/iQOO

- **ArcSoft** (Shanghai-listed; the main example of licensed phone-camera algorithms):
  - Main income is "licensing of independent research and development of core technologies"; customers include "Samsung, Xiaomi, OPPO, **vivo**, Honor, Moto".
  - Two charging modes:
    - "**fixed fee mode**": a license fee for the license period, covering unlimited devices of a specified type or series.
    - "**piecework mode**": "charged according to the number of intelligent devices equipped with Arcsoft algorithm technology", with "tiered price … for different production quantity ranges".
  - FY2024 operating income RMB 815.17M (+21.62%); net profit to shareholders RMB 176.69M; R&D at 48.81% of operating income.

  [READ] [Yicai Global, ArcSoft 2024 Annual Report summary](https://www.yicaiglobal.com/bulletin/253000000719361469). Per-unit royalty amounts are not disclosed.
- **Morpho** (Tokyo): "total number of licenses for its smartphone-based image processing software had surpassed the **3.5 billion** mark" (PhotoSolid, MovieSolid, Panorama, HDR). [READ] [Morpho press release, Mar 12, 2021](https://www.morphoinc.com/en/news/20210312-epr-sw_3-5b_licenses). Per-unit pricing not disclosed.
- **Glass Imaging:** $20M Series A led by Insight Partners, with GV, Future Ventures and Abstract Ventures. The team "is currently developing licensable IP, including GlassAI … can be tailored for any current or new camera"; shown at Snapdragon Summit 2024/2025 with Qualcomm. [READ] [Glass Imaging journal, Jun 30, 2025 (round announced May 2025)](https://www.glass-imaging.com/journal/glass-imaging-raises-20-million-funding-round-to-expand-ai-imaging-technologies)
- **Almalence:** "licensed by top smartphone OEMs and shipping on more than 30M high-end devices annually"; Intel platform license and Intel Capital investment. [SNIPPET] UNVERIFIED; homepage read had no figures.
- **Visidon** (Finland): mobile imaging software vendor whose "customers include top mobile phone OEMs". [SNIPPET] UNVERIFIED
- **iQOO creator precedent:**
  - On Mar 10, 2025 iQOO partnered with seven Indian gaming creators (Dynamo Gaming, GamerFleet, Mortal, Payal Gaming, Scout, Shreeman Legend, UnGraduate Gamer). They "test, provide feedback, and certify" iQOO phones, starting with the Neo 10R, and use them as primary streaming devices. iQOO India CEO Nipun Marya: "built by gamers, for gamers." [READ via WebFetch] [Outlook Business, Mar 10, 2025](https://www.outlookbusiness.com/corporate/vivo-group-smartphone-brand-iqoo-ties-up-with-top-online-gamers)
  - Earlier: 6 e-sports teams and 100+ gamers on the iQOO 13. [SNIPPET]
  - "vivo Ignite" is a vivo technology/innovation initiative. [SNIPPET]
  - **No talking-head or video-creator program was found for vivo or iQOO India.** UNVERIFIED that one exists.
- **Takeaway:** a per-device royalty or fixed-fee license is the established model, and vivo already buys third-party camera algorithms (ArcSoft). iQOO's creator relationships are about gaming and device co-creation, not video-creator tooling. That precedent can still be borrowed: "creators certify the camera workflow".

---

## 8. Enterprise video, and restrictions on cloud AI

- **Enterprise video market:** "USD 25.80 Billion" (2025) and "USD 27.97 billion in 2026 to USD 42.23 billion by 2031, at a CAGR of 8.6%", driven by hybrid work. Analyst estimate; methodology paywalled. [READ] [MarketsandMarkets, Enterprise Video Market 2026–2031](https://www.marketsandmarkets.com/Market-Reports/enterprise-video-market-1182.html)
  - Other estimates: Straits ~$27.2B (2025); corporate communications 39.4% share; "69% of corporate training content is delivered via video". [SNIPPET] UNVERIFIED
  - I found no India-specific corporate training-video market size.
- **Cisco 2024 Data Privacy Benchmark:**
  - "**27%** had banned its use, at least temporarily."
  - "63% have established limitations on what data can be entered, 61% have limits on which GenAI tools can be used by employees".
  - People have entered employee information (45%) and non-public company information (48%).
  - Base: 2,600 professionals across 12 geographies.

  [READ] [Cisco Newsroom, Jan 25, 2024](https://newsroom.cisco.com/c/r/newsroom/en/us/a/y2024/m01/organizations-ban-use-of-generative-ai-over-data-privacy-security-cisco-study.html)
- **Cisco 2025:**
  - "**90%** of organizations see local storage as inherently safer".
  - "64% of respondents worry about inadvertently sharing sensitive information publicly or with competitors, yet nearly half admit to inputting personal employee or non-public data into GenAI tools."

  [READ] [Cisco Newsroom, Apr 2, 2025](https://newsroom.cisco.com/c/r/newsroom/en/us/a/y2025/m04/cisco-2025-data-privacy-benchmark-study-privacy-landscape-grows-increasingly-complex-in-the-age-of-ai.html)
- **Microsoft/LinkedIn 2024 Work Trend Index:** "75% of knowledge workers now use AI at work"; "**78%** of AI users are bringing their own tools to work — Bring Your Own AI (BYOAI) — … putting company data at risk". Survey of 31,000 people in 31 countries. [READ] [Microsoft Source, May 8, 2024](https://news.microsoft.com/source/2024/05/08/microsoft-and-linkedin-release-the-2024-work-trend-index-on-the-state-of-ai-at-work/)
- **Gap:** no survey or policy found that specifically restricts uploading **employee video or voice** to cloud editors. The evidence is generic GenAI restriction plus shadow AI. Treat "enterprises can't send training footage to cloud editors" as UNVERIFIED inference.

---

## 9. Wedge ranking and business model

**Ranking for this jury** (evidence strength × buyer with budget × jury fit):

1. **(d) Regulated speech, narrowed to finance and health creators and the brands and agencies that brief them.** Tag this *evidenced on disclosure and qualification, hypothesis on talking-point reshoots.*
   - **Why it's evidenced:**
     - The rules are specific, and some are literally spoken lines at fixed positions: ASCI qualification "mentioned as the opening remark in videos"; live and audio disclosures "announced at the beginning and the end".
     - Superimposed video labels have measurable durations (3 s / one-third / whole section) that a phone can enforce.
     - Enforcement is real: 98% of 1,015 (FY25) and 1,173 (H1 FY26) investigated influencer ads required modification; 76% of top stars violated disclosure norms. Advertisers are responsible for making influencers "delete or edit".
     - Brands are formalizing: 39.3% send influencer contracts through legal review.
     - SEBI keeps tightening (Aug 2024 association ban; 30-day data lag from Jul 1, 2026).
   - **Why it's partly a stretch:**
     - No credible data shows missed *brand talking points* cause measurable reshoots.
     - Most ASCI violations are missing or buried labels, a caption and overlay problem, not a spoken-line problem.
     - Fix: ship "must-say lines" plus an ASCI-timed on-screen label check, and call reshoot savings a hypothesis to validate.
   - **Jury fit:**
     - Aracor CTO: "evidence-linked, reproducible verification" is his own company's thesis.
     - Mahindra infosec: on-device, with no third-party processor.
     - VC: regulatory tailwind plus a B2B payer.
2. **(a) Educators and course creators.** The largest usage pool (PW 4.13M paying users and ~99M YouTube subscribers; Classplus 100K+ institutes), and scripted lessons are frequent. Willingness to pay is low and anchors are cheap (Edits free; Gling $10/mo annual). Use it for usage and eval data. Finance and health educators overlap with (d), so start there.
3. **(c) OEM licensing.** Real precedent (ArcSoft fixed-fee or tiered per-device; vivo is an ArcSoft customer; Morpho 3.5B licenses; Glass Imaging's $20M round to license AI camera IP). But sales cycles are long, there's no leverage without usage, and iQOO's creator programs are gaming-focused. Pitch it as distribution and exit, not the wedge.
4. **(b) Enterprise training and internal comms.** Big market (~$26B enterprise video, 2025, analyst estimate) and strong generic evidence of cloud-AI restriction (27% banned GenAI; 90% see local storage as safer). But there's no evidence on video or voice specifically, and enterprise buyers need admin, MDM and SSO a hackathon app lacks. Treat it as expansion.

**Business model and pricing anchor** (my inference; willingness to pay is UNVERIFIED):
- **Creator app, freemium:** a Pro tier around **₹299–₹499/month**. That is below BIGVU's ₹742 Starter and in line with global annual-plan tools ($10–$16/mo). The free tier competes with Edits.
- **"Brief Mode" for brands and agencies:** charge **per verified deliverable** (e.g., ₹500–₹1,500).
  - The deliverable is a line-by-line coverage report: required lines said cleanly with timestamps, opening qualification line, and on-screen label duration against ASCI rules.
  - Against Kofluence's average metro campaign cost of ₹3.8L–₹4.5L, that is well under 1% of campaign spend.
  - Channel: influencer platforms (Kofluence, Qoruz, etc.) as integration partners.
- **OEM license, later:** fixed-fee or tiered per-device royalty on the ArcSoft model, as a camera-app "script mode".

---

## 10. Surprises and corrections

1. **Bain "Creators to Commerce" doesn't appear to exist.** The source is BCG's "From Content to Commerce" (May 3, 2025).
2. **ASCI's minimum video label duration is 3 seconds**, not the 2 seconds several blogs repeat.
3. **ASCI wants disclosure early, not late:** disclosures "at the end of posts or videos" are "likely to be missed". Health and finance qualifications may be spoken "as the opening remark".
4. **SEBI replaced the 3-month rule with a 30-day lag** (circular May 8, 2026; effective Jul 1, 2026). Blogs citing "3 months" are stale.
5. **Aracor's CEO publicly argues** "the real differentiator isn't how smart the LLM is but rather how securely it handles data". Aracor offers privately hosted open-weight models (GPT-OSS 120B). Their CTO is an NLP person, probably Chennai-based.
6. **Target Capital is unidentifiable** in public databases. Confirm the juror before Friday.
7. **vivo already licenses third-party camera algorithms (ArcSoft customer list).** iQOO's creator precedent is gaming creators certifying phones, not video tooling.
8. **Kofluence's report page contradicts its press release** on sample size (750K vs 2M creators; 300 vs 1,000+ surveyed). Cite the ₹ figures cautiously.
9. **BIGVU localizes pricing to INR** (₹742–₹3,999/mo), a direct India anchor for a teleprompter-plus-AI-video app.
10. **ASCI date conflict:** the health/finance addendum is dated Aug 17, 2023 in ASCI's PDF, but LexOrbis reports an "Addendum 2" dated Apr 7, 2025.
