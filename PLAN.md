# Lector — Design & Implementation Plan

Living document; drafted 2026-07-21 with facts verified against live docs and Media3 source that day. Phases and decisions update here as the project evolves.


## 1. Context

**Lector** (`io.github.diet103.lector`) is an open-source Android app for instantly hearing on-screen text in an ElevenLabs voice. Three ways in:
1. **Select it** — a "Read aloud" action in the system text-selection toolbar (`ACTION_PROCESS_TEXT`).
2. **Share it** — a `text/plain` share target for apps without the toolbar.
3. **Screenshot it** — when text can't be selected at all (the Reddit app, images, memes, locked-down UIs): screenshot → Share → Lector runs **on-device OCR** (ML Kit) and reads what's on the screen. Added to v0.1 scope today — this covers the "I just want it to read what I'm looking at" case that selection can't.

Audio streams from ElevenLabs TTS and starts in about a second, with media-notification controls. No import step, no library — the anti-ElevenReader for short-to-medium text.

**Greenfield** — no scaffold exists (the earlier brief's mention of one was mistaken); everything starts from an empty repo at `/root/git/lector`. You're a senior web engineer new to Android; I write the code, you review; each phase carries short "Android notes" mapping new concepts to React/TS/Node equivalents.

## 2. Locked decisions

| Decision | Choice |
|---|---|
| Language / UI | Kotlin + Jetpack Compose (Material 3, **dark-first**, follows system) |
| SDK | minSdk **26** · compileSdk **37** (current androidx requires it) · targetSdk **36** (Play mandates ≥36 for new apps from Aug 31, 2026) |
| Identity | `io.github.diet103.lector` · name **Lector** · repo `github.com/diet103/lector` · Apache-2.0 |
| Entry points | Translucent `ReadAloudActivity`: PROCESS_TEXT + SEND `text/plain` + SEND `image/*` (screenshot → on-device ML Kit OCR); no accessibility service |
| Screen reading | Screenshot-share OCR **in v0.1** (chosen today). QS-tile live capture, accessibility-service one-tap, and URL-content fetch: explicitly deferred to backlog |
| Playback | Foreground service (`mediaPlayback` type), Media3 ExoPlayer + MediaSession, media notification |
| Streaming | `ResolvingDataSource` rewrites GET → POST-with-JSON-body against the ElevenLabs streaming endpoint |
| Key handling | Bring-your-own `xi-api-key`, never embedded; DataStore + Keystore AES-GCM (no EncryptedSharedPreferences) |
| Collision rule | New selection **replaces** current playback (registry/session stay list-shaped so a queue slots in later) |
| Permissions | INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS, **WAKE_LOCK** (approved today). Screenshot intake needs **no** storage permission — shared images arrive as granted `content://` URIs |
| Cost guard | Selections/OCR text truncate at sentence boundary at **5,000 chars by default, settings override** up to the model limit (approved today); truncation always announced |
| Distribution | GitHub Releases first; kept fully Play-ready; Play submission deferred (12 testers × 14 days rule documented for later) |
| Dev setup | This Windows PC: SDK + Gradle CLI inside WSL2, wireless adb to the physical Pixel 10; Android Studio on Windows optional for learning |

## 3. Verified facts

**Toolchain** (as pinned in P0, `gradle/libs.versions.toml`): AGP 9.3.0 + Gradle 9.5.0 + JDK 17, using AGP's **built-in Kotlin** (the standalone `org.jetbrains.kotlin.android` plugin is incompatible with AGP 9's new DSL — learned the hard way in P0; only the Compose compiler plugin is applied, and `jvmTarget` inherits from `compileOptions`). Compose BOM 2026.06.01, Media3 **1.10.0** (arrives in P1). compileSdk 37 because current androidx artifacts demand it; targetSdk stays 36. Watch item for P5: kotlinx.serialization's compiler plugin under built-in Kotlin — same pattern as the Compose plugin, `org.json` is the fallback.

**ElevenLabs API** (base `https://api.elevenlabs.io`):
- `POST /v1/text-to-speech/{voice_id}/stream?output_format=…` · header `xi-api-key` · body `{text, model_id, voice_settings{speed, stability, similarity_boost, style, use_speaker_boost}, language_code?, apply_text_normalization}` · `optimize_streaming_latency=0–4` knob. Returns chunked audio bytes — **confirmed live in P1**: 200 with `Content-Type: audio/mpeg` (the docs' `text/event-stream` label is wrong), curl TTFB ≈ 0.31 s for a short flash-v2.5 request.
- Formats: `mp3_44100_128` default, `mp3_22050_32` fast-start, plus pcm/opus variants.
- Models: `eleven_flash_v2_5` default (40k chars, ~75 ms model latency, 32 langs, 0.5 credit/char) · `eleven_multilingual_v2` (10k, quality) · `eleven_v3` (5k, 70+ langs, expressive). Turbo models deprecated — excluded. Per-model limits bound the settings cap slider.
- Errors: JSON `detail{type, code, message, status}` (`status` = legacy field). Map `detail.code` first, legacy `detail.status` second, HTTP last: 400 `text_too_long`/`empty_text` · 401 `invalid_api_key`/`missing_api_key` (+ legacy `quota_exceeded`) · 402 `insufficient_credits` · 403 `model_access_denied`/`subscription_required` · 404 `voice_not_found` · 429 `rate_limit_exceeded`/`concurrent_limit_exceeded`/`system_busy` (free tier: 2 concurrent) · 5xx retryable-by-user. **Observed live in P1**: 401 `unauthorized` + `status: missing_permissions` (scoped API keys — onboarding must name the scopes to enable) · 402 `paid_plan_required` ("Free users cannot use library voices via the API" — distinct from out-of-credits).
- `GET /v1/user` → `subscription.tier / character_count / character_limit / status / next_character_count_reset_unix` (onboarding shows "Creator — 132,400 of 200,000 characters left"). `GET /v1/voices` → voices with public `preview_url` MP3s.

**ML Kit Text Recognition v2** (the OCR): on-device, free, no permission, no network — the screenshot never leaves the phone; only recognized text goes to ElevenLabs (a genuinely good privacy line). Returns text blocks with bounding boxes → single-column top-to-bottom ordering heuristic for v0.1 (screenshot cropping doubles as region selection). Bundled model works on de-googled devices (GrapheneOS crowd overlaps heavily with BYO-key sideloaders); unbundled Play-Services variant is the smaller alternative. **Measured in P4** (the earlier "~4–8 MB" estimate was low): `libmlkit_google_ocr_pipeline.so` is ~10.6 MB for arm64-v8a alone, and an unsplit APK carrying all four ABIs pays ~41 MB — the debug APK went 16 MB → 60 MB. A single-ABI release APK is therefore ~+11 MB; **P7 must ship arm64-v8a ABI splits** (or an App Bundle) or the sideload download is indefensible. Note: ML Kit is **proprietary** — fine for GitHub/Play, a flag for any future F-Droid submission (FOSS alternative Tesseract is markedly worse on UI text; accepted tradeoff, revisit only if F-Droid becomes real).

**Media3 internals (checked against androidx/media source — these shape the design):**
- `CacheDataSource` has **no POST/body cache bypass** → write-through caching of our POST response is viable.
- ExoPlayer's progressive loader stamps `FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN` on every request, and our chunked response has unknown length → **by default the cache would silently never be written**. Our resolver strips the flag.
- Seeks re-open the source at a byte offset, and when a server ignores `Range` (ElevenLabs regenerates from scratch) the HTTP source re-downloads from 0 and skips bytes → a forward seek would **re-bill the full text and splice two different generations mid-word**. Retries via the default error policy re-issue the load — same cost.

**Reddit correction** (found today): the Reddit app's share button shares a **URL, not the post text** — so the text-share fallback never worked there. The screenshot-OCR path is the actual answer for Reddit; the URL-fetch idea (public posts have a `.json` mirror) sits in the backlog.

## 4. Architecture

```
any app: select text ──"Read aloud"──▶ ReadAloudActivity (translucent scrim)
   or: share text ──SEND text/plain──▶   │ text: extract (CharSequence→String, readonly flag)
   or: screenshot ──SEND image/*────▶    │ image: ML Kit OCR on-device → ordered text
                                         │ both: 5k sentence-boundary truncation, announced
                                         │ SpeakRequest → in-process registry (never re-parceled)
                                         │ MediaController.setMediaItem(id) + play
                                         │ stays alive until first isPlaying/error (≤8 s), then finish()
                                         ▼
                     PlaybackService (MediaSessionService, FGS mediaPlayback)
                       onAddMediaItems: mediaId → item with uri lector://tts/<sha256>
                                         ▼
   ExoPlayer (fast-start LoadControl, seek commands stripped, single-shot error policy)
     └─ ResolvingDataSource(TtsDataSpecResolver)   lector:// → POST DataSpec + flag strip + resume guard
         └─ CacheDataSource(SimpleCache ~50 MB LRU) write-through, key = content hash
             └─ OkHttpDataSource                    executes POST, streams MP3 chunks
```

**Billing protection — the defining design problem, defended in depth:**
1. Resolver strips `FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN` so the cache actually writes (see §3); pause/resume/replay serve from disk. Cache keying is free: the URI *is* the content hash `sha256(text|voice|model|format)`.
2. `COMMAND_SEEK_*` stripped from the session → no scrubber anywhere (notification/QS included), no seek-triggered re-POST. v0.1 shows elapsed time only; README FAQ explains why. Proper seeking arrives with tee-to-file (backlog).
3. `SingleShotLoadErrorHandlingPolicy`: zero auto-retries — errors are loud, retry is a deliberate user action (a new, knowing request).
4. Resolver resume guard: any residual `position > 0` open the cache can't serve throws a descriptive error instead of silently re-billing and splicing mismatched audio.
5. A Robolectric + MockWebServer test asserts **exactly one POST per utterance** across pause/resume/replay, and that bytes land in the cache — so a Media3 upgrade that regresses either fails CI, not your wallet.
6. Documented UX note (not a bug): replacing playback mid-stream cancels the HTTP call, but ElevenLabs bills the full submitted text regardless.

**Contracts that prevent nasty surprises** (verified platform behavior):
- **Never set an activity result** from ReadAloudActivity: with an editable caller (Gmail compose, Keep), a PROCESS_TEXT result *replaces the user's selection*. Silent data corruption; enforced by a contract test.
- **Handoff keep-alive**: Android 12+ forbids background FGS starts; finishing the translucent activity before the service promotes itself risks a race. The scrim stays until first `isPlaying`/error (OCR runs during the scrim too — a "reading your screen…" state). Media3 owns the `startForeground` dance — we never call `startForegroundService` ourselves.
- Notification-permission denied (33+): service still runs and audio plays, but shade + QS controls vanish. Mitigation: onboarding request with rationale, in-app mini player, persistent "controls limited" banner, and a `LastErrorRepository` ledger so errors always land somewhere visible (notification → toast → home banner).
- Audio: `AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_SPEECH)` + `handleAudioFocus` (pause on call, duck for navigation) + becoming-noisy (unplug pauses) + `setWakeMode(WAKE_MODE_NETWORK)` (per approved WAKE_LOCK). Speed 0.5–3.0× via client-side `playbackParameters` — free, instant, works on cached audio (server-side `speed` would re-bill per value; rejected).
- Key storage: Keystore-held AES-256-GCM key (alias `lector_master`, no biometric binding), random IV per encryption, ciphertext in Preferences DataStore. Backup rules **exclude the secrets file** — Keystore keys don't restore, and restored ciphertext without its key is poison; any decrypt failure ⇒ treated as "no key" → onboarding, never a crash.
- Privacy: screenshots are OCR'd on-device and never uploaded — only recognized text goes to ElevenLabs; shared image URIs are read once and not retained. Cached MP3s are the user's text as audio, unencrypted in `cacheDir` (OS-purgeable) — disclosed in PRIVACY.md, "Clear audio cache" in settings, 50 MB LRU bounds exposure. Selected text never persisted. `xi-api-key` never logged (redacting interceptor).
- Small mercies: media-volume-at-zero hint on the scrim; no-key flow bounces to onboarding, keeps the pending text/image, and speaks it once setup completes.

## 5. Package layout (single `:app` module — multi-module buys nothing at this size for one dev)

```
io.github.diet103.lector
├─ LectorApplication            creates AppContainer + notification channel
├─ app/AppContainer             manual DI root: OkHttpClient, Json, SimpleCache(!), ApiKeyStore,
│                               SettingsRepository, ElevenLabsApi, SpeakRequestRegistry,
│                               LastErrorRepository, TtsEngineFactory, ScreenTextRecognizer
├─ entry/
│  ├─ ReadAloudActivity         translucent; all three filters; scrim; handoff; inline errors
│  ├─ IntentTextExtractor       pure fn: Intent → ExtractedText | ExtractionError (unit-tested)
│  └─ SharedImageLoader         content:// URI → downscaled Bitmap for OCR (bounded memory)
├─ ocr/
│  ├─ ScreenTextRecognizer      ML Kit wrapper: Bitmap → ordered text (suspend)
│  └─ TextBlockAssembler        pure fn: blocks+boxes → reading order, junk filtering (unit-tested)
├─ playback/
│  ├─ PlaybackService           MediaSessionService; idle-stop ~10 min; onPlaybackResumption declined
│  ├─ TtsSessionCallback        onAddMediaItems via registry; commands minus COMMAND_SEEK_*
│  ├─ TtsEngineFactory          builds the MediaSource — THE seam where tee-to-file swaps in later
│  ├─ TtsDataSpecResolver       lector://tts/{id} → POST spec; flag strip; resume guard
│  ├─ SingleShotLoadErrorHandlingPolicy
│  └─ PlaybackErrorNotifier     player errors → TtsError → notification/toast/ledger
├─ tts/
│  ├─ ElevenLabsApi             OkHttp: getUser(), getVoices(); injectable base URL for tests
│  ├─ TtsRequestBodyFactory     SpeakRequest → JSON bytes
│  ├─ ElevenLabsErrorMapper     (code, detail) → sealed TtsError
│  ├─ CacheKeys                 sha256(text|voice|model|format)
│  └─ dto/                      @Serializable DTOs (User, Subscription, Voice, Error)
├─ model/                       SpeakRequest, VoiceSummary, UserAccount, TtsError, PlayerUiState
├─ data/                        ApiKeyStore, AesGcmCipher (interface + fake for JVM tests),
│                               SettingsRepository, SpeakRequestRegistry, LastErrorRepository
└─ ui/                          MainActivity + NavHost + theme/ · onboarding/ · home/ (status,
                                try-it box, mini player) · voices/ · settings/ · common/
```

## 6. Phases (each ends demoable on your Pixel; you review, then I proceed)

- **P0 — Walking skeleton**: SDK into WSL2, wireless adb paired, repo init (LICENSE, README stub, `.gitignore`, `.editorconfig` 4-space, adapted PLAN.md), Gradle Kotlin DSL + version catalog, empty dark edge-to-edge Compose app, `ci.yml` green. *Exit: `./gradlew installDebug` from WSL2 puts Lector on your phone; CI badge green.*
- **P1 — Streaming spike (the de-risk gate)**: curl smoke test; temporary in-app DevSpeakScreen (key in memory only); the full DataSource chain with flag strip, resume guard, single-shot policy, fast-start buffer; request-counting interceptor + latency readout; the Robolectric one-POST/cache-canary tests. *Exit: tap→audio ≤ ~1.5 s on the Pixel; pause/resume/replay with request counter pinned at 1; ElevenLabs dashboard usage confirms. **Decision gate**: if write-through caching misbehaves despite the strip, tee-to-file gets promoted from backlog to now, before anything builds on top.*
- **P2 — Real playback engine**: PlaybackService + MediaSession + media notification, audio focus/noisy/wake mode, replace semantics, seek stripping, idle-stop. *Exit: audio survives Home; controls in shade + QS; unplug pauses; second speak replaces; screen-off run completes.*
- **P3 — Text entry points**: ReadAloudActivity (PROCESS_TEXT + text SEND, `exported`, `excludeFromRecents`, no `screenOrientation` — Oreo crash otherwise), IntentTextExtractor with cap/truncation, keep-alive handoff, never-set-result contract. *Exit: Chrome selection → toolbar item → audio with Chrome undisturbed; no-key selection routes to a clear pointer, not a silent fail.*
- **P4 — Screenshot OCR path**: `image/*` SEND filter, SharedImageLoader (downscale, bounded memory), ML Kit recognizer + TextBlockAssembler (top-to-bottom ordering, junk filtering — status-bar clock, UI chrome fragments), scrim shows "reading your screen…", empty-result error ("no text found"), same cap/truncation. *Exit: Reddit post → screenshot → Share → Lector reads it; a meme reads its caption; cropped screenshot reads only the crop; screenshot of a photo with no text errors cleanly.*
- **P5 — Key management + onboarding + errors**: AesGcmCipher + ApiKeyStore + backup-exclusion rules; onboarding (welcome → key guidance naming the required scopes — Text-to-Speech, User: Read, Voices: Read; scoped keys are common and fail with `missing_permissions` → paste key with deep-link — exact dashboard path verified at implementation → `/v1/user` validation showing tier + remaining chars → notification permission with rationale); full ErrorMapper + ledger; Home screen with mini player; pending-content-after-onboarding touch. *Exit: cleared-data install → guided setup → Chrome selection speaks; wrong key → precise error; airplane mode mid-stream → visible error, no retry storm; quota figures match the ElevenLabs dashboard.*
- **P6 — Voices + settings**: voice picker with previews (public preview URLs, throwaway player; voices always sourced from the account's own `/v1/voices` — free-tier accounts cannot use library voices via the API, so classic premade IDs are never hardcoded), model choice with per-model cap bounds, speed slider applying live, format toggle with one-line tradeoff copy, cap override slider, clear-cache, sign-out. *Exit: preview three voices, pick one, hear a Reddit post in it at 1.5×; settings survive restart; changed model/format re-synthesizes once then caches.*
- **P7 — Hardening + v0.1 release** — ✅ **SHIPPED 2026-07-22, [v0.1.0](https://github.com/diet103/lector/releases/tag/v0.1.0)**, stranger-path verified (uninstall → download from GitHub → sideload → onboard from nothing). One thing this phase got badly wrong and it is the phase's real lesson: **R8 silently deleted ML Kit's registrar constructors and killed the entire OCR feature while 108 unit tests stayed green**, because unit tests run unminified and therefore test a different program than the one that ships. Everything below was the plan; the details of what actually happened are in CLAUDE.md and docs/LESSONS.md: ~~R8 keep rules scoped to `tts/dto`~~ — that was written assuming kotlinx.serialization, which was never adopted; R8 in fact needed only two `-dontwarn` lines for annotations jsoup compiles against. **ABI splits are required, not optional** (ML Kit is ~10.6 MB of native code per ABI): release builds split, giving a 14.6 MB arm64 APK against a 43 MB universal. Also: adaptive + monochrome icon (already shipped in P0); About with a hand-written licence list that names ML Kit as the one non-open-source component; PRIVACY.md (plain repo file — GitHub Pages adds a moving part for no gain, and a blob URL satisfies Play too) + `docs/play-data-safety.md` draft; signing keystore generated + **backed up off-machine by the user** (losing it strands sideload updaters); `release.yml`; README with **two GIFs — select-to-play and screenshot-to-play** (`adb screenrecord` → gif), architecture section starring the ResolvingDataSource trick, supported-apps table, no-seek-bar FAQ. *Exit: tag `v0.1.0` → GitHub Release with signed APK + SHA-256s; sideloaded release build passes the matrix.*
- **Reading history — ✅ BUILT 2026-07-22 (v0.2), device verification outstanding.** Shipped beyond the original sketch: search, delete, clear-all, a reader that highlights the sentence being spoken, and tap-a-word-to-start-there. Three decisions worth carrying forward. **Storage is a hand-rolled `SQLiteOpenHelper`, not Room** — one table, and Room's KSP + codegen + reflective lookup is the exact shape R8 silently broke in P7. **The history key is the audio cache key**, which makes "free to replay" answerable and incidentally fixes a latent bug (the memory-only registry left cached reads unplayable after a force-stop). **Seeking became possible without breaking the billing invariant** by granting it per-read on cache state alone — a fully-cached read is served from disk, so a seek inside one costs nothing; it stays stripped everywhere else, is withheld from the notification, and `GuardedUpstreamDataSource` remains the net. Highlighting is *estimated* from duration with punctuation weighting rather than measured, because ElevenLabs' `/stream/with-timestamps` returns base64-in-JSON rather than MP3 and would rewrite the layer that guards billing; it sits behind an interface so exact timings can replace it later. Defaults changed from the original plan after asking the user: **on by default with a one-time notice**, not opt-in — but still backup-excluded (including the WAL side files), one-tap clearable, cleared on sign-out, capped at 500 rows, and recognized text only, never a screenshot image. Original framing below, kept because the reasoning still holds.
  Superseded plan text: **Requested twice; user confirmed 2026-07-22 they want it.** Ship "last thing you read" on Home first, then the history screen. Storage shape is the first real decision: SharedPreferences is the wrong tool, and this may be the project's first genuine case for a database — weigh that against PLAN §11's minimal-dependency rule before reaching for Room. Original framing, still accurate: "last thing you read" on Home is the small half and worth doing on its own; a full history screen is the larger half. The argument *for* is that the audio cache already exists, so replaying a past read is free — a history screen is really a UI onto a cache the user currently can't reach, and it can honestly mark each entry "free to replay" vs "would cost ~N characters" once the 50 MB LRU has evicted it. The argument *against* is that it reverses PLAN §4's explicit "selected text never persisted": a durable list of everything you've read is a sensitive record in an app whose pitch is "the screenshot never leaves your phone". **If built: opt-in, one-tap clear-all, excluded from backup like the credential file, and never store screenshot images — only recognized text.**
- **Backlog (unscheduled, discussed and deliberately deferred)**: QS-tile live capture (MediaProjection + per-session consent dialog) · accessibility-service one-tap read (reverses a locked constraint; Play-defensible as a genuine accessibility tool; prototype only post-v0.1) · URL-content fetch (Reddit `.json`, article extraction — whole-post reading incl. off-screen text) · tee-to-file streaming engine (the agreed cleaner long-term fix: one OkHttp writer into a growing file — structurally one POST, free seeking, known duration; confined to the `TtsEngineFactory` seam) · queue mode · chunked playlist for over-cap texts · Play closed-testing track · F-Droid (NonFreeNet + proprietary-ML-Kit questions) · playback resumption.

## 7. Toolchain + Pixel 10 setup (this PC)

WSL2 (I run; JDK 17 ✓, 28 cores/31 GB — a great build box): install `cmdline-tools`, then `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"` + license acceptance + `ANDROID_HOME` in `~/.zshrc`; Gradle via wrapper.

Pixel 10 pairing (the one step needing your hands, ~6 taps, I'll walk you through live): Developer options → **Wireless debugging** → *Pair device with pairing code*; I run `adb pair <ip:port> <code>` then `adb connect`. If WSL2's NAT blocks it, fallback: run adb on Windows and point WSL at it via `ADB_SERVER_SOCKET=tcp:<windows-host>:5037` (one adb server owns the phone — kill the other); USB via `usbipd-win` as last resort. Whichever works gets documented in `docs/dev-setup.md`.

Android Studio (optional, Windows): open `\\wsl.localhost\<distro>\root\git\lector` for layout inspector/profiler learning; Gradle CLI in WSL2 remains the build path of record.

## 8. Testing plan

- **JVM unit (every push)**: IntentTextExtractor (readonly flag, SpannedString→String, cap/truncation boundaries), TextBlockAssembler (fixture OCR block layouts → reading order, junk filtering), TtsRequestBodyFactory golden JSON, CacheKeys stability/distinctness, ErrorMapper table-driven over (code × detail) incl. unknowns, DTO parsing from recorded fixtures (tolerant of extra fields), cipher logic via fake.
- **Robolectric playback integration (the crown jewel — real ExoPlayer, zero credits)**: MockWebServer plays ElevenLabs with a chunked, throttled MP3 fixture. Scenarios: ① one-POST invariant with correct method/headers/body/query ② pause→resume→end, still one request, **cache contains the key** (the flag-strip canary) ③ replay = cache hit, zero requests ④ mid-body disconnect → loud error, no retry ⑤ 401 body → `TtsError.InvalidKey` ⑥ replace mid-stream cancels first connection ⑦ delayed body → buffering state exposed. Fallback if Robolectric fights Media3: same tests run instrumented instead.
- **Instrumented (Pixel, before each tag)**: real Keystore round-trip + corrupted-ciphertext recovery; ReadAloudActivity intent contract incl. **no result ever set**; OCR end-to-end over bundled fixture screenshots (a Reddit post PNG, a meme, a no-text photo — ML Kit needs a real Android runtime); service + notification via UiAutomator (against in-process MockWebServer — debug-only `network_security_config` permits `127.0.0.1` cleartext; release manifest clean); `pm revoke` POST_NOTIFICATIONS run.
- **Manual matrix** (`TESTING.md`; run in P4 and again on the minified P7 build): toolbar path — Chrome (maybe in overflow), Gmail read, Gmail compose (**selection must survive untouched**), Messages, WhatsApp, Keep, Wikipedia, Drive PDF; text-share path — Telegram, Firefox, X, Docs; **screenshot path — Reddit app (its text share sends URLs, so OCR is the way), Instagram captions, a meme, a cropped screenshot**; documented-unsupported — Play Books (DRM blocks even screenshots via FLAG_SECURE in some views). Cross-cutting: rotation on the scrim, notifications denied end-to-end, airplane mid-stream, incoming call ducks/pauses, BT disconnect, split-screen, TalkBack over onboarding/home, over-cap truncation notice, two rapid selections → second wins.

## 9. CI/CD + release engineering

- `ci.yml` (push/PR): wrapper-validation (supply-chain check) → Temurin JDK 17 (pinned via Gradle toolchain so local = CI) → `setup-gradle` caching (`cache-read-only` off default branch) → `lint testDebugUnitTest assembleDebug` → debug APK artifact. Config-cache + build-cache on; per-ref concurrency with cancel-in-progress. Ubuntu runners ship the SDK preinstalled; AGP fetches missing platform pieces.
- `release.yml` (tag `v*`, `contents: write`): decode keystore from `KEYSTORE_B64` → signed `assembleRelease` (+ debug) — signing block activates only when the env vars exist, so local and fork builds never break → `SHA256SUMS` → Release with APKs + checksums + generated notes. Forks can't push tags and never see secrets.
- Versioning: `versionName` = tag; `versionCode` = `major×1,000,000 + minor×10,000 + patch×100` computed from the tag (headroom for hotfixes, monotonic for the deferred Play track; CI-run-number rejected — couples identity to the CI provider).
- Hygiene: `.gitignore` covers `local.properties`, `dev.properties`, `*.keystore`; keystore generation + rotation documented in `docs/release.md`; repo public from day one — no secret ever enters history by construction.

## 10. Launch checklist

**v0.1.0 (GitHub)**: minified-build matrix pass → README complete (both GIFs, BYOK guide, architecture, supported-apps table, error table, FAQ) → PRIVACY.md live on Pages → tagged signed release → repo topics → issue template asking Android version + the app where selection/OCR failed.

**Play-ready, deferred**: FGS type ✓ · target 36 ✓ · privacy policy URL ✓ · Data-Safety draft ✓ ("selected or screenshot-recognized text is transmitted to ElevenLabs under the user's own API key; screenshots are processed on-device and never uploaded; key stored encrypted on-device; audio cached locally and clearable; no analytics, no ads") · remaining when you opt in: $25 account, listing assets, closed test (12 testers × 14 days).

## 11. Defaults I chose (veto anytime)

OkHttp direct (no Retrofit — two JSON endpoints) · kotlinx.serialization (fallback `org.json` if AGP 9's built-in-Kotlin wiring fights the plugin) · manual DI `AppContainer` (no Hilt — costs more than it returns at this size, and manual wiring teaches the lifecycles honestly) · coroutines + Flow only (no WorkManager/RxJava) · media3: exoplayer + session + datasource-okhttp, **no media3-ui** (controls are Compose-drawn + system notification) · **ML Kit bundled model** (measured +~11 MB per ABI, ~41 MB unsplit — ABI splits required in P7; works offline and on de-googled devices — the unbundled Play-Services variant is the veto alternative) · single-column OCR ordering heuristic for v0.1 (cropping is the power move for complex layouts) · no analytics/crash SDK · static OSS-licenses screen (no plugin) · default `mp3_44100_128`, "faster start" toggle `mp3_22050_32` · default voice = first premade until picked · normalization auto, `language_code` unset · toolbar label "Read aloud", launcher "Lector" · Turbine for Flow test assertions.

## 12. Risk register

| # | Risk | Sev | Mitigation |
|---|---|---|---|
| 1 | Re-POST → double billing + spliced nondeterministic audio (retry / seek / position>0) | HIGH | §4 defense-in-depth; Robolectric fence in CI |
| 2 | Cache silently never written (`FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN`) | HIGH→managed | Resolver strip + cache-canary test |
| 3 | FGS promotion races the finishing translucent activity | HIGH | Keep-alive-until-playing handoff |
| 4 | PROCESS_TEXT result replaces user's selection in editable apps | HIGH | Never set result; contract test; Gmail-compose matrix row |
| 5 | OCR reading order wrong on complex layouts (multi-column, overlays) | MED | v0.1 single-column heuristic + junk filter; crop-as-region-select; fixture tests; README expectation-setting |
| 6 | Toolbar item absent in popular apps (structural) | MED | Screenshot path is the universal fallback; README support table |
| 7 | Notification denial hides all system controls | MED | Onboarding ask, mini player, error ledger + banner |
| 8 | R8 release-only serialization crashes | MED | Scoped keep rules + minified matrix in P7 |
| 9 | Backup/restore poisons Keystore ciphertext | MED | Secrets excluded from backup; decrypt-fail → re-onboard |
| 10 | Real first-audio latency is network-bound (~0.5–1.5 s + ~0.3 s OCR on that path) | MED | P1/P4 measure honestly before the README promises anything |
| 11 | ML Kit is proprietary (future F-Droid friction) | LOW | Accepted for v0.1; Tesseract fallback documented in backlog |
| 12 | AGP 9 novelty (built-in Kotlin vs Compose/serialization plugins) | LOW | Absorbed in P0; JetBrains migration guide |
| 13 | WSL2 wireless-adb flakiness | LOW | Windows adb-server bridge, then usbipd |
| 14 | Dev quota burn (free ≈ 10k credits/mo ≈ 20k flash chars) | LOW | MockWebServer keeps automated tests free |

## 13. Verification

Per-phase exits above, plus final acceptance on the Pixel: fresh install → onboard with your real key (tier correct) → Chrome Reddit paragraph via selection → audio ≤ 2 s → shade pause/resume/stop → **Reddit app post via screenshot-share → hear it** → mid-playback replacement → airplane-mode error → over-cap truncation notice → uninstall. The Robolectric one-POST suite is the permanent regression fence for the billing invariant.

