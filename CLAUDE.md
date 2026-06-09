# CLAUDE.md

## Build

```bat
rem Always set JAVA_HOME to the Android Studio JBR (project requires Java 17+)
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk

rem Build + install debug on connected device
.\gradlew.bat installDebug

rem Build release APK (requires signing config in local.properties)
.\gradlew.bat assembleRelease

rem Run all checks (no tests exist yet)
.\gradlew.bat check
```

`gradlew.bat` must be present — it was missing from the initial repo and was recreated. Do not delete it.

`gradle.properties` must contain `android.useAndroidX=true` — this is already in the file; do not remove it.

## Project structure

```
app/src/main/java/com/victorypoint/zldreventreporter/
├── data/
│   ├── auth/               AuthRepository, AuthInterceptor, TokenStore
│   ├── db/                 ZldrReporterDatabase, EventStatEntity, EventStatDao,
│   │                       EventStatsRepository
│   ├── events/             EventSyncRepository
│   │   └── dto/            ZwiftEventDto, EventSubgroupDto,
│   │                       RaceResultsResponseDto, RaceResultEntryDto,
│   │                       ProfileDataDto, ActivityDataDto
│   └── SyncMetadataStore
├── ui/
│   ├── login/              LoginScreen, LoginViewModel
│   ├── report/             ReportsScreen, ReportViewModel,
│   │                       EventDetailScreen, EventDetailViewModel,
│   │                       ParticipantDetailScreen, ParticipantDetailViewModel,
│   │                       EventPeriodDetailScreen, EventPeriodDetailViewModel
│   ├── settings/           SettingsScreen, SettingsViewModel,
│   │                       ReportConfig, ReportGeneratorDialog
│   ├── navigation/         AppNavGraph
│   └── theme/              Theme, Color, Type
├── MainActivity
├── SyncWorker
└── ZldrReporterApplication
```

## Architecture

- **MVVM** with Compose. ViewModels expose `StateFlow`; screens collect with `collectAsStateWithLifecycle()`.
- **Manual DI** — `ZldrReporterApplication` owns all singletons (repos, DB, Retrofit) as `lazy` properties. ViewModels access them via `application as ZldrReporterApplication`.
- **Repository pattern** — `EventSyncRepository` owns the full sync lifecycle; `AuthRepository` owns token lifecycle. No ZwiftHacks dependency anywhere.
- **No DI framework** (no Hilt/Dagger/Koin). Keep it that way unless the graph grows significantly.
- **Room** for local cache (`EventStatEntity`). Schema version 3. Migration 1→2 adds `signedUpFrozen` column (dormant — always `false`, ignored by all code). Migration 2→3 adds `durationInSeconds INTEGER NOT NULL DEFAULT 3600` column. If you add further columns, increment version and add a migration to `ZldrReporterDatabase.MIGRATION_*`.
- **Encrypted SharedPreferences** (Security Crypto) for OAuth tokens — do not move tokens to plain DataStore or unencrypted prefs.

## Key conventions

- All UI state is a sealed interface (`LoginUiState`, `ReportUiState`, `SyncProgress`). Add new states to the sealed interface rather than adding boolean flags.
- Sync logic lives entirely in `EventSyncRepository`. Do not add Retrofit calls to ViewModels.
- Rate limiting: 1.5-second delay (`RATE_LIMIT_DELAY_MS`) between authenticated Zwift API fetches in Pass 2. Pass 1b has no rate limit — all calls are concurrent.
- **Sync is three passes**:
  - **Pass 1** — discovers upcoming ZLDR events via two parallel `getUpcomingEvents(tags=…)` calls (`zldr`, `zldriders`) plus a name-prefix scan of the general feed (catches events missing the tag). The name scan is cached: it runs at most once every 24 hours (`NAME_SCAN_INTERVAL_MS`); subsequent syncs skip it and log how long ago it last ran. Stores new events with `countSignedUp=0`.
  - **Pass 1b** — fetches live sign-up counts for **all** upcoming events **concurrently** (no rate limit, no window restriction) via `getEventWithCounts(id)` and writes `totalSignedUpCount` via `updateSignedUpForDisplay()`.
  - **Pass 2** — fetches post-event counts for past events not yet fully processed. Only eligible after a 30-minute grace period from event start (`GRACE_PERIOD_MS`), retried up to 48 hours, throttled to once per 5 minutes. `countSignedUp` is set from the post-event `totalSignedUpCount` (falling back to `totalEntrantCount`). `countCompleted` is populated from `GET /api/race-results/entries?event_subgroup_id={id}` — one call per subgroup, 1.5 s apart; the entry count across all subgroups is summed. If all subgroup calls fail (results not yet posted), `countCompleted` falls back to `countShowedUp`. If there are no subgroups, `countCompleted = countShowedUp` directly. **All three counts are written using `maxOf(new, existing)` to prevent API-returned zeros from overwriting previously-captured non-zero values** — Zwift clears counts on some events (those with `invisibleToNonParticipants: true`) within ~24 hours of the event, and a retry that catches the zeroed-out window must not destroy good data.
- **In-progress polling** — after each successful sync, `ReportViewModel.startInProgressPolling()` launches a coroutine that calls `EventSyncRepository.refreshInProgressCounts()` immediately, then every 60 seconds, until no in-progress events remain. An event is "in progress" for polling purposes when `fetchedAt == 0 && eventDate <= now && now - eventDate < IN_PROGRESS_WINDOW_MS` (3 hours). `refreshInProgressCounts()` fires concurrent `getEventWithCounts` calls for those events and writes updated `countSignedUp` via `updateSignedUpForDisplay()` (which guards `fetchedAt = 0`). Once Pass 2 runs and sets `fetchedAt`, the event leaves the in-progress pool and polling stops. A separate guard (`inProgressJob?.isActive`) prevents concurrent polling loops. **Auto-trigger**: after each polling tick where `remaining > 0`, `anyReadyForPass2()` checks whether any in-progress event has passed the 30-minute grace period (`eventDate + GRACE_PERIOD_MS < now`); if so, `startSync()` is called and the polling loop exits — this causes Pass 2 to run within one polling interval (~60 seconds) of an event becoming eligible, without waiting for a manual sync or the 4-hour WorkManager cycle.
- **`invisibleToNonParticipants` events**: Roughly half of observed ZLDR events carry this flag. Zwift may return valid attendance counts for a short post-event window (~24 h) and then return zeros for the same endpoint. Events also marked `recordable: false` are additionally restricted. The app captures whatever the API returns within the 48-hour retry window; the `maxOf` write guard preserves the best observed value.
- **Zwift API date format**: dates arrive as `+0000` (RFC 822, no colon). `parseEventStart()` tries `Instant.parse` first, falls back to `OffsetDateTime` with an explicit `X`-offset formatter.
- **race-results response wrapper**: `GET /api/race-results/entries` returns `{"entries":[...]}` — a JSON object, not a bare array. The typed DTO is `RaceResultsResponseDto`; use `.entries?.size` to count finishers. Each entry carries `rank`, `lateJoin`, `profileData` (firstName/lastName), and `activityData` (durationInMilliseconds, segmentDistanceInMeters).
- **No historical backfill**: There is no Zwift API endpoint for querying past instances of a recurring series. All five candidate endpoint patterns were probed and returned 404 or 405. Historical data accumulates only as upcoming events age into the past through normal sync cycles.

## Data sources

| Host | Endpoint | Purpose |
|------|----------|---------|
| `secure.zwift.com` | `POST /auth/realms/zwift/protocol/openid-connect/token` | OAuth2 login, token refresh |
| `us-or-rly101.zwift.com` | `GET /api/public/events/upcoming` | ZLDR event discovery via `?tags=zldr` / `?tags=zldriders`; general feed also scanned for ZLDR-prefixed events missing the tag |
| `us-or-rly101.zwift.com` | `GET /api/events/{id}` | Live sign-up counts (Pass 1b) and post-event attendance counts (Pass 2) — authenticated, non-public path |
| `us-or-rly101.zwift.com` | `GET /api/race-results/entries?event_subgroup_id={id}` | Finished-rider count per subgroup (Pass 2 `countCompleted`) |

Auth client ID is `Zwift_Mobile_Link` (hardcoded). The User-Agent header mimics the Zwift mobile app.

### Participant count fields (from `/api/events/{id}`)

| Field | Stored as | Notes |
|-------|-----------|-------|
| `totalSignedUpCount` | `countSignedUp` | Captured post-event by Pass 2; for upcoming events updated live by Pass 1b. Falls back to `totalEntrantCount` when zero. `totalSignedUpCount` and `totalEntrantCount` are identical in practice for all observed ZLDR events. |
| `totalJoinedCount` | `countShowedUp` | In-game joins — most reliable attendance metric |
| `totalEntrantCount` | fallback only | Used when `totalSignedUpCount` is zero |
| `followee*` fields | not stored | Personal social-graph data, not meaningful for community stats |

### race-results/entries fields (from `/api/race-results/entries?event_subgroup_id={id}`)

| Field | Used as | Notes |
|-------|---------|-------|
| `entries[].profileId` | identity | Unique per rider |
| `entries[].rank` | display | Sort order in ParticipantDetailScreen |
| `entries[].lateJoin` | display | True if rider joined after event start |
| `entries[].profileData.firstName/lastName` | display | Rider name |
| `entries[].activityData.durationInMilliseconds` | display | Time in event |
| `entries[].activityData.segmentDistanceInMeters` | display | Distance covered |
| `entries.length` | `countCompleted` | Written back to DB via `updateCompleted()` on each ParticipantDetailScreen load |

## Package name & build variants

- Debug: `com.victorypoint.zldreventreporter.debug`
- Release: `com.victorypoint.zldreventreporter`

Current version: `1.8.0` (versionCode 8).

Release signing config is read from `local.properties` (not committed). Keys: `releaseStoreFile`, `releaseStorePassword`, `releaseKeyAlias`, `releaseKeyPassword`.

`BuildConfig.BUILD_DATE` is injected at compile time from `app/build.gradle.kts` via `Calendar.getInstance()`. It is a `String` in `yyyy-MM-dd` format and is displayed in Settings → About.

## Settings features

### Data — Export / Import

Export serialises all `event_stats` rows to a JSON array (`zldr_events_backup_YYYYMMDD.json`) in `cacheDir` and shares it via `FileProvider` + Android share sheet. `EventStatEntity` carries `@JsonClass(generateAdapter = true)` so Moshi generates a code-safe adapter that survives R8 minification.

Import opens the SAF file picker, reads a JSON backup, and upserts all records (`REPLACE` on conflict by `eventId`). Nothing is deleted from the target device — it is a merge, but the **imported record wins entirely on conflict**: if the device has a fresher `countCompleted` for an event and the backup has an older value, the backup overwrites it.

`FileProvider` authority: `${applicationId}.fileprovider`. Declared in `AndroidManifest.xml`; path config in `res/xml/file_paths.xml` (grants access to `cacheDir`).

### Reports — Generate Report

Accessed via Settings → **Generate report…**. Presents a scrollable dialog with:

- **Sport**: All / Cycling / Running
- **Date range**: All time / This year / This month / Last 30 days / Custom (tapping the date chips opens the system `DatePickerDialog`)
- **Include**: Completed events / Upcoming events / Completed & upcoming
- **View**: By date / By series
- **Format**: CSV · Plain text · HTML (printable, open in browser to print-to-PDF) · PDF (rendered via `android.graphics.pdf.PdfDocument` — no extra dependency; paginated A4 table with column headers repeated per page)

All four formats include: Signed Up, Showed Up, Finished, Att % (Showed Up ÷ Signed Up), Cmp % (Finished ÷ Showed Up). Event dates include the time (`MMM d, yyyy h:mm a` for plain text/HTML/PDF; `yyyy-MM-dd HH:mm` for CSV).

**Upcoming vs completed handling** (applies to all formats × both views): when the scope includes upcoming events, each generator splits events into completed (sorted descending) and upcoming (sorted ascending, nearest first). Summary stats (Showed Up / Finished / Att% / Cmp%) are computed from completed events only; upcoming events contribute a separate count and Signed Up total. Upcoming event rows show `—` for Shwd/Fin/Att%/Cmp%. All formats render two distinct sections when both types are present.

**By date** (default): completed section first (most-recent-first), then upcoming section (nearest-first). **By series**: `groupBySeries()` handles completed groups (sorted by most-recent-occurrence descending, events within group descending); `groupUpcomingBySeries()` handles upcoming groups (sorted by nearest-occurrence ascending, events within group ascending). CSV adds `Series Name` and `Day of Week` leading columns. Plain text, HTML, and PDF use grouped sections with per-series headers and aggregate totals followed by individual occurrence rows.

On generation, the file is written to `cacheDir` and shared via the same `FileProvider` + share sheet as the export backup.

Report config types live in `ReportConfig.kt` (`DateRangeOption`, `ReportFormat`, `EventScope`, `ReportView`). Generation logic (all four formats × both views) lives in `SettingsViewModel`. Dialog UI is in `ReportGeneratorDialog.kt`.

## Report screen UI conventions

- **ReportsScreen** has two view modes toggled from the top bar: **Date view** and **Series view** (`ViewMode` enum in `ReportViewModel`).
- **Date view** has two collapsible top-level sections rendered inside a single `LazyColumn`: "Upcoming Events" (collapsed by default) and "Completed Events" (expanded by default).
- Within each section, events are grouped into period rows (Day/Week/Month/Year) matching the current filter. The current period row auto-expands on launch.
- Period grouping logic lives in `ReportViewModel` (`buildUpcomingReport` and `buildReport`). Both share `toBucketKey()`.
- **Series view** has two collapsible top-level sections: "Upcoming Events" (collapsed by default, key `"series_upcoming"`) and "Completed Events" (expanded by default, key `"series_completed"`), mirroring the date view pattern. Completed events are grouped by (eventName, dayOfWeek) into `SeriesRow` items sorted by most-recent-occurrence descending; upcoming events are grouped the same way but sorted by nearest-occurrence ascending (`buildUpcomingSeriesReport()`). Series row title format: `"ZLDR AYOP Event (Tuesday)"`. Aggregates (totals and rates) are computed across all occurrences in each group. Series rows alternate background (even index = `surfaceVariant` at 40% alpha, odd = `surface`). Completed logic in `buildSeriesReport()`; upcoming logic in `buildUpcomingSeriesReport()`. For upcoming series rows, Shwd/Fin/Att%/Cmp% show dimmed "—" (same as date view upcoming rows).
- **Section membership**: Upcoming = `fetchedAt == 0 && eventDate > now`; Completed = `eventDate <= now`. An event moves to Completed the moment it starts, showing placeholder zeros until Pass 2 fills in the real counts (within 30 minutes).
- **Sync triggers**: `ReportViewModel` implements `DefaultLifecycleObserver` on `ProcessLifecycleOwner`. `init` always calls `startSync()` on ViewModel creation (cold launch / process death restart). `onStart()` calls `startSync()` whenever the app returns to the foreground from the background. `startSync()` guards with a running-job check so concurrent syncs cannot stack. On `SyncProgress.Success`, `startSync()` also kicks off `startInProgressPolling()`. In-app navigation (Settings ↔ Reports) does not trigger a re-sync because `ProcessLifecycleOwner` only transitions on genuine app foreground/background events, not on Activity back-stack changes.
- **In progress badge**: completed events where `now < eventDate + durationInSeconds * 1000` show a blinking green "In progress" label — to the right of the event name in date view (`EventRow`), and next to the date text in series view (`SeriesEventLine`). Rendered by `InProgressBadge` composable using `rememberInfiniteTransition` (alpha oscillates 1.0 → 0.25, 800 ms cycle). Badge disappears exactly when the event's stored duration elapses. `durationInSeconds` defaults to 3600 for historical rows; Pass 1 writes the real value from `ZwiftEventDto.durationInSeconds` on every sync.
- **Sync indicator**: while `SyncProgress.Loading`, the sync icon in the top bar is replaced with a small `CircularProgressIndicator` (24dp, 2dp stroke). The icon returns when the sync completes.
- Upcoming rows are sorted **ascending** (nearest first); completed rows are sorted **descending** (most recent first).
- **Column header bar** sits above the `LazyColumn` in a fixed `Column` — always visible while scrolling. Each of the five stat column labels (Reg, Shwd, Fin, Att%, Cmp%) is tappable and shows an `AlertDialog` with the column's full name and definition.
- Each event row is stacked: name on row 1 (full width, no truncation), date + stats on row 2. Stats row uses an 8dp leading spacer + date at weight(2.0f) + five stat columns at weight(0.5f) each — matching the header weights exactly for pixel alignment.
- Event date format: `EEE, MMM d h:mma/pm` (e.g. `Mon, Jun 8 7:30am`) — no year, no separator between date and time. Rendered at 11sp with `maxLines = 1` and `TextOverflow.Ellipsis`.
- Stats columns: **Reg** (Signed Up, centre), **Shwd** (Showed Up, centre), **Fin** (Finished, centre), **Att%** (Showed Up ÷ Signed Up, right), **Cmp%** (Finished ÷ Showed Up, right). Colour-coded: ≥70% green, ≥40% amber, <40% red. All five stat values render at 11sp normal weight. Att% has `padding(end = 6.dp)` to create visual separation from Cmp%. `RateCell` enforces `maxLines = 1` to prevent wrapping in narrow columns.
- For upcoming events, Shwd/Fin/Att%/Cmp% show dimmed "—".
- **Expanded/collapsed state** (`expandedKeys: Map<String, Boolean>`) lives in `ReportViewModel` so it survives back-stack navigation. The `LazyColumn` scroll position is preserved automatically via `rememberSaveable`.
- Section content is indented 16dp. Event rows are **not** additionally indented — their own `padding(start=12dp)` aligns their left edge with the `^` expand icon in period row headers. Event rows alternate background (even index = `surfaceVariant` at 40% alpha) within each expanded period.
- **Tap gestures** on event rows:
  - **Single tap** → `EventDetailScreen` (event info: date, route, distance/duration, description, category cards). Shows "Starts" for upcoming, "Completed" for past. For completed events where all three counts are 0, a **Re-fetch banner** is shown with a button that resets `fetchedAt = 0` and triggers a full sync. `EventDetailViewModel` now takes `EventStatsRepository` and `EventSyncRepository` for this purpose.
  - **Double tap** (completed events only) → `ParticipantDetailScreen` (finisher list: rank, name, late join, duration, distance). Upcoming events absorb the double-tap silently. Each `ParticipantDetailScreen` load writes the fresh `entries.size` back to DB via `updateCompleted()`. When the entries list is empty, the screen distinguishes two cases via `ParticipantUiState.Success.resultsConfirmed`: **"No one has completed this event."** when `resultsConfirmed = true` (at least one subgroup API call returned a response, or `totalJoinedCount == 0`); **"Results not yet available for this event."** when `resultsConfirmed = false` (all subgroup calls failed, meaning results may not yet be posted by Zwift).
  - **Long press** → opens `https://www.zwift.com/events/view/{eventId}` in browser.
- Sport filter order: ALL | RUNNING | CYCLING (enum declaration order in `SportFilter`).

## Navigation routes

| Route | Screen | Key args |
|-------|--------|----------|
| `login` | LoginScreen | — |
| `reports` | ReportsScreen | — |
| `event_detail/{eventId}` | EventDetailScreen | eventId: Long |
| `participants/{eventId}` | ParticipantDetailScreen | eventId: Long |
| `detail/{fromMillis}/{toMillis}/{sport}` | EventPeriodDetailScreen | period range + sport |
| `settings` | SettingsScreen | — |

## Resource files created during setup

The following resource files were absent from the repo and were added to unblock the build:

- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- `app/src/main/res/drawable/ic_launcher_foreground.xml` (placeholder triangle icon)
- `app/src/main/res/values/colors.xml` (`ic_launcher_background` = ZldrBlue `#1565C0`)
- `app/src/main/res/xml/file_paths.xml` (FileProvider cache-dir path config)

Replace `ic_launcher_foreground.xml` with a real icon vector before release.

## Known TODOs in the code

- No tests exist. Room supports in-memory testing; Retrofit supports MockWebServer. Add these before shipping.
- `signedUpFrozen` column in `event_stats` is dormant. It was used by the now-removed `PreEventSyncWorker` to freeze pre-event sign-up counts. All new events have `signedUpFrozen = false`; the column is kept to avoid a schema migration. Remove it (with a version 4 migration) when a future schema change makes the migration worthwhile.
- `ResultDto.kt` is unused — it was the DTO for the old `/api/events/subgroups/{id}/results` endpoint which always returned 404 for ZLDR events. Safe to delete.
- The import merge strategy (imported record wins entirely on conflict) means a user restoring an older backup can silently downgrade `countCompleted` values. A smarter merge (keep the higher `countCompleted`) could be added to `importData()` if needed.

## Dependencies to watch

- `androidx.security:security-crypto:1.1.0-alpha06` — still in alpha; check for stable release.
- `androidx.lifecycle:lifecycle-process:2.8.2` — added for `ProcessLifecycleOwner` (foreground-resume sync trigger in `ReportViewModel`). Check for updates alongside the other lifecycle artifacts.
- Zwift API is undocumented/unofficial — endpoints or auth may change without notice.
