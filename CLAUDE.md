# Lector — agent context

Select-to-speak Android app streaming ElevenLabs TTS (bring-your-own key). **[PLAN.md](PLAN.md) is the design source of truth** — read §4 (architecture), §6 (phases), and §8 (testing) before substantial work. This file carries session-to-session status and machine specifics.

## Status — update this section as work lands

- **P0 walking skeleton: DONE.** Public repo at github.com/diet103/lector; app installs and runs on the Pixel.
- **P1 streaming spike: DONE (2026-07-21).** Device-verified (tap→audio, POST counter pinned at 1 across pause/resume/replay; curl TTFB 0.31 s). The Robolectric + MockWebServer billing suite covers PLAN §8 scenarios ①–⑦ (`TtsStreamingBillingTest`, plus resume-guard and CacheKeys tests — 12 tests, in CI): one-POST shape, flag-strip cache canary, replay-from-cache, no-retry on disconnect, 401 surfacing (P5 tightens to `TtsError`), replace semantics, buffering exposure. MP3 fixture is synthesized CBR frames (`TestMp3`), no binary checked in. Same session fixed CI red on main (`UnstableApi` lint — see gotchas).
- **P2 real playback engine: code complete (2026-07-21), pending on-device verification.** `PlaybackService` (MediaSessionService, FGS `mediaPlayback`) owns the one `ExoPlayer` + one `MediaSession`; Media3 draws the media notification and runs `startForeground` itself. `TtsSessionCallback` strips every `COMMAND_SEEK_*` (no scrubber → no re-bill), resolves a controller's media-id → `lector://tts/<key>` via the registry, and declines `onPlaybackResumption`. Player gained audio focus (`handleAudioFocus`), becoming-noisy pause, and `WAKE_MODE_NETWORK`. New shared DI root `AppContainer` + `LectorApplication` hold the registry/OkHttp/cache; `DevSpeakScreen` is now a `MediaController` client (no longer owns a player). Idle-stop 10 min; `onTaskRemoved` stops when nothing's playing. 16 unit tests green (added `TtsSessionCallbackTest`: seek-strip + id-resolution). `MainActivity` has a temp POST_NOTIFICATIONS request (P5 onboarding replaces it).
  **VERIFY ON PIXEL (PLAN §6 P2 exit):** audio survives Home · play/pause/stop in the notification shade + QS · unplug headphones pauses · second Speak replaces the first · screen-off run completes. Then mark verified and proceed to P3.
- **Then P3:** `ReadAloudActivity` (PROCESS_TEXT + text SEND) per PLAN §6 — the real entry points; `DevSpeakScreen` finally retires around P5's Home screen.

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
