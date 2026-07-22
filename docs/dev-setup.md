# Dev setup (reference machine: Windows PC + WSL2)

Builds run with the Gradle CLI **inside WSL2**; the app deploys to a physical Pixel 10 over wireless adb. Android Studio on Windows is optional (layout inspector, profiler) — open `\\wsl.localhost\<distro>\root\git\lector`.

## Toolchain (WSL2)

- JDK 17 (system `openjdk-17`) — required by AGP 9.x.
- Android SDK at `/root/android-sdk`: `platforms;android-36`, `build-tools;36.0.0`, `platform-tools`.
  Installed via `cmdline-tools` + `sdkmanager`; licenses accepted.
- Gradle via the checked-in wrapper (`./gradlew`). Bootstrap distribution: Gradle 9.5.0.
- `local.properties` (git-ignored) points `sdk.dir` at the SDK.

## Pixel 10 over wireless adb

1. On the phone: Settings → About phone → tap **Build number** 7× to unlock Developer options.
2. Settings → System → Developer options → enable **Wireless debugging** (phone and PC on the same Wi-Fi).
3. Tap **Wireless debugging** → **Pair device with pairing code**. Note the pairing `IP:port` + 6-digit code, and the separate connection `IP:port` shown on the main wireless-debugging screen.
4. In WSL2:

    ```bash
    adb pair <pair-ip:port>      # prompts for the 6-digit code
    adb connect <connect-ip:port>
    adb devices                  # should list the Pixel
    ```

Notes:
- **This machine**: `adb start-server` hangs on its fork-into-daemon step. Run the server in the foreground instead — `adb -L tcp:5037 server nodaemon &` — then all adb client commands work normally.
- mDNS auto-discovery does not cross the WSL2 NAT — always use the explicit `IP:port` from the phone screen.
- The connection port changes every time wireless debugging toggles; re-run `adb connect` with the new port.
- If WSL→phone TCP fails entirely: run adb on Windows instead and bridge WSL to it with
  `export ADB_SERVER_SOCKET=tcp:$(ip route show default | awk '{print $3}'):5037`
  (only one adb server may own the phone — `adb kill-server` on the loser). Last resort: USB via `usbipd-win`.

## Everyday commands

```bash
./gradlew assembleDebug          # build APK
./gradlew installDebug           # build + install on the connected Pixel
./gradlew testDebugUnitTest lint # what CI runs
adb shell am start -n io.github.diet103.lector/.MainActivity
```

Debug builds carry **only the arm64-v8a** native libraries, which keeps the APK around 27 MB instead
of 60 MB and makes every wireless install noticeably quicker. If you need one that also installs on
an x86_64 emulator, build with `-PdebugAbis=all`.

## Releasing

### The signing keystore — read this before you lose it

`lector-release.jks` and `keystore.properties` live at the repo root and are git-ignored. **They are
not recoverable.** Android identifies an app by its signing certificate, so if these are lost every
existing installation must be *uninstalled* before it can update — silently breaking the update path
for everyone who sideloaded it.

Back up both files somewhere durable and off this machine (password manager attachment, encrypted
archive in cloud storage — anywhere that is not just this WSL2 filesystem). `keystore.properties`
contains the store and key passwords in plain text, so treat it exactly like the keystore itself.

To build a signed release locally:

```bash
./gradlew assembleRelease
```

Output lands in `app/build/outputs/apk/release/` as one APK per ABI plus a universal one. With no
keystore present the build still succeeds and produces `-unsigned` APKs, which is what anyone
cloning the repo gets.

### Cutting a release

CI does the real build. It needs four repository secrets (Settings → Secrets and variables →
Actions):

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 lector-release.jks` |
| `KEYSTORE_PASSWORD` | `storePassword` from `keystore.properties` |
| `KEY_ALIAS` | `lector` |
| `KEY_PASSWORD` | `keyPassword` from `keystore.properties` |

Then:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`. The workflow **fails** if the tag
   and `versionName` disagree, which is deliberate.
2. Commit, then `git tag v0.1.0 && git push --tags`.
3. `.github/workflows/release.yml` builds, lints, tests, signs, verifies each APK is really signed,
   writes `SHA256SUMS.txt`, and publishes the GitHub Release.

Run the workflow manually (`workflow_dispatch`) to rehearse all of that without publishing — it
uploads the APKs as a build artifact and skips the release step.
