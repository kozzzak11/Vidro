# Vidro

Lightweight Android browser for watching web video on a metered connection. Pages open in a WebView; direct MP4, WebM, HLS and DASH streams can play in a native Media3 player. Network requests are filtered with EasyList, EasyPrivacy and uBlock lists.

UI is Russian. Data sizes are shown in KB/MB.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Release signing reads `VIDRO_KEYSTORE_FILE` or `VIDRO_KEYSTORE_BASE64`, plus `VIDRO_KEYSTORE_PASSWORD`, `VIDRO_KEY_ALIAS` and `VIDRO_KEY_PASSWORD`. A tag `v*` builds a non-debuggable signed APK and publishes `Vidro.apk` on a GitHub Release without replacing an existing release.
