# CMS Display (Native Android)

Native Android (Kotlin) version of the CMS Display app. Same behavior as the Flutter app:

- **Settings**: Enter device MAC address; optional server URL (default: https://abettech.com/cms/public).
- **Display**: Fetches media for the device from the API; plays images (10s each) and videos (to end), then loops.
- **Offline**: When online, media list and files are cached. When offline, playback uses cached media.
- **Overlays**: MAC badge (top-left), settings (top-right), position/title (bottom-right), “Open video in app” when playing video.

## Build and run

1. Open the project in **Android Studio** (File → Open → select `cms_display_android`).
2. Sync Gradle and run on a device or emulator (min SDK 24).

Or from the project root:

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Structure

- `app/src/main/java/com/cms/display/`
  - `MainActivity.kt` – Single activity: settings view or display view; fetch, play, next.
  - `CmsDisplayApp.kt` – Application class.
  - `ConnectivityHelper.kt` – Online check.
  - `api/ApiService.kt` – GET device media (path and query style).
  - `model/MediaItem.kt` – Data class for API/cache.
  - `settings/SettingsService.kt` – SharedPreferences (MAC, base URL).
  - `offline/OfflineMediaService.kt` – Cache media list and files; merge with API.

Uses **OkHttp** for HTTP, **Glide** for images, **Media3 (ExoPlayer)** for video.
