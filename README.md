# BecashPlayer

> **Fork of [doublesymmetry/KotlinAudio](https://github.com/doublesymmetry/KotlinAudio)** — the
> `kotlin-audio` library module is kept as-is; the upstream example module has been fully
> replaced with a personal music player application (`becash-player`).

A personal Android music player built with Kotlin and Jetpack Compose, powered by
the [KotlinAudio](https://github.com/doublesymmetry/KotlinAudio) / Media3 library. Designed for
syncing and playing a private music library stored on a Nextcloud (WebDAV) server, with per-track
metadata (ratings, play counts, listen time) tracked in a MySQL database.

---

## Features

### Playback

- Plays local audio files: `mp3`, `flac`, `aac`, `ogg`, `m4a`, `wav`, `wma`, `opus`
- Four playlist modes: **Shuffle** (weighted), **Normal**, **Play One** (repeat single),
  **Manual** (long-press a track to queue it next; pauses when nothing is queued)
- Previous / Next navigation with filter awareness
- Persistent playback state — resumes last track on restart
- Media session integration: lock-screen controls, headset buttons, Bluetooth

### Sync

- **WebDAV sync** — downloads missing files from a Nextcloud server, deletes local files removed
  from the server, retries on failure (3×)
- **MySQL sync** — reads track metadata (ratings, play counts) from a shared database; uploads
  accumulated listen time and play events
- **Offline mode** — stats operations are queued locally (`offline_queue.json`) and flushed to
  MySQL when going back online; failed online writes fall back to the queue automatically

### Weighted Shuffle

Tracks played rarely are scheduled more often. The weight formula:

- Unplayed tracks get maximum priority (`weight = 1000 × (max_plays + 1)²`)
- Played tracks: `weight = 1000 × (max_plays / plays)²` (inverse-quadratic)
- Weight doubles for each month since the track was last played (up to 12 months)
- After a track finishes playing, its weight drops to 0 until the next stats sync

### Rating & Filtering

- Per-track flags: **Rate** (1–5 stars), **Dance**, **Calm**
- Filter presets: `ALL`, `WITH_RATING`, `NO_RATING`, `TOP` (≥4), `BEST` (5), `DANCE`, `CALM`
- Full-text search across artist and title, with optional **inverted** mode (exclude matches)

### UI

- Jetpack Compose / Material 3
- Scrollable track list with current-track highlight, duration, rating and listen-completeness bar
- Rate/dance/calm editing: full dialog + quick panel for the current track
- Track info dialog: file details, playlist stats, ID3 tags (read-only)
- Delete current track (local file + remote WebDAV copy)
- Settings screen: configure server URL, credentials, folder paths, MySQL connection
- Sync progress bar with file-level status
- Screen stays on during playback; playlist hidden in split-screen mode

### Other

- Crash reporting via [Sentry](https://sentry.io)
- Call-phone shortcut (configured numbers, e.g. gate intercoms)
- All credentials stored in `local.properties` — never committed to version control

---

## Architecture

```
KotlinAudio/
├── kotlin-audio/               # Media3/ExoPlayer wrapper library
│   └── src/main/java/com/doublesymmetry/kotlinaudio/
│       ├── players/            # BaseAudioPlayer, QueuedAudioPlayer
│       ├── models/             # AudioItem, PlayerConfig, BufferConfig, …
│       ├── event/              # Kotlin Flow event holders
│       └── notification/       # Media session & notification manager
│
└── becash-player/              # BecashPlayer application
    └── src/main/java/com/becash/becashplayer/
        ├── MainActivity.kt     # Activity bootstrap: permissions, calls, screen switch
        ├── PlayerViewModel.kt  # MVVM state + playback/stats/sync orchestration
        ├── PlaylistMode.kt     # Playback mode enum
        ├── Filters.kt          # RatingFilter + text/rating filter predicates
        ├── data/               # Persistence & sync, one file per entity
        │   ├── AppSettings.kt    # JSON-backed persistent settings
        │   ├── PlaylistStore.kt  # playlist.json + shuffle weight calculation
        │   ├── WebDavSync.kt     # Nextcloud WebDAV sync (OkHttp)
        │   ├── SyncState.kt      # Sealed class: Idle / Syncing / Done / Error
        │   ├── DbSync.kt         # MySQL play-stats sync (JDBC)
        │   ├── OfflineQueue.kt   # Queued stats ops while offline
        │   └── AudioConstants.kt # Supported audio extensions
        ├── ext/                # Small Kotlin extensions (Long.toDurationString)
        └── ui/                 # Compose UI
            ├── Screen.kt         # Navigation model (Main / Settings)
            ├── screen/           # One file per screen + its dialogs
            └── component/        # Reusable pieces (TrackDisplay, PlayerControls)
```

**Stack:** Kotlin · Jetpack Compose · Media3 (ExoPlayer) · OkHttp · MySQL JDBC · Sentry · Coil

---

## Requirements

| Component      | Version               |
|----------------|-----------------------|
| Android Studio | Hedgehog 2023.1+      |
| Android SDK    | API 26+ (Android 8.0) |
| Kotlin         | 1.9.24                |
| Gradle         | 8.7                   |
| Java           | 17 (build host)       |

**Runtime requirements:**

- A Nextcloud instance accessible via WebDAV (optional — local playback works without it)
- A MySQL 5.7+ / 8.0+ server with a `played` table (optional — ratings and stats require it)

---

## Installation

### 1. Clone the repository

```bash
git clone https://github.com/<your-username>/KotlinAudio.git
cd KotlinAudio
```

### 2. Create `local.properties`

The file is already listed in `.gitignore`. Create it at the project root:

```properties
sdk.dir=/path/to/your/Android/Sdk
# Nextcloud / WebDAV
DEFAULT_SERVER_URL=https://your.nextcloud.server
DEFAULT_USERNAME=your_username
DEFAULT_PASSWORD=your_password
DEFAULT_REMOTE_FOLDER=/path/to/Music
# MySQL
DEFAULT_MYSQL_HOST=your.mysql.host
DEFAULT_MYSQL_PORT=3306
DEFAULT_MYSQL_USER=db_user
DEFAULT_MYSQL_PASSWORD=db_password
DEFAULT_MYSQL_DB=database_name
# Optional: phone numbers for quick-dial shortcuts
BARIERA9=
BARIERA10=
```

> All values are injected at build time via `BuildConfig` — they are never hardcoded in source
> files.

### 3. Open in Android Studio

File → Open → select the `KotlinAudio` folder.

Wait for Gradle sync to complete.

### 4. Run

Select the **`becash-player`** run configuration, choose your device or emulator, and click
**Run**.

### 5. First launch

- Grant storage and notification permissions when prompted.
- Tap **Sincronizează Nextcloud** from the ⋮ menu — if the server isn't configured yet, the
  Settings screen opens automatically; fill in the details, save, then sync again.
- Playback starts automatically after sync.

---

## MySQL Schema

The app auto-creates the `played` table on first database sync:

```sql
CREATE TABLE IF NOT EXISTS played (
    id       VARCHAR(500) PRIMARY KEY,
    plays    INT        DEFAULT 0,
    rate     TINYINT    DEFAULT 0,
    dance    TINYINT(1) DEFAULT 0,
    calm     TINYINT(1) DEFAULT 0,
    listen   BIGINT     DEFAULT 0,  -- accumulated milliseconds
    duration INT        DEFAULT 0,  -- track duration in ms
    updated  DATETIME   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

`id` is the file path relative to the audio folder, with a leading slash
(e.g. `/Artist/Album/track.mp3`).

---

## Settings Reference

All settings are persisted to `settings.json` on the device's external storage and can be edited
from the in-app Settings screen.

| Setting               | Description                                           |
|-----------------------|-------------------------------------------------------|
| Server URL            | Nextcloud base URL (e.g. `https://cloud.example.com`) |
| Username / Password   | WebDAV credentials                                    |
| Remote Folder         | Path on Nextcloud to sync from                        |
| Local Folder          | Folder name inside device external storage            |
| MySQL Host / Port     | Database server address                               |
| MySQL User / Password | Database credentials                                  |
| MySQL Database        | Database name                                         |
| Bariera 9 / 10        | Quick-dial phone numbers (⋮ menu shortcuts)           |

---

## KotlinAudio Library

The `kotlin-audio` module is a self-contained Media3/ExoPlayer wrapper published separately. It
provides:

- `QueuedAudioPlayer` — queue management with next/previous/jump
- `BaseAudioPlayer` — audio focus, becoming-noisy handling, caching
- Kotlin Flow event streams: `stateChange`, `audioItemTransition`, `playbackError`,
  `onPlayerActionTriggeredExternally`
- Support for Progressive, HLS, DASH, and SmoothStreaming sources
- `NotificationManager` for Media session and lock-screen controls

```kotlin
val player = QueuedAudioPlayer(
  context, PlayerConfig(
    handleAudioFocus = true,
    handleAudioBecomingNoisy = true
  )
)

val item = DefaultAudioItem(
  audioUrl = "https://example.com/track.mp3",
  type = MediaType.DEFAULT,
  title = "Track Title",
  artist = "Artist Name"
)

player.add(item, playWhenReady = true)

// Observe state in Compose
val state = player.event.stateChange.collectAsState(initial = AudioPlayerState.IDLE)
```

---

## Permissions

| Permission               | Purpose                                 |
|--------------------------|-----------------------------------------|
| `INTERNET`               | WebDAV sync, MySQL connection           |
| `READ_MEDIA_AUDIO`       | Read audio files (Android 13+)          |
| `READ_EXTERNAL_STORAGE`  | Read audio files (Android 10–12)        |
| `WRITE_EXTERNAL_STORAGE` | Save synced files (Android 9 and below) |
| `MANAGE_EXTERNAL_STORAGE` | Full access to the synced music folder |
| `POST_NOTIFICATIONS`     | Playback notification                   |
| `CALL_PHONE`             | Quick-dial shortcuts                    |

---

## License

This project is for personal use. The `kotlin-audio` library module is available under the **MIT
License** — see [`LICENSE`](LICENSE) for details.
