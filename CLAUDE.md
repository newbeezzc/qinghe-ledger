# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

青禾记账 (`com.qinghe.ledger`) is a local-first Android bookkeeping app with automatic capture from WeChat Pay / Alipay notifications and accessibility screens, plus GitHub-based in-app updates. Kotlin + Jetpack Compose + Room + DataStore, minSdk 26, target/compileSdk 35.

The project directory contains Chinese characters. `android.overridePathCheck=true` in `gradle.properties` allows AGP to build, but Gradle's **test worker** cannot read the Chinese classpath. Run unit tests through an ASCII drive mapping (see Commands).

User-facing setup and behavior are documented extensively in `README.md`; keep it in sync when changing features, version numbers, and the APK hash.

## Environment

- JDK 17 (`C:\Program Files\Java\jdk-17`)
- Android SDK: `D:\Software\android-sdk` (also in `local.properties`)
- Gradle 8.9 distribution: `D:\Software\gradle-dist\gradle-8.9`
- Gradle wrapper downloads Gradle from the Tencent mirror (configured in `gradle/wrapper/gradle-wrapper.properties`)
- Gradle cache used in this environment: `D:\Software\gradle-home`
- No connected device by default; an Android 15 AVD named `qinghe35` exists under `D:\Software\android-avd`.

## Commands

```bash
export GRADLE_USER_HOME="D:/Software/gradle-home"
export ANDROID_HOME="D:/Software/android-sdk"

# Debug APK
./gradlew.bat :app:assembleDebug

# R8 release APK (signed with the local debug keystore)
./gradlew.bat :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk

# Unit tests — required on this Chinese path
subst X: "D:\study\博士\杂项\记账软件开发"
./gradlew.bat -p X:/ :app:testDebugUnitTest
# Single class:
./gradlew.bat -p X:/ :app:testDebugUnitTest --tests "com.qinghe.ledger.auto.PaymentParserTest"
subst X: /D
```

Before delivering an APK:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Do a clean build (`clean :app:assembleRelease`) — repeated incremental packaging has produced a 63 MB APK with ZIP holes; a clean release should stay around 2.7 MB.
3. Copy to a versioned path, e.g. `dist/qinghe-1.0.9.apk`, remove stale dist APKs, and update the SHA-256 and version in `README.md`.
4. Publish via `bash scripts/publish-github.sh` (defaults to `newbeezzc/qinghe-ledger`). The script clean-builds, updates README version/SHA-256, **commits and pushes automatically**, then creates the Release with the APK (uses `gh`, else `GITHUB_TOKEN`/`GH_TOKEN`/`.github-token`).

## Architecture

Single-module, single-activity Compose app. Manual DI: `QingheApp` creates `di/AppContainer` (Room DB, repository, settings, auto-recorder).

### Data layer

- `data/local/Entities.kt` — Room entities. Money is always **Long cents**. Room DB is **version 2**; `MIGRATION_1_2` adds nullable `categories.parentId`. Never use destructive migration: user ledgers must survive upgrades.
- `CategoryEntity.parentId == null` = top-level ("大类"); non-null = subcategory ("小类"). Transactions reference either level. Titles are rendered as `大类-小类`.
- `LedgerRepository.seedDefaults()` runs on every app start, is idempotent, and must keep adding missing built-in categories/children for existing installs (this is how new subcategories reach old users).
- `data/SettingsRepository.kt` — DataStore preferences: auto-capture toggles, GitHub update source, auto-check flag, last check timestamp. `DEFAULT_GITHUB_REPO` (`newbeezzc/qinghe-ledger`) is the built-in source; a blank stored value falls back to it, so users never need to type a repo.
- `ui/LedgerViewModel.kt` — month selection, Room flows, CRUD, update-setting writes, and `learnCategoryRule` message.

### Auto-capture pipeline

`PaymentNotificationListener` and `PaymentAccessibilityService` feed `PaymentParser` (pure Kotlin, JVM-testable), then `AutoRecorder`:

- `PaymentParser.analyzeNotification` / `analyzeScreen` return `ParseResult(payment, reason)`. It parses amounts (¥/元/standalone), merchant, payment time, and rejects bill summaries/chats. Keep it free of Android APIs so `PaymentParserTest` works.
- `PaymentAccessibilityService` is intentionally layered (no OCR): DFS over `event.source`, active root and all interactive windows → `findAccessibilityNodeInfosByText` + parent backtracking → retries at 0/0.8/2/3.5 s. It listens to a broad event set (`typeAllMask`); narrowing events previously broke WeChat bill recognition.
- `util/AppVisibility` is set by `MainActivity`; the accessibility service skips scans while the bookkeeping UI is foreground to avoid competing with list rendering.
- `AutoRecorder` holds a `Mutex` around duplicate-check + insert. Dedup checks both recent `createdAt` (90 s) and payment `occurredAt` (±120 s), so the notification and accessibility paths cannot double-record the same payment, and reopening an old bill does not duplicate it.
- For auto records, `note` is the merchant (or empty), `occurredAt` is the parsed payment time / notification `postTime`, and the category is resolved through auto-rules plus `subcategoryHints`. If the user edits an auto record's category, the repository learns/updates a keyword rule from merchant (or note).
- `AutoRecordOverlay` shows the bottom system overlay and its 修改 button; it requires `SYSTEM_ALERT_WINDOW` and falls back to the high-priority notification. The edit intent is passed through `MainActivity` (`EXTRA_EDIT_TRANSACTION_ID`) into `LedgerRoot`.

### UI

- `ui/LedgerRoot.kt` owns tabs, full-screen overlays (add/edit, accounts, categories, rules), update dialog, and the top-level snackbar.
- `ui/screens/HomeScreen.kt` builds a precomputed, flat `HomeListItem` list (day headers + rows) in `remember`; scrolling must not do category lookups, string formatting, or color parsing per row. Keep this property when changing rows.
- Add/edit screen supports two-level category selection and pre-selects the existing big/small category; be careful not to clear `categoryId` while `categories` is still loading from the DB (this caused an edit-from-overlay bug).
- No Navigation library. Add screens? Prefer the existing overlay pattern. `AddTransactionScreen` is shared for new/edit.
- Settings header displays `vVERSION · BUILD_TYPE`; keep it visible for APK debugging.

### GitHub updater

- `update/UpdateManager.kt` checks `https://api.github.com/repos/<repo>/releases/latest`, requires a tag `vX.Y.Z`, picks an `.apk` asset, compares versions, downloads, and validates package name, higher `versionCode`, non-debuggable, and certificate match with the installed app.
- Downloads try the asset API URL (`api.github.com/.../releases/assets/<id>`, `Accept: application/octet-stream`) first, then fall back to `browser_download_url`; `github.com` release downloads are unreliable on mainland networks while the API/CDN hosts usually work.
- `INTERNET` is only for update checks/downloads. No ledger/notification/screen text is uploaded.
- The updater validates the signing certificate, so **future APKs must be signed with the same key**. Release builds currently use `signingConfigs.getByName("debug")`. Do not switch signing keys without warning the user that an uninstall (data loss) is required.
- New permissions/paths: `REQUEST_INSTALL_PACKAGES`, `FileProvider` authority `${applicationId}.fileprovider`, `res/xml/file_paths.xml` maps `cache/updates`.
- Update source is built in (`DEFAULT_GITHUB_REPO`) and overridable in Settings; it can never be blank. Auto-check runs at most daily.
- GitHub releases must be public; assets are downloaded from `browser_download_url` with redirects followed manually.

## Tests

- `app/src/test/java/com/qinghe/ledger/auto/PaymentParserTest.kt` — notification/screen parsing, false positives, timestamps, categories/notes.
- `app/src/test/java/com/qinghe/ledger/update/UpdateVersionTest.kt` — semver comparison.
- Current expected total: 21 tests (17 parser + 4 updater).

## Invariants / gotchas

- Do not add OCR; the accessibility extraction is intentionally non-OCR.
- Do not narrow accessibility event types to only window events; WeChat bill recognition regressed when that was done.
- Do not put raw screen text into auto-record notes; notes must remain merchant/empty.
- Keep category seeding idempotent; new built-in subcategories must be added there so existing users receive them.
- Use `occurredAt` for the ledger date; never replace it with insertion time for auto records.
- Any new Room schema change needs a real migration; user data is the product.
- The app is currently debug-keystore-signed release for personal use; store distribution is not configured.
