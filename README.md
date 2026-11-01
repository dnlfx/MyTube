# MyTube

A private, minimal YouTube app for Android. No ads, no Shorts, no Google account, no tracking.

- **Plays the best quality available.** Picks the highest resolution and frame rate your phone can decode in hardware (AV1, VP9 or H.264), with the best original-language audio.
- **Nothing to sign in to.** Video data comes from [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor). No API key, no cookies, no analytics.
- **Stays on your phone.** No cloud backup. Watch history and the home feed (coming next) are stored only on the device.

## Install

Every push builds a signed APK on GitHub Actions. Open the latest run under **Actions → Build APK**, download the `mytube-apk` artifact, unzip it, and open the APK on your phone. Later builds install over earlier ones.

## Build locally

Requires JDK 17 and the Android SDK (API 36).

```
./gradlew assembleRelease
```

## Notes

- Signing: without secrets, CI signs with a throwaway debug key, so each new build must be installed fresh. To install updates in place, create a keystore once (`keytool -genkeypair -keystore sideload.jks -alias mytube -keyalg RSA -validity 36500`) and add the repository secrets `MYTUBE_KEYSTORE_BASE64` (the file, base64-encoded), `MYTUBE_STORE_PASSWORD` and `MYTUBE_KEY_PASSWORD`.
- NewPipeExtractor is GPL-3.0, so this app is GPL-3.0 as well. Inter font is under the SIL Open Font License (see `licenses/`).
