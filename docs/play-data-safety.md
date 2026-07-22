# Play Data Safety — draft answers

Not submitted. Lector v0.1 ships as a sideloaded APK from GitHub Releases; this is prepared so that
a future Play submission is a form-filling exercise rather than a fresh audit. Keep it in step with
[PRIVACY.md](../PRIVACY.md) — if the two ever disagree, the app's actual behaviour decides and both
documents are wrong until fixed.

## Data collection and sharing

**Does your app collect or share any of the required user data types?** → **No.**

That answer is only defensible because of a distinction Play draws explicitly, so record the
reasoning rather than just the answer:

- Play's definition of *collection* is data transmitted off the device **to the developer or to a
  third party acting on the developer's behalf**. Lector has no servers and no analytics, so nothing
  is collected under that definition.
- The text sent to ElevenLabs is **not** collection or sharing in Play's sense: it goes to a service
  the *user* holds the account with, using the *user's* own API key, at the user's explicit request,
  and it is the entire function they asked for. This is the same category as an email client sending
  mail through the user's own provider. Play's guidance treats a user-initiated transfer to a
  service the user controls as outside the collection definition.
- If a reviewer disagrees, the fallback is to declare **"App activity → other user-generated
  content"** as *collected, not shared*, required for app functionality, not used for tracking. Be
  ready to switch; it is a weaker but still honest answer.
- **The reading history (v0.2) does not change this answer.** Play's definition turns on data
  leaving the device; the history never does. It is stored in Lector's private storage, excluded
  from cloud backup and device transfer, holds recognized text only (never a screenshot image), can
  be switched off, and is cleared on sign-out. On-device-only storage is explicitly outside the
  collection definition — but expect it to be *asked about*, so the answer to have ready is the one
  above rather than one improvised at review time.

## Security practices

| Question | Answer |
| --- | --- |
| Is data encrypted in transit? | Yes — all requests are HTTPS |
| Can users request data deletion? | Yes — "Clear history" and "Clear cached audio" in Settings; "Sign out" deletes the stored key, every setting and the whole history; uninstalling removes everything |
| Committed to the Play Families Policy? | Not applicable — not directed at children |
| Independent security review? | No |

## Declarations likely to be asked about

- **Foreground service (`mediaPlayback`)** — required, and the use is the obvious one: playing the
  audio the user asked for, with a standard media notification. Play now asks for a short video of
  the foreground-service use; the select-to-play GIF from the README covers it.
- **No `QUERY_ALL_PACKAGES`,** no accessibility service, no `MANAGE_EXTERNAL_STORAGE`. The
  screenshot-delete feature deliberately uses `MediaStore.createDeleteRequest`, whose system
  confirmation dialog cannot be suppressed, precisely so that the all-files permission is never
  needed.
- **Account requirement** — the app needs an ElevenLabs API key to do anything. Play requires demo
  credentials for review; supply a throwaway key on a free plan, and note that a free-tier key
  cannot use cloned voices (the app already handles and explains this).

## Open questions before an actual submission

1. **ML Kit is not open source** and is bundled, not downloaded. It runs offline and contacts
   nothing, but confirm current ML Kit terms permit redistribution inside a Play-listed app (they
   do for the standard SDK; re-check at submission time).
2. **"Bring your own API key" apps** occasionally attract scrutiny as thin clients over a paid API.
   The counter is that Lector's substance is the on-device work — the entry points, OCR, article
   extraction, cache and billing discipline — not the API call.
3. Decide whether to keep the sideload channel as the primary one. Play adds review latency and a
   signing-key decision (Play App Signing vs upload key) that does not currently exist.
