# Android APK Factory

**A lightweight GitHub Actions factory that builds installable Android debug APKs without Android Studio.** It supports two independent inputs: native Android Gradle projects (Java/Kotlin) and offline HTML websites or ZIP archives containing `index.html`.

## Quick start

1. Open **Actions → APK Factory → Run workflow**.
2. Select `native` or `web`, enter the relevant path, and press **Run workflow**.
3. Open the completed workflow run → **Artifacts** → `android-debug-apk-...` → download ZIP → extract the APK and install it.

You can use the factory itself as the home for multiple apps. Nothing needs to be installed on your PC or phone to compile them.

## Examples

| Goal | mode | source | path |
| --- | --- | --- | --- |
| Build the **HTML Shelf** Android app | `native` | `local` | `project_path=projects/html-shelf` |
| Turn example HTML into its own Android APK | `web` | `local` | `site_path=sites/demo` |
| Build a ZIP/HTML uploaded to this repo | `web` | `local` | `site_path=sites/my-site.zip` or `sites/my-site` |
| Build a public Android source repository | `native` | `github` | `repository=owner/name`, `ref=main`, `project_path=.` |
| Build a public HTML site | `web` | `github` | `repository=owner/name`, `ref=main`, `site_path=dist` |

### Input meaning

- `source=local`: project or site is committed inside this factory repository.
- `source=github`: publicly accessible GitHub repository is cloned at the specified branch/tag. Private repos are not supported by this example, and `source` does **not** accept arbitrary download URLs.
- `project_path`: path to a Gradle Android project root containing `settings.gradle` or `settings.gradle.kts`. For a repository with the project at its root use `.`.
- `site_path`: directory or ZIP of HTML/CSS/JS/media. Supports one top-level wrapping folder in ZIP, with an `index.html` inside.
- `app_name` and `application_id`: only apply to `web` mode. The HTML is bundled in a lightweight native Android WebView shell. Static local pages work offline; remote requests still need connectivity.
- `module`: Gradle application module. Defaults to `app` for native projects; web always uses `app`.
- `gradle_version`: defaults to **8.9**, compatible with the bundled Android Gradle Plugin **8.7.3**. Supply a compatible version when building another project.

**Important:** `web` packages HTML into an internal WebView. The `HTML Shelf` app is different: it opens imported pages and ZIP sites in an **external browser** as requested.

## Use it as your personal Android factory

Add Android project folders under `projects/`, and HTML folders or ZIP archives under `sites/`. Keep the folder structure of each native project intact. The factory stages each build into a temporary `.factory-work` directory; originals are not modified. ZIP extraction rejects traversal and several unsafe file types and imposes size/count limits.

An optional `tests/run.sh` inside a native project is executed before building. Successful outputs are uploaded by `actions/upload-artifact` and retained for **14 days**. A SHA256 checksum file accompanies each APK.

A GitHub Actions **debug APK** is signed with an Android debug key, suitable for local installation and testing, not Google Play release. Repeated builds on different runners can use different debug certificates; if Android rejects an update, uninstall the previous app first (back up app data before uninstalling).

## Local verification

```sh
python3 -m unittest discover -s scripts -p 'test_*.py' -v
bash projects/html-shelf/tests/run.sh
```

The runner additionally needs Java 17, Android SDK 35, and Gradle installed. You do not need those locally if using GitHub Actions.

## GitHub permissions

The workflow requests only `contents: read` and does not use a signing secret. The project being built can execute Gradle build scripts on the runner, so build **only code you trust**. Don't put private API keys inside HTML or Android source files.

## Notes

- Workflow starts **manually**, so pushes and edits won't waste Actions minutes or build unexpectedly.
- No Releases are published and no GitHub Pages site is enabled.
- Included sample project `projects/html-shelf` is carried over as provided source; compilation on GitHub needs to complete before the APK is confirmed working.
- Building Kotlin/Compose/Flutter/React Native projects with custom build requirements may need extra SDK/toolchain steps. This factory is primarily for Android Gradle projects and static websites.
