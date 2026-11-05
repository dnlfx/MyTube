# MyTube

A private, minimal YouTube app for Android. No ads, no Shorts, no Google account, no tracking.

- **Plays the best quality available.** Picks the highest resolution and frame rate your phone can decode in hardware (AV1, VP9 or H.264), with the best original-language audio.
- **Nothing to sign in to.** Video data comes from [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor). No API key, no cookies, no analytics.
- **Mini player.** Swipe the video down and it shrinks into a mini player that keeps playing while you browse. Tap or swipe it up to come back, swipe it away to close.
- **Stays on your phone.** No cloud backup. Watch history and the home feed (coming next) are stored only on the device.

## Install

Download `MyTube.apk` from the [latest release](https://github.com/dnlfx/mytube/releases/latest) and open it on your phone. Every push to `main` rebuilds it, and new versions install over old ones.

## Build locally

Requires JDK 17 and the Android SDK (API 36).

```
./gradlew assembleRelease
```

## Notes

- Signing: without secrets, CI signs with a throwaway debug key, so each new build must be installed fresh. To install updates in place, create a keystore once (`keytool -genkeypair -keystore sideload.jks -alias mytube -keyalg RSA -validity 36500`) and add the repository secrets `MYTUBE_KEYSTORE_BASE64` (the file, base64-encoded), `MYTUBE_STORE_PASSWORD` and `MYTUBE_KEY_PASSWORD`.
- NewPipeExtractor is GPL-3.0, so this app is GPL-3.0 as well. Inter font is under the SIL Open Font License (see `licenses/`).
