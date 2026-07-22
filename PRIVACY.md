# Privacy Policy — Lector

**Last updated: 21 July 2026** · Applies to Lector for Android, version 0.1.0 onwards.

Lector is a free, open-source app with no servers of its own. The developer of Lector collects
nothing, receives nothing, and cannot see anything you do in the app. Everything below is verifiable
in the [source code](https://github.com/diet103/lector).

## The short version

- Lector has **no accounts, no analytics, no ads, no crash reporting, and no tracking of any kind.**
- The only thing that ever leaves your phone is **the text you explicitly asked Lector to read**,
  sent directly to ElevenLabs using your own API key.
- **Screenshots never leave your phone.** They are read on the device itself.
- Your ElevenLabs API key is stored **encrypted on your device** and is never sent anywhere except
  to ElevenLabs, as the credential for your own requests.

## What leaves your device, and where it goes

**To ElevenLabs (elevenlabs.io), using your API key:**

| What | When |
| --- | --- |
| The text to be spoken | Each time you ask Lector to read something that isn't already cached |
| Your API key | With every request, as the credential — this is how ElevenLabs bills your account |
| Your chosen voice, model, and audio format | With the same request |

These requests go straight from your phone to ElevenLabs. There is no intermediate server. What
ElevenLabs does with the text is governed by
[their privacy policy](https://elevenlabs.io/privacy-policy) and your agreement with them, not by
this one.

**To a website, when you share a link to Lector:** Lector fetches that page directly so it can
extract the article text. This is an ordinary web request to that site, the same as opening the link
in a browser. It is only made for links you deliberately share to Lector.

**Nowhere else.** Lector contacts no other host. It has no telemetry endpoint.

## What stays on your device

- **Screenshots and shared images.** Text recognition runs entirely on your phone using a bundled
  offline library. The image is held in memory only for as long as it takes to read it, and is never
  uploaded, stored by Lector, or shared. If you turn on "delete after reading", Lector asks Android
  to remove the original from your gallery — Android always shows you a confirmation dialog first.
- **Your API key**, encrypted with a key held in the Android Keystore that cannot be extracted from
  the device. It is kept in its own storage file, which is excluded from Android's cloud backup and
  from device-to-device transfer, so it does not travel to any new phone or to Google's servers.
- **Cached audio** for text you have already heard, so replaying it costs you nothing. This lives in
  the app's private cache directory. Clearing it is one tap in Settings, and uninstalling the app
  removes it.
- **Your settings** (voice, model, speed, length limit).

**Lector does not keep a record of what you have read.** The text you select is used to make the
request and to compute a cache key, and is not written to any history or log.

## Permissions

| Permission | Why |
| --- | --- |
| Internet | To reach ElevenLabs, and to fetch pages for links you share |
| Foreground service (media playback) | To keep audio playing when you leave the app, with the standard media notification |
| Post notifications | To show the play/pause notification |
| Wake lock | To keep streaming while the screen is off |

Lector requests **no storage, camera, microphone, contacts, or location permission.** Shared images
arrive through Android's own share mechanism, which grants access to that one file only.

## Children

Lector is not directed at children and collects no data from anyone, including children.

## Changes

Any change to this policy will be committed to this file in the public repository, so its full
history is visible in the commit log.

## Contact

Questions or concerns: open an issue at
[github.com/diet103/lector/issues](https://github.com/diet103/lector/issues).
