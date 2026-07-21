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
- **P4 screenshot OCR path: DONE, agent-verified on device (2026-07-21); user demo still pending.** Shared image → on-device ML Kit OCR → same playback path. `ACTION_SEND` `image/*` added to `ReadAloudActivity`'s existing SEND filter. New `entry/SharedImageLoader` (`content://`/`file://` → subsampled Bitmap, longest edge ≤ 2048), `ocr/RecognizedBlock` (our own type, so the assembler stays plain-JVM testable), `ocr/ScreenTextRecognizer` (suspend wrapper over ML Kit, `suspendCancellableCoroutine`), `ocr/TextBlockAssembler` (pure: line-wrap flattening, tiny-fragment drop, status-bar strip drop, row grouping by mutual vertical-centre containment). `ReadAloudActivity` is now a 3-state machine (`Reading` → `Speak` → `Failed`) so text and image paths share one handoff; 15 s OCR timeout; `ScreenTextRecognizer` is lazy in `AppContainer` so text-only runs never load the model. **48 unit tests green** (+9 assembler, +10 loader).
  **Device-verified by me:** no-text image → `blocks=0 assembled=0` → "No text found in that image."; text image → `blocks=7 assembled=145 chars` → 190 KB MP3 streamed + cached, media notification with `FOREGROUND_SERVICE`, scrim self-finished on `isPlaying`. Scrim renders correctly over a third-party app and fails gracefully on an unreadable URI.
  **Still unverified (user's review gate):** the real share-sheet flow with a `content://` grant, a genuine Reddit post, a meme, and a crop — i.e. the PLAN §6 P4 exit criteria.
- **Next: P5 — key management + onboarding + errors** (PLAN §6). Also carry forward: `MainActivity`'s temp POST_NOTIFICATIONS request is replaced there, and the 401/402 error map gets real.

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
- ElevenLabs auth failures observed live — all three must be in the P5 error map + onboarding copy (PLAN §3):
  - scope-restricted key → 401 `missing_permissions`
  - free tier + library voice → 402 `paid_plan_required`
  - free tier + the account's **own instantly-cloned voice** → 401 `subscription_required` / `ivc_not_permitted`, "Instantly cloned voices are not available on your current plan" (2026-07-21: the user's cloned "Dieter" voice lists fine in `/v2/voices` but will not synthesize). **Listing a voice does not mean you can use it** — the voice picker in P6 must either filter `category == "cloned"` on free tier or surface this error clearly, or users will pick a voice that always fails.
- **`inJustDecodeBounds` returns null on purpose.** `BitmapFactory.decodeStream(s, null, opts)` with `inJustDecodeBounds = true` reports through `opts` and returns **null**. So `openInputStream(uri)?.use { decodeStream(…) } ?: return null` fails for *every* image — the elvis tests the decode result, not the stream. This shipped into P4 and made the whole OCR path answer "Couldn't open that image"; only device testing caught it. **Robolectric does not reproduce it** — its BitmapFactory shadow returns a non-null bitmap in bounds-only mode, verified by reintroducing the bug against the test suite (still green). `SharedImageLoaderTest` says so in a comment; don't trust it as a guard.
- **Kotlin block comments nest**, so a literal `image/*` or `text/*` inside a KDoc opens a comment that never closes ("Unclosed comment", with misleading unresolved-reference errors elsewhere in the file). Write the MIME glob without the star in comments.
- **Driving share intents from `adb` is not the real flow.** `am start --grant-read-uri-permission` does **not** transfer a MediaStore grant (`SecurityException: has no access to content://media/...`), files `adb push`ed to `/sdcard/Android/data/<pkg>/` are `shell:ext_data_rw` and unreadable by the app, and a second `am start` gets swallowed ("current task has been brought to the front") unless you pass `-f 0x10008000`. What works: `base64 -w0 f.png | adb shell "run-as <pkg> sh -c 'base64 -d > /data/data/<pkg>/files/f.png'"` then a `file://` URI to that path.
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
