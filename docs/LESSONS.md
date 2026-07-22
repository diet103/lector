# What building Lector actually taught us

Transferable Android lessons, separated from Lector's own status (that lives in
[CLAUDE.md](../CLAUDE.md)) and its design (that lives in [PLAN.md](../PLAN.md)).

Everything here was paid for once, usually the hard way. Most of it is the kind of thing that is
either undocumented or documented somewhere you only find *after* you've lost the afternoon.

---

## 1. Toolchain

**AGP 9 has built-in Kotlin.** Applying `org.jetbrains.kotlin.android` breaks the new DSL. Apply
only the Compose compiler plugin; `jvmTarget` inherits from `compileOptions`.

**`compileSdk` is dragged by androidx, not by your `targetSdk`.** Current androidx artifacts
demanded 37 while we targeted 36. These are independent knobs and the error message when
`compileSdk` is too low names the artifact, not the fix.

**Robolectric's SDK image sets your minimum JRE.** Emulating SDK 36 needs a Java 21 test runtime.
On a JDK 17 toolchain you must pin `@Config(sdk = [35])` on every Robolectric test, or the whole
suite fails with a message about Java versions that sounds like a Gradle problem.

**Some opt-ins can only be silenced by lint.** Media3's `@UnstableApi` marker is the *androidx*
flavour of `@RequiresOptIn`, which `kotlin.compilerOptions.optIn` cannot register — that line is a
silent no-op. `lint { disable += "UnsafeOptInUsageError" }` is the only real enforcement point, so
that switch *is* the project-wide opt-in. We shipped a broken CI for a phase because the no-op
looked like it was working locally.

**Kotlin block comments nest.** A literal `image/*` inside a KDoc opens a comment that never
closes. The compiler reports "Unclosed comment" plus a scatter of unresolved references elsewhere
in the file, which sends you hunting in the wrong place. Bit us twice.

---

## 2. Testing — and what tests can't tell you

**Design types so the interesting logic is plain-JVM.** Our OCR reading-order heuristic takes our
own `RecognizedBlock`, not ML Kit's `Text.TextBlock`; the article extractor uses jsoup, which is
pure Java. Both are unit-tested with no Android runtime, no Robolectric, no device. If your core
logic imports a platform type, you've chosen a slower test suite.

**Robolectric does not faithfully emulate the platform, and the gaps are silent.** Real
`BitmapFactory.decodeStream` with `inJustDecodeBounds = true` returns **null by design** and
reports through the options object. Robolectric's shadow returns a non-null bitmap. We wrote:

```kotlin
resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
```

The elvis tests the *decode result*, not the stream — so every image failed on a real phone while
the test suite stayed green.

**Therefore: verify that your regression test actually fails against the bug.** We wrote a test
for the above, then deliberately reintroduced the bug — and the test still passed. A regression
test you haven't seen fail is decoration. We kept it, relabelled honestly as a smoke test, and
wrote down that this class of bug is device-only.

**Some invariants deserve a permanent fence.** Lector bills per character, so "one network POST
per unique text, no matter how much you pause, resume or replay" is enforced by a Robolectric +
MockWebServer suite that asserts the request count. That's the test that lets you refactor
playback without fear.

---

## 3. Media3

**Let the service own the player.** `MediaSessionService` holds one `ExoPlayer` and one
`MediaSession`; every UI surface is a `MediaController` client, exactly like the notification is.
Then closing a screen can't interrupt playback, and you never call `startForegroundService`
yourself — Media3 does the `startForeground` dance, which sidesteps the Android 12+ background-FGS
restriction.

**You can remove capabilities from the session.** Stripping every `COMMAND_SEEK_*` removes the
scrubber from the notification. For us that was a billing defence (seeking a non-seekable stream
would re-request audio), but it's the general mechanism for "this content doesn't support that."

**`ResolvingDataSource` lets you turn a fake URI into a real request.** The player holds
`lector://tts/<hash>`; the resolver rewrites it into an authenticated POST at load time. That
keeps credentials out of the player's state and makes the media id safe to hand to a controller.

**Watch for the flag that silently disables your cache.** ExoPlayer's progressive loader sets
`FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN` on every request. A chunked response has unknown length, so
the write-through cache is never written and nothing tells you. Strip the flag explicitly.

**Cache-key discipline is a product decision.** Ours is `sha256(text | voice | model | format)`.
Playback speed is deliberately *absent*, because speed is applied by the player — so dragging that
slider is free forever. Model and format *are* in the key, so changing them costs exactly one
re-synthesis and then caches. Decide what belongs in the key by asking what the user should be
charged for.

**Player errors bury the useful part.** An HTTP failure arrives as a `PlaybackException` with the
real `InvalidResponseCodeException` — and the server's response body — several `cause` links down.
Walk the chain. Untranslated, an expired API key reaches the user as "Response code: 401".

---

## 4. Entry points

**A share target is a translucent activity, and it must be careful.** For `ACTION_PROCESS_TEXT`
from an *editable* field, returning a result **replaces the user's selection**. Lector therefore
never calls `setResult()` anywhere, and says so in a comment at the top of the file, because the
failure mode is silent data corruption in someone else's app.

**Don't set `android:screenOrientation` on a translucent activity** — it crashes on Android 8.

**Declare `text/*`, not `text/plain`, for PROCESS_TEXT.** Rich-text fields query `text/html`.
Necessary but *not* sufficient: some apps build custom selection menus and exclude third parties
by criteria we never identified. Have a fallback path rather than assuming manifest work will win.

**The share sheet gives you a read-only URI.** Deleting the shared file needs
`MediaStore.createDeleteRequest`, whose confirmation dialog is mandatory. The only way around it is
the manage-all-files permission, which is not worth it for a convenience feature.

---

## 5. Secrets on device

**Android Keystore + AES-GCM, IV prefixed to the ciphertext.** The key material is non-exportable,
so the ciphertext is worthless on any other device — that, more than backup rules, is what protects
a stolen backup.

**Undecryptable must mean "signed out", not "crash".** A Keystore key invalidated by a lock-screen
change, or a blob restored onto a different phone, is an *expected* state. Return null, clear the
stored value, and re-prompt. Anything else is a permanent crash loop that only a reinstall fixes.

**Exclude the credential file from backup and device transfer** (`backup_rules.xml` +
`data_extraction_rules.xml`), and keep secrets in a *separate* prefs file from ordinary settings so
settings can still ride along in a backup.

**Verify encryption at rest on a real device by grepping the raw file.** We confirmed zero `sk_`
matches in `shared_prefs` — that is the proof the Keystore round-trip works, not the unit test with
a fake cipher.

---

## 6. Talking to a third-party API

**Map errors on the response body's status field first, HTTP code second.** One ElevenLabs 401
covers a bad key, a scope-restricted key, an exhausted quota, and a voice your plan can't use.
Only the body distinguishes them, and only the distinction lets you tell the user what to do.

**Listing a resource does not mean you may use it.** Cloned voices appear in `/v2/voices` on a free
plan and then fail at synthesis. There was a capability flag on the account
(`can_use_instant_voice_cloning`) that predicts it exactly — go looking for that flag rather than
letting the user pick something that always fails.

**Never keep an unknown failure to yourself.** The fallback error case carries the HTTP code and
whatever detail text came back, so a bug report is actionable instead of "something went wrong".

**Check the API's own metadata instead of hardcoding what you remember.** We were about to
recommend a "cheaper" model; `/v1/models` showed every model has the same per-character cost and
wildly different per-request caps. One request replaced a wrong assumption.

---

## 7. Device testing

**Driving intents from `adb` is not the real flow, and the differences waste hours.**

- `am start --grant-read-uri-permission` does **not** transfer a MediaStore grant. You get
  `SecurityException: has no access to content://media/...` — an artifact of the harness, not a
  bug in the app.
- Files `adb push`ed to `/sdcard/Android/data/<pkg>/` are owned by `shell:ext_data_rw` and the app
  cannot read them.
- A second `am start` at the same component is swallowed ("current task has been brought to the
  front") unless you pass `-f 0x10008000`.
- What works for a readable fixture:
  `base64 -w0 f.png | adb shell "run-as <pkg> sh -c 'base64 -d > /data/data/<pkg>/files/f.png'"`
- After starting the adb server, allow ~15 s for mDNS before concluding the device is unreachable.

**Log shapes, never content.** `blocks=7 assembled=145 chars` is enough to debug an OCR pipeline
and tells you nothing about what the user was reading. Gate it on `BuildConfig.DEBUG` anyway.

**A screencap captures whatever they had open.** Launch your own app first and crop.

---

## 8. Process

**Measure before you write the number down.** PLAN estimated ML Kit at "+4–8 MB". Measured: 10.6 MB
for one ABI, ~41 MB unsplit — the debug APK went 16 MB to 60 MB. That single measurement turned ABI
splits from a nice-to-have into a release requirement.

**Test the hostile assumption early.** "Share a Reddit link and read it" sounded like a small
feature. Ten minutes of `curl` proved anonymous Reddit access is entirely gone (403 on the JSON
endpoints, a login wall on `old.reddit.com`), which turned a week of work into a one-line
short-circuit and an honest message.

**Prefer failing cleanly over guessing.** Pages with no paragraph markup extract to nothing and say
so, rather than reading a navigation menu aloud. A confident wrong answer is worse than an
admission.

**Don't let a plausible diagnosis outrun the evidence.** A build froze the host machine, so we
capped WSL2's memory — reasonable. When it happened *again* with no build running, memory, CPU,
disk and the event log were all clean, and the honest answer was "I was wrong, and I don't know
yet." The right move was a background sampler to catch the next occurrence, not a second guess.
