# Privacy Policy — Lector

**Last updated: 22 July 2026** · Applies to Lector for Android, version 0.1.0 onwards.

Lector is a free, open-source app with no servers of its own. The developer of Lector collects
nothing, receives nothing, and cannot see anything you do in the app. Everything below is verifiable
in the [source code](https://github.com/diet103/lector).

## The short version

- Lector has **no accounts, no analytics, no ads, no crash reporting, and no tracking of any kind.**
- The only thing that ever leaves your phone is **the text you explicitly asked Lector to read**,
  sent directly to ElevenLabs using your own API key.
- **Screenshots never leave your phone.** They are read on the device itself, and the image is never
  saved — not even to your reading history.
- From version 0.2, Lector **keeps a history of what it has read**, stored only on your phone,
  excluded from backup, and switchable off. See [Reading history](#reading-history).
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
- **Your reading history** — see below.

## Reading history

**This changed in version 0.2.** Versions up to 0.1.0 kept no record of what you had read. From 0.2,
Lector saves the text of each thing it reads so you can find it, search it, and hear it again — and
so it can tell you honestly whether replaying something is free or would spend characters. Lector
shows a one-time notice explaining this the first time you open it after updating.

What that means in practice:

- The history is stored in a database in Lector's own private storage, readable only by Lector.
- It is **excluded from Android's cloud backup and from device-to-device transfer**, exactly like
  your API key. It stays on the phone it was made on and does not travel to a new device.
- **Screenshots are never saved.** For an image you share, Lector stores only the text it recognised
  — not the picture, and not a reference to it.
- It holds the most recent 500 reads; older ones are dropped automatically.
- You can turn it off entirely in Settings, and clear it in one tap. It is also cleared when you
  sign out.
- Nothing in the history is ever transmitted anywhere. It exists only so the app can show it back
  to you.

If you would rather Lector kept no record at all, turn the switch off — either from the notice when
it first appears or in Settings — and it will stop saving anything.

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
