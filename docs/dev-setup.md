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
