# Lector

**Hear any text on your screen, in any ElevenLabs voice, in about a second.**

Select text anywhere in Android and tap **Read aloud (Lector)**. Or share text, a link, or a
screenshot to Lector. Playback starts almost immediately and behaves like a proper media app —
play/pause from the notification shade, pauses for calls and unplugged headphones, keeps going with
the screen off.

Lector uses **your own** ElevenLabs API key. Requests go straight from your phone to ElevenLabs.
There is no middleman server, no account to create, no analytics, and no ads.

---

## Install

Grab an APK from the [latest release](https://github.com/diet103/lector/releases/latest).

| File | For |
| --- | --- |
| `app-arm64-v8a-release.apk` | **Almost certainly this one** — every phone sold in the last several years (~15 MB) |
| `app-armeabi-v7a-release.apk` | Older 32-bit phones |
| `app-x86_64-release.apk` | Emulators and ChromeOS |
| `app-universal-release.apk` | If unsure, or if the others refuse to install (~43 MB) |

Android refuses to install an APK built for the wrong CPU, so a wrong guess fails harmlessly at
install time. You will need to allow installing from your browser or file manager the first time.

Requires Android 8.0 or newer. Every release is signed, and releases from v0.1.1 onward also carry
a build attestation — see [Verify this build](#verify-this-build).

## What Lector is allowed to do on your phone

Installing an APK from the internet deserves suspicion. The fastest way to check this one is the
permission list, which Android enforces whatever the code claims — you can read it yourself in
[AndroidManifest.xml](app/src/main/AndroidManifest.xml), or in any APK inspector before installing.

| Permission | Why |
| --- | --- |
| `INTERNET` | Send your text to ElevenLabs and stream the audio back |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keep playing when you leave the app, with the usual media notification |
| `POST_NOTIFICATIONS` | Show that notification |
| `WAKE_LOCK` | Finish reading after the screen turns off |

That is the whole list. In particular there is **no storage, camera, microphone, location, contacts,
or accounts permission** — an app that wanted to watch you would need permissions Lector never asks
for, and Android would have to show you. Shared screenshots arrive as one-shot `content://` grants
for that single image, which is why reading text out of pictures needs no storage access at all.

The only host Lector connects to is `api.elevenlabs.io`, plus any page you explicitly share to it.
There is no analytics SDK, no crash reporter, and no ads.

## Verify this build

Four checks, cheapest first. All of them work on the files attached to any
[release](https://github.com/diet103/lector/releases).

**1. The file is the one that was published**

```bash
sha256sum -c SHA256SUMS.txt
```

**2. It was signed with the key every Lector release uses**

```bash
apksigner verify --print-certs app-arm64-v8a-release.apk
```

The certificate SHA-256 should be:

```
f2ff13f1622120e2f139f4d471d9eb1d9ae0e27b1b3ed55c7fca10b79c0c0603
```

The same fingerprint on every release is what tells you an update came from the same person as the
build you already trusted. Android enforces this too — it refuses to install an update signed with a
different key.

**3. It was built by GitHub from this source, not on someone's laptop**

```bash
gh attestation verify app-arm64-v8a-release.apk --repo diet103/lector
```

Release APKs from **v0.1.1 onward** carry a signed [build attestation](https://docs.github.com/actions/security-guides/using-artifact-attestations-to-establish-provenance-for-builds)
recorded in a public transparency log, binding the file's digest to the exact commit and workflow
run that produced it. This is the check a checksum can't give you: a checksum published next to the
file only proves the file matches the page. (v0.1.0 predates this and has no attestation — checks 1
and 2 still apply to it.)

**4. Watch it yourself**

Point the phone at any proxy and use it. You will see requests to `api.elevenlabs.io` and to pages
you shared, and nothing else.

**One honest caveat:** Android builds are not bit-for-bit reproducible — build the same commit twice
and you get two different files — so "build it yourself and compare hashes" does not work here, for
this or any other Android app. Attestation exists precisely because that check is unavailable.

## Setup

1. Get an API key at [elevenlabs.io → Developers → API Keys](https://elevenlabs.io/app/developers/api-keys).
   This is the **developer API key**, not your ElevenReader login.
2. Open Lector and paste it in. Lector validates the key, shows your plan and remaining characters,
   and picks a voice your plan can actually use.
3. That's it. Go highlight some text.

Your key is encrypted with a device-bound key from the Android Keystore, stored in its own file, and
excluded from cloud backup and device-to-device transfer.

## The four ways in

| How | What it does |
| --- | --- |
| **Select text** → *Read aloud (Lector)* | The selection toolbar in browsers, readers, chat apps |
| **Share → Lector** (text) | For apps whose selection toolbar doesn't offer it |
| **Share → Lector** (link) | Fetches the page and reads the article, skipping navigation and footers |
| **Share → Lector** (screenshot or image) | Reads the text out of the image, entirely on your phone |

The screenshot path is the universal fallback: it works in any app, including ones that block text
selection. **The image never leaves your device** — text recognition is a bundled offline library.

## History

Everything Lector reads is kept in a searchable list, so you can find something again and hear it
again. Open one and you get the text laid out to follow along with: the sentence being spoken is
tinted, and **tapping any word starts reading from there** — free, because it's playing audio that's
already on your phone rather than asking for it again.

Stored on your device only, never backed up, switchable off, and cleared in one tap. **Screenshots
are never saved** — only the text read out of them. Full detail in [PRIVACY.md](PRIVACY.md).

## What it costs

ElevenLabs bills per character, and Lector is careful with your money:

- **Every read is cached.** Replaying something you've already heard is free, forever, and the
  history says so on each entry — "free to replay" or roughly what a fresh read would cost.
- **Playback speed is deliberately not part of the cache key**, so dragging the speed slider never
  re-synthesises anything.
- **No seek bar while streaming.** Scrubbing a response as it arrives would re-request the audio and
  bill you twice, so the seek controls are removed from the session rather than left there to cost
  you money. Once a read is fully downloaded it's served from disk, and *then* the reader lets you
  tap into it — the same reason replays are free.
- **A length cap**, cut at a sentence boundary, so a runaway page can't drain your balance. Lector
  always tells you when it trimmed something.
- **Known-unreadable links cost nothing.** A Reddit link is refused before a single character is
  spent, because Reddit blocks anonymous reads entirely.

There is a test in CI whose only job is to assert that one text produces exactly **one** network
POST, no matter how much you pause, resume, or replay it.

## How it works

```
selection / share / screenshot / link
              │
              ▼
      ReadAloudActivity          translucent, over the calling app; never returns a result
              │                  (returning one would overwrite the user's selection)
              ▼
        AppContainer             one place builds every request, so no entry point can drift
              │
              ▼
      PlaybackService            MediaSessionService — owns the only ExoPlayer and MediaSession
              │
              ▼
   lector://tts/<sha256>         the player only ever sees an opaque URI
              │
     ResolvingDataSource         rewrites it into an authenticated POST at load time
              │
      CacheDataSource            write-through disk cache, keyed by the same hash
              │
              ▼
         ElevenLabs
```

A few decisions worth stealing:

- **The player never holds credentials.** It holds `lector://tts/<hash>`; a `ResolvingDataSource`
  turns that into the real authenticated request only at the moment bytes are needed. That also
  makes the media id safe to hand to any `MediaController`.
- **The cache key is a product decision, not a technical one.** `sha256(text | voice | model |
  format)` — speed is absent because the player applies it, so speed is free; model and format are
  present because changing them genuinely produces different audio.
- **ExoPlayer sets `FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN` on every request.** A streamed response has
  unknown length, so the write-through cache silently never gets written. It has to be stripped
  explicitly, and nothing tells you.
- **Capabilities can be removed from a `MediaSession`.** Stripping `COMMAND_SEEK_*` is what removes
  the scrubber from the notification.

More of this is written up in [docs/LESSONS.md](docs/LESSONS.md) — the Android traps this project
paid for the hard way. The full design lives in [PLAN.md](PLAN.md).

## Known limitations

- **Gmail and Keep compose fields don't show the action.** Those apps build custom selection menus
  that exclude third-party actions by criteria we couldn't identify — Lector's manifest matches the
  same MIME types as apps that *do* appear. Use the screenshot path there.
- **Reddit links can't be read.** Anonymous access to Reddit is gone: the JSON endpoints return 403
  and `old.reddit.com` serves a login wall. Screenshot the post instead.
- **Pages without paragraph markup** (Hacker News items, many forums) extract to nothing and say so,
  rather than reading a navigation menu at you.
- **No offline voices.** Lector is a front end for ElevenLabs; without a network it says so.

## Build from source

```bash
git clone https://github.com/diet103/lector && cd lector
./gradlew assembleDebug
```

`./gradlew lint testDebugUnitTest` is exactly what CI runs. `./gradlew assembleRelease` produces a
minified build, unsigned unless you supply a keystore — see
[docs/dev-setup.md](docs/dev-setup.md).

## Privacy

Nothing is collected. The only thing that leaves your phone is the text you asked to have read, sent
to ElevenLabs with your own key. Screenshots are read on-device and never uploaded. Full detail in
[PRIVACY.md](PRIVACY.md).

## Licence

[Apache-2.0](LICENSE). Lector bundles Google's ML Kit for on-device text recognition, which is
covered by Google's own terms rather than an open-source licence; everything else is Apache-2.0 or
MIT. See the About screen in the app for the full list.
