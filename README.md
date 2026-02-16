# CMS Display (Flutter)

Flutter app that mirrors the web **Display** player: enter a device MAC address, fetch media from the CMS API, and play images and videos in a loop.

## How it works

1. **Settings**: On first run (or when no MAC is saved), you enter:
   - **MAC Address** – e.g. `A4:5E:60:12:3F:B9`
   - **Server URL** – base URL of your CMS, e.g. `http://localhost/cms/public` or `http://192.168.1.5/cms/public` when using another device on the network.

2. **API**: The app calls:
   - `GET {baseUrl}/api/devices/{mac}/media`
   - Response: `{ "data": [ { "id", "title", "file_type", "file_size", "preview_url", "updated_at" } ] }`

3. **Playback**:
   - **Images**: Shown for 10 seconds, then next item.
   - **Videos**: Played muted, full screen; on end, next item.
   - Only `image/*` and `video/*` are played; list loops.

4. **Media URLs**: Each item’s file is streamed from:
   - `GET {baseUrl}/media/devices/{mac}/{mediaId}`  
   (same as the web display; the Laravel backend serves the file.)

## Run

```bash
cd cms_display_app
flutter pub get
flutter run
```

For a real device or emulator, set **Server URL** to your machine’s IP (e.g. `http://10.0.2.2/cms/public` for Android emulator, or `http://YOUR_PC_IP/cms/public` for a phone on the same Wi‑Fi).

## Project layout

- `lib/main.dart` – App entry, chooses Settings vs Display.
- `lib/screens/settings_screen.dart` – MAC + Server URL form.
- `lib/screens/display_screen.dart` – Full-screen player (images + video), fetches media by MAC and loops.
- `lib/services/api_service.dart` – `getDeviceMedia(mac)` calling `/api/devices/{mac}/media` and building preview URLs from `baseUrl`.
- `lib/services/settings_service.dart` – Persists MAC and base URL (e.g. `shared_preferences`).
- `lib/models/media_item.dart` – Model for one media item from the API.

This matches the web flow: **/display** → enter MAC → **/display/{mac}** → `fetch(/api/devices/{mac}/media)` → play `preview_url` for each item in a loop.
