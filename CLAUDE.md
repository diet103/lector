# Lector — agent context

Select-to-speak Android app streaming ElevenLabs TTS (bring-your-own key). **[PLAN.md](PLAN.md) is the design source of truth** — read §4 (architecture), §6 (phases), and §8 (testing) before substantial work. This file carries session-to-session status and machine specifics.

## Status — update this section as work lands

- **P0 walking skeleton: DONE.** Public repo at github.com/diet103/lector; app installs and runs on the Pixel.
- **P1 streaming spike: DONE (2026-07-21).** Device-verified (tap→audio, POST counter pinned at 1 across pause/resume/replay; curl TTFB 0.31 s). The Robolectric + MockWebServer billing suite covers PLAN §8 scenarios ①–⑦ (`TtsStreamingBillingTest`, plus resume-guard and CacheKeys tests — 12 tests, in CI): one-POST shape, flag-strip cache canary, replay-from-cache, no-retry on disconnect, 401 surfacing (P5 tightens to `TtsError`), replace semantics, buffering exposure. MP3 fixture is synthesized CBR frames (`TestMp3`), no binary checked in. Same session fixed CI red on main (`UnstableApi` lint — see gotchas).
- **P2 real playback engine: DONE, user-verified on device (2026-07-21).** `PlaybackService` (MediaSessionService, FGS `mediaPlayback`) owns the one `ExoPlayer` + one `MediaSession`; Media3 draws the media notification and runs `startForeground` itself. `TtsSessionCallback` strips every `COMMAND_SEEK_*` (no scrubber → no re-bill), resolves a controller's media-id → `lector://tts/<key>` via the registry, and declines `onPlaybackResumption`. Player gained audio focus (`handleAudioFocus`), becoming-noisy pause, and `WAKE_MODE_NETWORK`. New shared DI root `AppContainer` + `LectorApplication` hold the registry/OkHttp/cache; `DevSpeakScreen` is now a `MediaController` client (no longer owns a player). Idle-stop 10 min; `onTaskRemoved` stops when nothing's playing. 16 unit tests green (added `TtsSessionCallbackTest`: seek-strip + id-resolution). `MainActivity` has a temp POST_NOTIFICATIONS request (P5 onboarding replaces it).
  **Device-confirmed:** audio survives Home · pause from the media notification · headphone-unplug pauses. Not re-checked but low-risk (same code paths): second-Speak-replaces and screen-off completion.
- **P3 text entry points: DONE, user-verified 2026-07-21 (with one accepted limitation).** `entry/ReadAloudActivity` (translucent, `excludeFromRecents`, no `screenOrientation`) handles `ACTION_PROCESS_TEXT` (selection toolbar, label **"Read aloud (Lector)"** — renamed to disambiguate from Android's built-in) + `ACTION_SEND` `text/plain` (share sheet). `IntentTextExtractor` (pure) flattens the CharSequence, surfaces the read-only flag, caps via `SentenceCap` (sentence-boundary, 5k default). Scrim stays up until the service reports `isPlaying` (keep-alive handoff), then `finish()`; 8 s timeout; truncation announced via Toast. **Contract: never calls `setResult()`** (risk #4); guarded by the code comment + documented instrumented test. 29 unit tests green (`IntentTextExtractorTest` ×8, `SentenceCapTest` ×5).
  **Device-confirmed:** browser (read-only) selection → "Read aloud (Lector)" → audio, host selection undisturbed; share sheet works.
  **Accepted limitation (risk #6, deferred):** the action does **not** appear in Gmail/Keep **editable compose** fields — only ChatGPT + the built-in reader show there. Broadening our filter to `text/*` made Lector match the `text/html` those fields query for *at the package-resolver level* (verified), but it still doesn't surface in the live menu, while ChatGPT (also third-party) does — so the exclusion is app-side (custom selection menu / some differentiator in how those apps build the menu), not our manifest. Not worth reverse-engineering closed apps; the **P4 screenshot path is the universal fallback** for uncooperative apps. `text/*` kept as the correct broad declaration regardless.
- **Next: P4 — screenshot OCR path** (per PLAN §6/§8; ML Kit decisions locked in §11). Reads text out of a shared screenshot for apps that expose neither the toolbar nor useful share text (Reddit, memes, locked-down UIs). Components:
  - **Dep:** ML Kit `com.google.mlkit:text-recognition` (bundled Latin model, +~4–8 MB APK; pin current version). On-device, no network, no permission — the privacy line (screenshot never leaves the phone; only recognized text goes to ElevenLabs).
  - **Manifest:** add an `ACTION_SEND` `image/*` intent-filter to `ReadAloudActivity` (shared images arrive as granted `content://` URIs — no storage permission).
  - **`entry/SharedImageLoader`:** `content://` URI → downscaled `Bitmap` (BitmapFactory `inSampleSize`, cap ~2048 px for bounded memory), read once, not retained.
  - **`ocr/ScreenTextRecognizer`:** suspend wrapper over ML Kit `TextRecognizer` (`InputImage.fromBitmap`) → `Text` (blocks + boxes).
  - **`ocr/TextBlockAssembler`** (pure, **unit-tested in CI**): blocks+boxes → single-column top-to-bottom reading order + junk filtering (status-bar clock strip, tiny fragments, UI chrome). v0.1 heuristic; cropping is the power move for complex layouts.
  - **`ReadAloudActivity`:** branch on `image/*` → `lifecycleScope` coroutine: scrim "reading your screen…" → load bitmap → recognize → assemble → same 5k cap/truncation → `SpeakRequest` → play. Empty OCR → "no text found" error.
  - **Tests:** `TextBlockAssembler` unit (fixture block layouts, CI). ML Kit end-to-end is **instrumented/on-device** (deferred): fixture PNGs — a Reddit post, a meme, a no-text photo.
  - **Exit (PLAN §6 P4):** Reddit screenshot → Share → reads it; meme reads its caption; cropped screenshot reads only the crop; no-text photo errors cleanly.

## Machine / device (this PC: Windows + WSL2)

- JDK 17 (system), SDK at `/root/android-sdk` (platforms 36 + 37.0, build-tools 36.0.0), Gradle via wrapper (9.5.0).
- **adb quirk:** `adb start-server` hangs on this box. First run `adb -L tcp:5037 server nodaemon` as a persistent background task, then clients work. Details in [docs/dev-setup.md](docs/dev-setup.md).
- Pixel 10 Pro XL over wireless adb at `192.168.4.20:<port>` — the port rotates whenever Wireless debugging toggles; re-run `adb connect` with the new port (pairing persists). Two transports may appear for one phone → always target with `adb -s <ip:port>`.
- **Android Studio on Windows stomps `local.properties`** with the Windows SDK path (`C:\Users\...`), which overrides `ANDROID_HOME` and breaks WSL CLI builds with "SDK location not found". Rewrite it to `sdk.dir=/root/android-sdk` before building (observed 2026-07-21).
- `dev.properties` at repo root (**git-ignored, never commit**): `ELEVENLABS_API_KEY` (user's key, free tier, 10k credits/mo, ~40 used) and `DEV_VOICE_ID` (George, `JBFqnCBsd6RMkjVDRZzb`). Injected as debug-only BuildConfig fields.

## Build gotchas — hard-won in P0/P1, do not relitigate

- AGP 9 uses **built-in Kotlin**: do NOT apply `org.jetbrains.kotlin.android` (incompatible with the new DSL). Only the Compose compiler plugin is applied; `jvmTarget` inherits from `compileOptions` (17).
- compileSdk **37** (current androidx artifacts demand it), targetSdk 36, minSdk 26.
- Media3 `UnstableApi` acceptance is project-wide via `lint { disable += "UnsafeOptInUsageError" }` in `app/build.gradle.kts`. Its marker is the *androidx* `@RequiresOptIn`, which `kotlin.compilerOptions.optIn` cannot register — that line was a silent no-op in P1 and lint is the only real enforcer (this is what had CI red on main until 2026-07-21).
- Robolectric emulating SDK 36 needs a Java 21 test runtime; toolchain is JDK 17, so Robolectric tests pin `@Config(sdk = [35])`. Revisit if the toolchain ever moves to 21.
- Robolectric playback tests need an auto-advancing `FakeClock`, and `TtsPlayerFactory` parks Media3's stuck-playback watchdogs whenever a non-default clock is injected — fake time races ahead of real MockWebServer I/O and fires them spuriously otherwise.
- kotlinx.serialization's compiler plugin under built-in Kotlin is unproven — decided in P5; the resolver uses platform `org.json` today and that's fine.
- ElevenLabs API keys can be **scope-restricted** (401 `missing_permissions`) and free-tier accounts **cannot use library voices** via API (402 `paid_plan_required`) — both observed live; error map + onboarding copy must handle them (PLAN §3).
- **PROCESS_TEXT filter: use `text/*`, not `text/plain`** (the correct broad declaration mature apps use; the extractor flattens any CharSequence, so it's free). BUT this is **not** a fix for missing-in-editable-fields: broadening to `text/*` made Lector match `text/html` at the package-resolver level (`cmd package query-activities -t text/html` now includes it) yet it still does **not** appear in Gmail/Keep compose, while ChatGPT (also `text/*`, also third-party) does. So the differentiator is app-side, unidentified, and deferred (risk #6; P4 screenshot path is the fallback). Don't relitigate the MIME angle — it's necessary-but-not-sufficient and the real cause is inside those closed apps.

## Conventions

- 4-space indent (`.editorconfig`), Kotlin official style, Compose Material 3, **dark-first**.
- Manual DI, minimal dependencies — check PLAN.md §11 before adding any library.
- Commit messages end with: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`
- The user has nerve pain — minimize their typing (tap-through questions, agent writes the code, they review). Give honest tradeoffs; never paper over gaps.

## Commands

```bash
./gradlew assembleDebug                # build
./gradlew lint testDebugUnitTest       # exactly what CI runs
adb -s <ip:port> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <ip:port> shell am start -n io.github.diet103.lector/.MainActivity
```
