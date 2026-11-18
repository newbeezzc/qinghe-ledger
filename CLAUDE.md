# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

青禾记账 (`com.qinghe.ledger`) is a local-first Android bookkeeping app with automatic capture from WeChat Pay, Alipay, Douyin and Meituan notifications and accessibility screens, plus GitHub-based in-app updates. Kotlin + Jetpack Compose + Room + DataStore, minSdk 26, target/compileSdk 35.

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

# GitHub channel debug APK (keeps in-app updates)
./gradlew.bat :app:assembleGithubDebug

# GitHub channel R8 release (keeps in-app updates, signed with the local debug keystore)
./gradlew.bat :app:assembleGithubRelease
# -> app/build/outputs/apk/github/release/app-github-release.apk

# Huawei AppGallery channel release (no update entry, no INTERNET / install-package)
./gradlew.bat :app:assembleHuaweiRelease
# -> app/build/outputs/apk/huawei/release/app-huawei-release.apk

# Unit tests — required on this Chinese path
subst X: "D:\study\博士\杂项\记账软件开发"
./gradlew.bat -p X:/ :app:testGithubDebugUnitTest
# Single class:
./gradlew.bat -p X:/ :app:testGithubDebugUnitTest --tests "com.qinghe.ledger.auto.PaymentParserTest"
subst X: /D
```

Before delivering an APK:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Do a clean build (`clean :app:assembleGithubRelease :app:assembleHuaweiRelease`) — repeated incremental packaging has produced a 63 MB APK with ZIP holes; a clean release should stay around 2.7 MB.
3. Copy to versioned paths, e.g. `dist/qinghe-1.0.9.apk` (GitHub) and `dist/qinghe-1.0.9-huawei.apk` (AppGallery upload only), remove stale dist APKs, and update the SHA-256 and version in `README.md`.
4. Publish via `bash scripts/publish-github.sh` (defaults to `newbeezzc/qinghe-ledger`). The script clean-builds **both** flavors, updates README version/SHA-256, **commits and pushes automatically**, then creates the Release with the GitHub APK only (uses `gh`, else `GITHUB_TOKEN`/`GH_TOKEN`/`.github-token`). Never attach the Huawei APK to a GitHub Release: the updater could select the wrong asset. The `sync-gitee-release.yml` GitHub Action then mirrors the published Release/APK to Gitee (requires the `GITEE_TOKEN` repository secret).

## Architecture

Single-module, single-activity Compose app. Manual DI: `QingheApp` creates `di/AppContainer` (Room DB, repository, settings, auto-recorder).

### Distribution channels (github / huawei)

`app/build.gradle.kts` defines a `channel` flavor dimension. Both flavors share `applicationId com.qinghe.ledger`, `versionCode`/`versionName`, signing config and every feature. The only intentional differences are the in-app updater and its permissions:

- `github` (`BuildConfig.HUAWEI_CHANNEL == false`): keeps `UpdateManager`, the 「我的 → 关于与更新」 entry, auto-check, `INTERNET`, `REQUEST_INSTALL_PACKAGES` and the update `FileProvider`.
- `huawei` (`BuildConfig.HUAWEI_CHANNEL == true`): hides the whole 「关于与更新」 section, guards `checkForUpdates` / auto-check with the flag, and `app/src/huawei/AndroidManifest.xml` removes `INTERNET`, `REQUEST_INSTALL_PACKAGES` and the `FileProvider`. This is required by Huawei AppGallery review guideline 3.9 (no self-update) and lets the app be declared a standalone/offline app.

Do not add channel-specific differences beyond these. Any new update entry, update permission or network call must be gated by `BuildConfig.HUAWEI_CHANNEL` or kept out of the huawei flavor. Huawei APKs are uploaded to AGC by hand and must never be attached to GitHub Releases.

### Data layer

- `data/local/Entities.kt` — Room entities. Money is always **Long cents**. Room DB is **version 2**; `MIGRATION_1_2` adds nullable `categories.parentId`. Never use destructive migration: user ledgers must survive upgrades.
- `CategoryEntity.parentId == null` = top-level ("大类"); non-null = subcategory ("小类"). Transactions reference either level. Titles are rendered as `大类-小类`.
- `LedgerRepository.seedDefaults()` runs on every app start, is idempotent, and must keep adding missing built-in categories/children for existing installs (this is how new subcategories reach old users). It also runs `migrateRetiredMealSubcategories()`: built-in 餐饮-外卖 / 餐饮-咖啡 are removed and their transactions/rules are reassigned (咖啡 → 酒水饮料, 外卖 → 其他). `ensureOtherLast()` keeps 其他 at the end of every subcategory group after any add/update/delete; `sortedKeepingOtherLast()` is unit-tested.
- `data/SettingsRepository.kt` — DataStore preferences: auto-capture toggles, Gitee/GitHub update sources, update mirror, auto-check flag, last check timestamp. `DEFAULT_GITEE_REPO` and `DEFAULT_GITHUB_REPO` are both `newbeezzc/qinghe-ledger`; Gitee is checked first. A blank stored GitHub repo falls back to the default; a blank Gitee repo disables Gitee and uses GitHub directly.
- `ui/LedgerViewModel.kt` — month selection, Room flows, CRUD, update-setting writes, and `learnCategoryRule` message.

### Auto-capture pipeline

`PaymentNotificationListener` and `PaymentAccessibilityService` feed `PaymentParser` (pure Kotlin, JVM-testable), then `AutoRecorder`:

- `PaymentParser.analyzeNotification` / `analyzeScreen` return `ParseResult(payment, reason)`. It parses amounts (¥/元/standalone), merchant, payment time, and rejects bill summaries/chats. Screen parsing is **page-shape first, amount second**: a page must match a payment result page (success title + a strong marker such as 完成/交易单号/订单号/当前状态/收款方; 付款方式 and account names like 花呗/余额宝 do **not** count) or a single bill detail page (详情 title, or 交易单号/订单号 + ≥3 detail fields + status/time). Home pages, balance/limit/yield widgets, bill/order lists, unpaid/closed orders and repayment pages are rejected before any amount is read. **Discounted payments record the paid amount**: an explicit 实付/支付金额 label wins, otherwise 订单/商品金额 − 优惠/立减/… (or the displayed net) is used, and discount lines are excluded from the list-page amount count and from bare-amount scanning. Income vs expense: an explicit `+`/`-` amount sign wins, then keywords match after stripping the bidirectional field labels 收款方 / 付款方式 / 支付方式 and brand/nav words (支付宝, 微信支付, 收付款, 付款码…) (otherwise an income bill containing “付款方式” is misread as an expense, and every Alipay screen looks like an expense). Keep it free of Android APIs so `PaymentParserTest` works.
- `PaymentAccessibilityService` is intentionally layered (no OCR): DFS over `event.source`, active root and all interactive windows → `findAccessibilityNodeInfosByText` + parent backtracking → retries at 0/0.6/1.5/3/5/7.5 s. Each window gets its own text budget (a text-heavy chat list must not starve the bill-detail window), the event source is included on every retry, an event that arrives mid-chain is queued for a rescan instead of dropped, and a page with no text at all gets one slow 5 s follow-up scan. It listens to a broad event set (`typeAllMask`); narrowing events previously broke WeChat bill recognition.
- `util/AppVisibility` is set by `MainActivity` and `QuickEditActivity`; the accessibility service skips scans while the bookkeeping UI is foreground to avoid competing with list rendering.
- `AutoRecorder` holds a `Mutex` around duplicate-check + insert. Dedup checks recent `createdAt` (90 s) for auto records and payment `occurredAt` (±120 s) for **all** records including manual ones, so the notification and accessibility paths cannot double-record the same payment, and reopening an old bill only inserts it when that payment is not already in the ledger.
- For auto records, `note` is the merchant (or empty), `occurredAt` is the parsed payment time / notification `postTime`, and the category is resolved through auto-rules plus `subcategoryHints`. If the user edits an auto record's category, `learnCategoryRule` stores/updates **merchant → category** rules: both the raw merchant and a normalized key (branch/company suffixes removed) are saved so branch variants match, platform-only names (美团/淘宝/…) are never learned, and `recordAuto` also falls back to the last non-其他 category used for the same merchant/note.
- There is **no intermediate overlay card**. After inserting an auto record, `AutoRecorder` opens `QuickEditActivity` directly when `showAutoRecordNotification` is on and `SYSTEM_ALERT_WINDOW` is granted (Android allows this background activity start with the SAW permission). If it cannot open the page, it falls back to `AutoRecordEvents` (snackbar) and the high-priority notification.
- `QuickEditActivity` (`ui/QuickEditActivity.kt`) is an `exported=false`, `taskAffinity=""`, translucent activity that docks a **bottom card (up to 92% of the screen height)** over WeChat/Alipay. It reuses `AddTransactionScreen` with `autoConfirm = true`, `compact = true` and `showKeypad = false`; the auto amount/category/account/note/payment time are prefilled and the category UI is still the same two-level grid/FlowRow. The number keypad is hidden because the amount comes from the payment; 「改金额」 reveals it on demand. In compact mode the top-right action and the keypad's 记录 key are hidden; the bottom-right actions are 取消 (opens the delete confirmation) and 确认 (`updateTransaction` + `learnCategoryRule`). 取消记录 deletes via `deleteAutoTransaction`; both finish back to WeChat/Alipay without opening `MainActivity`. X/back just closes and keeps the already-inserted record. Keep the full category UI inside the compact card; do not replace it with a simplified card or a dropdown picker.
- `data/AutoCategoryClassifier.kt` (pure Kotlin) resolves the category for every auto record: user rules first (user rules beat built-ins, longest keyword wins; learned rules also match normalized merchant keys), then specific merchant/product keywords, then platform keywords (美团/淘宝/…), then a direct category-name match, then meal time for 餐饮, then the parent's 其他 child. It always tries to land on a subcategory. Add new merchant keywords there and extend `AutoCategoryClassifierTest`.
- Supported source packages live in `PaymentParser.supportedPackages`: WeChat, Alipay, Douyin (`com.ss.android.ugc.aweme`, lite variant), Meituan (`com.sankuai.meituan`, takeout). Adding a source requires updating the parser package set + notification context, `res/xml/accessibility_service_config.xml` packageNames, manifest `<queries>`, `AutoCategoryClassifier` platform hints, and account mapping.
- `AccountType` includes `DOUYIN` and `MEITUAN`; the first auto record from those apps creates a dedicated account on demand (new installs seed them too). Do not route new sources into cash silently.
- Both capture paths go through `AutoRecorder.handle`, so every auto record attempts to open the confirmation page — do not add a second insert path that bypasses it.

### Home-screen widget & quick text entry

- `widget/TodayWidgetProvider.kt` + `widget/WidgetUpdater.kt` render a 4×1 `res/layout/widget_today.xml` with 今日支出 / 本月支出 / 本月收入. `TodayWidgetProvider.onReceive` handles `APPWIDGET_UPDATE`, `DATE_CHANGED`, `TIME_SET` and `TIMEZONE_CHANGED` via `goAsync()` + `WidgetUpdater.refreshAsync`; the provider must stay registered in the main manifest (both flavors share it).
- `LedgerRepository` takes an `onTransactionsChanged` callback, wired in `AppContainer` to `WidgetUpdater.refresh`. Every `addTransaction` / `updateTransaction` / `deleteTransaction` / `deleteAutoTransaction` / successful `recordAuto` triggers an immediate widget refresh; the widget also has a 30-minute `updatePeriodMillis` fallback. Do not add a transaction write path that bypasses this callback.
- `LedgerRepository.widgetSummary` computes today/month boundaries (`LocalDate.toEpochMillis()`, `YearMonth.startMillis()/endMillis()`) and calls `TransactionDao.widgetSummary`, a read-only aggregate query returning `WidgetSummary`. No schema change or migration is involved.
- `data/QuickTextParser.kt` is a pure-Kotlin parser for one line of Chinese (`午饭 25`, `打车18.5`, `昨天奶茶12`, `工资 8000`). It extracts amount/type/relative date (今天/昨天/前天; non-today entries use 15:00 to avoid meal-time bias), rejects date/time/quantity/order-number/discount digits, supports ¥/元/块/commas/full-width digits and explicit +/- signs, and detects income keywords plus red-packet context. `LedgerRepository.parseQuickEntry` resolves the category through `AutoCategoryClassifier`; extend `QuickTextParserTest` for new rules.
- `widget/WidgetEntryActivity.kt` is a transparent bottom-card entry (modelled on `QuickEditActivity`): auto-focused text field → 250 ms debounced parse → preview with editable expense/income and category picker → confirm inserts a `MANUAL` transaction. The account is fixed to cash via `LedgerRepository.ensureCashAccount()` (no account selector); keep the preview compact so the category row stays visible above the keyboard. It sets `AppVisibility.isForeground` while open.
- No new permissions and no Glance/WorkManager dependency; `RemoteViews` only supports framework views (LinearLayout/TextView).

### UI

- `ui/LedgerRoot.kt` owns tabs, full-screen overlays (add/edit, accounts, categories, rules), update dialog, and the top-level snackbar.
- `ui/screens/HomeScreen.kt` builds a precomputed, flat `HomeListItem` list (day headers + rows) in `remember`; scrolling must not do category lookups, string formatting, or color parsing per row. Keep this property when changing rows.
- `AddTransactionScreen` supports two-level category selection and pre-selects the existing big/small category; subcategories are shown as a **multi-row FlowRow** (no horizontal scrolling). Its `compact` mode is used by the QuickEdit modal (smaller keypad, no status/nav insets, bottom-right actions). Be careful not to clear `categoryId` while `categories` is still loading from the DB (this caused an edit-from-overlay bug).
- No Navigation library. Add screens? Prefer the existing overlay pattern. `AddTransactionScreen` is shared for new/edit.
- Settings header displays `vVERSION · BUILD_TYPE`; keep it visible for APK debugging.
- The main 「我的」 page is grouped navigation only (自动记账 / 账本 / 关于与更新). Auto-capture permissions, switches and diagnostics live in `AutoSettingsScreen`, a full-screen overlay controlled by `showAutoSettings` in `LedgerRoot`. Use `SettingsGroup` / `GroupedNavRow` / `GroupedSwitchRow` / `GroupedValueRow` from `ui/components/Common.kt`; do not go back to one card per row. 「关于与更新」 shows only 检查更新 (its subtitle carries the version) and the auto-check switch; the Gitee/GitHub/mirror rows and the separate version row are intentionally hidden.

### Updater (Gitee first, GitHub fallback)

- `update/UpdateManager.kt` checks Gitee first via `https://gitee.com/api/v5/repos/<giteeRepo>/releases/latest`; when that fails, has no release/APK, or is up to date it best-effort checks GitHub (`https://api.github.com/repos/<githubRepo>/releases/latest`). A newer GitHub release is still offered if Gitee is stale. Both require a `vX.Y.Z` tag and an `.apk` asset; the updater validates package name, higher `versionCode`, non-debuggable, and certificate match with the installed app.
- Gitee release JSON exposes uploaded files in `assets` and/or `releases/<id>/attach_files`; the latter carries `size`. Download URLs redirect to a signed `foruda.gitee.com` URL and work anonymously. Gitee does not support HTTP Range on that CDN (it returns 200), so retries restart from zero.
- For a Gitee result, `UpdateInfo.fallbackDownloadUrls` carries a constructed GitHub URL for the same tag/asset name. Gitee URLs are never prefixed with GitHub mirrors; GitHub fallback URLs are tried with the custom mirror (`ghfast.top` etc.) and then directly.
- For GitHub, downloads try the official asset API URL (`api.github.com/.../releases/assets/<id>`, `Accept: application/octet-stream`) first, then built-in mirrors (`ghfast.top`, `gh-proxy.com`, `ghproxy.net`) prefixed onto `browser_download_url`, then `browser_download_url` directly. Mid-download failures retry with HTTP `Range` resume, and the final size must match the release asset size when known. Check requests fall back from the direct API to a `gh-proxy.com` API mirror.
- The update source values live only in DataStore: a Gitee repo (`gitee_repo`, default `DEFAULT_GITEE_REPO`, blank disables), a GitHub repo (`github_repo`, never blank), and an optional GitHub update mirror (`update_mirror`): a prefix or a `{url}` template. They are intentionally **not exposed on the 「我的」 page**; changing them requires changing the defaults or writing the preference. Mirrors are untrusted transport: `validateApk` still enforces package name, higher versionCode, non-debuggable, and certificate match, so a mirror cannot substitute a different APK.
- `INTERNET` is only for update checks/downloads. No ledger/notification/screen text is uploaded.
- The updater validates the signing certificate, so **future APKs must be signed with the same key**. Release builds currently use `signingConfigs.getByName("debug")`. Do not switch signing keys without warning the user that an uninstall (data loss) is required.
- New permissions/paths: `REQUEST_INSTALL_PACKAGES`, `FileProvider` authority `${applicationId}.fileprovider`, `res/xml/file_paths.xml` maps `cache/updates`.
- Auto-check runs at most daily; a failed check does not stamp the timestamp, so the next launch retries.
- Both release repos must be public; assets are downloaded from `browser_download_url` with redirects followed manually.
- `.github/workflows/sync-gitee-release.yml` runs on `release: published` (and manual `workflow_dispatch`): waits for the Gitee mirror to sync the tag, downloads the APK from the GitHub Release, creates/reuses the Gitee Release, and uploads the APK through `/releases/{id}/attach_files`. Requires the `GITEE_TOKEN` repository secret (Gitee personal access token, `projects` scope). Gitee mirror repositories sync code and tags only, **not** GitHub Releases or their APK attachments.

## Tests

- `app/src/test/java/com/qinghe/ledger/auto/PaymentParserTest.kt` — notification/screen parsing, false positives, timestamps, categories/notes.
- `app/src/test/java/com/qinghe/ledger/data/QuickTextParserTest.kt` — widget free-text amount/type/relative-date parsing and false-positive rejection.
- `app/src/test/java/com/qinghe/ledger/data/AutoCategoryClassifierTest.kt` — merchant/product/platform hints, rules, quick-text categories.
- `app/src/test/java/com/qinghe/ledger/data/CategoryOrderingTest.kt` — 其他-last ordering.
- `app/src/test/java/com/qinghe/ledger/util/MoneyFormatTest.kt` — widget compact 万/亿 formatting.
- `app/src/test/java/com/qinghe/ledger/update/UpdateVersionTest.kt` — semver comparison.
- `app/src/test/java/com/qinghe/ledger/update/UpdateSourceTest.kt` — mirror prefix/`{url}` handling, Gitee/GitHub candidate ordering, and Gitee APK selection.
- Current expected total: 130 tests (51 parser + 27 classifier + 30 quick text + 4 money format + 15 updater + 3 category ordering).

## Invariants / gotchas

- **README.md is the public GitHub landing page.** Keep personal validation notes and local machine paths out of it; detailed troubleshooting goes in `docs/TROUBLESHOOTING.md`, version history in `docs/release-notes/`, and filing materials in `docs/filing/`.
- **Keep every project artifact inside the repository.** Never write release notes, APKs, tokens, credentials, or test downloads to `%TEMP%` or anywhere else on C:. Use `docs/release-notes/vX.Y.Z.md` for release notes (the publish script reads it automatically), `dist/archive/` for old APKs, and `local-temp/` for transient helpers — the last two are git-ignored. Credentials stay in `.github-token` and `.git-credentials` at the repo root and are git-ignored; the Gitee token lives only in the `GITEE_TOKEN` GitHub Actions secret.
- **Keep the two distribution channels feature-identical.** `github` keeps the in-app updater; `huawei` hides the update entry and drops `INTERNET` / `REQUEST_INSTALL_PACKAGES` / the update `FileProvider`. Do not add channel-specific behavior beyond that, and never upload the Huawei APK to a GitHub Release.
- Do not add OCR; the accessibility extraction is intentionally non-OCR.
- Do not put release notes, "what's new" text, or version-changelog cards into app screens. In-app help text should describe current behavior only; release notes belong in `docs/release-notes/` and the GitHub Release.
- Do not narrow accessibility event types to only window events; WeChat bill recognition regressed when that was done.
- Do not put raw screen text into auto-record notes; notes must remain merchant/empty.
- Keep category seeding idempotent; new built-in subcategories must be added there so existing users receive them.
- Use `occurredAt` for the ledger date; never replace it with insertion time for auto records.
- Any new Room schema change needs a real migration; user data is the product.
- The app is currently debug-keystore-signed release for personal use; store distribution is not configured.
