# صفحاتي — HTML Shelf

A minimal, **offline-first Android app** to keep generated HTML pages and ZIP sites organized locally. Native Android UI, no WebView, no analytics, no account, no third-party libraries.

## Features

- Main screen shows only page names. Tap any page to open it in **an external browser** (Chrome, Firefox, etc.).
- `+` opens Android's **system document picker**, requesting the Downloads location first. Choose one or multiple `.html`, `.htm`, or `.zip` files.
- ZIP archives are safely extracted; the shallowest `index.html` is detected automatically, including archives with a wrapping top-level folder.
- The local browser URL serves relative and `/assets/` absolute paths, including images, CSS, JS, SVG, fonts, audio and video with HTTP Range support.
- ⋮ on each row: **Rename** or **Delete**. Drag the ≡ handle to reorder; long-press a row to start multiple selection, then bulk delete.
- SQLite persists names, paths and order. HTML/assets are copied to private device storage.
- Import uses Android Storage Access Framework (SAF), **not** `MANAGE_EXTERNAL_STORAGE`. No full-storage permission required.
- Browser-accessible loopback HTTP server is restricted to `127.0.0.1`, uses unpredictable one-time launch URLs and per-site session cookies. Its foreground notification has a **Stop** action.

## Build the APK

### Android Studio

1. Open this project directory in Android Studio (requires JDK 17 and Android SDK 35).
2. Let Gradle sync using **Gradle 8.9** (AGP 8.7.3).
3. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. Install `app/build/outputs/apk/debug/app-debug.apk` on the phone.

If your environment has the `gradle` CLI: run `./build.sh`.

### GitHub Actions (no local Android SDK required)

1. Create a new repository, upload the unzipped project files (including `.github/workflows/android.yml`) to `main`.
2. On the **Actions** tab, run **Build Android APK** (or push to `main` to trigger it automatically).
3. Download the `html-shelf-debug-apk` artifact and install the `app-debug.apk` from it.

The debug APK is automatically signed with Android's debug certificate for sideloading. It is **not** a production Play Store signed release.

## Local tests

Run `tests/run.sh` on a computer with Java 17+; no Android SDK required. Tests cover archive extraction/path traversal, root paths, static assets, browser sessions, SPA routes, missing assets, partial content and MIME types.

## Important behavior

- ZIP must contain an `index.html` (possibly nested). A standalone `.html` is copied without adjacent assets; bundle assets into ZIP first.
- Files are held in the app's private internal storage. Uninstalling the app removes them; make your own backups.
- Chrome and other external browsers display the saved website via a localhost URL. **Do not stop the foreground service while viewing it**, or its pages/assets will stop loading.
- External URLs/CDNs/API calls used by imported pages still require network access; local files themselves do not.
- Only import HTML/ZIP from sources you trust: JavaScript embedded in a website executes in the browser, just like any ordinary website.
- The picker attempts to open Downloads initially; the actual starting folder can vary by Android device and file-provider implementation.
- Android 14+ `specialUse` foreground service declaration is intended for personal sideload builds; Google Play review may require an additional use-case justification.

## Source structure

- `MainActivity.java`: one-screen list, import picker, rename, delete, selection, drag-reordering, external-browser launch.
- `PageRepository.java`: SQLite helper, metadata and ordering.
- `ImportManager.java`: imports individual HTML pages or ZIPs into internal app storage.
- `SafeZipExtractor.java`: defensive ZIP extraction, index discovery, cleanup.
- `SiteService.java`: Android foreground service maintaining opened sites in the background.
- `SiteHttpServer.java`: local static asset HTTP server supporting session cookies and byte ranges.

## Verification status

The platform-independent server/import extraction logic can be tested by the included script. Android Gradle build and device installation have **not** been executed in environments lacking Android SDK. Do not mistake this source ZIP for an installable APK.
