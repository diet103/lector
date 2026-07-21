# Lector — agent context

Select-to-speak Android app streaming ElevenLabs TTS (bring-your-own key). **[PLAN.md](PLAN.md) is the design source of truth** — read §4 (architecture), §6 (phases), and §8 (testing) before substantial work. This file carries session-to-session status and machine specifics.

## Status — update this section as work lands

- **P0 walking skeleton: DONE.** Public repo + green CI at github.com/diet103/lector; app installs and runs on the Pixel.
- **P1 streaming spike: code DONE, user-verified on device** — tap→audio streams, pause/resume and same-text replay hold the upstream POST counter at 1 (the billing invariant), cache replay is instant. Curl baseline: TTFB 0.31 s, `Content-Type: audio/mpeg`.
  **REMAINING in P1:** the Robolectric + MockWebServer suite (PLAN.md §8, scenarios ①–⑦) — add test deps (robolectric, media3-test-utils-robolectric, mockwebserver, turbine; pin current versions), fake the streaming endpoint with a chunked MP3 fixture through the real player chain, assert one-POST-per-utterance and that bytes land in the cache. Must run in CI.
- **Then P2:** `PlaybackService` (MediaSessionService) per PLAN §6 — the player, registry, and OkHttp client move out of `DevSpeakScreen` (which is temporary and dies in P2) into the service + a future `AppContainer`.

## Machine / device (this PC: Windows + WSL2)

- JDK 17 (system), SDK at `/root/android-sdk` (platforms 36 + 37.0, build-tools 36.0.0), Gradle via wrapper (9.5.0).
- **adb quirk:** `adb start-server` hangs on this box. First run `adb -L tcp:5037 server nodaemon` as a persistent background task, then clients work. Details in [docs/dev-setup.md](docs/dev-setup.md).
- Pixel 10 Pro XL over wireless adb at `192.168.4.20:<port>` — the port rotates whenever Wireless debugging toggles; re-run `adb connect` with the new port (pairing persists). Two transports may appear for one phone → always target with `adb -s <ip:port>`.
- `dev.properties` at repo root (**git-ignored, never commit**): `ELEVENLABS_API_KEY` (user's key, free tier, 10k credits/mo, ~40 used) and `DEV_VOICE_ID` (George, `JBFqnCBsd6RMkjVDRZzb`). Injected as debug-only BuildConfig fields.

## Build gotchas — hard-won in P0/P1, do not relitigate

- AGP 9 uses **built-in Kotlin**: do NOT apply `org.jetbrains.kotlin.android` (incompatible with the new DSL). Only the Compose compiler plugin is applied; `jvmTarget` inherits from `compileOptions` (17).
- compileSdk **37** (current androidx artifacts demand it), targetSdk 36, minSdk 26.
- Media3 `UnstableApi` opt-in is project-wide via `kotlin.compilerOptions.optIn` in `app/build.gradle.kts`.
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
