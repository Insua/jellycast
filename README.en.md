<div align="center">

# JellyCast

**Listen to the shows and movies on your Jellyfin server as if they were podcasts.**

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://www.android.com)
[![minSdk](https://img.shields.io/badge/minSdk-26-blue)](https://developer.android.com/tools/releases/platforms)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)](https://developer.android.com/jetpack/compose)
[![Jellyfin](https://img.shields.io/badge/Jellyfin-10.10%2B-00A4DC?logo=jellyfin&logoColor=white)](https://jellyfin.org)

[中文](README.md) · English

</div>

---

## What it is

JellyCast is an Android client shaped like a podcast player, backed by your own **Jellyfin** server. It **never renders video** — the playback screen shows cover art, and subtitles scroll by as **lyrics**.

### Why it exists

A NAS tends to accumulate a lot of shows, and a large share of them — talk shows, documentaries, interviews, slice-of-life anime, comfort rewatches — **only need the audio**.

Official clients are video players: you have to look at the screen, playback stops when the screen locks, it drains the battery, and away from home it ships a full video stream.

JellyCast inverts the assumption. Because **it assumes you are not watching**, it can do something a video client cannot: ask the server to transcode audio only. For the same episode, a video stream costs several Mbps; an audio-only stream costs about **100 Kbps**. Away from home the bottleneck is your home broadband's **upload** bandwidth, and that order-of-magnitude difference decides whether playback is smooth or not.

---

## Features

**Playback**

- 🎧 **Audio-only streaming** — the server transcodes just the audio track; no video bytes cross the wire
- 🎵 **Subtitles as lyrics** — text subtitles scroll and highlight the way Apple Music lyrics do
- 🔒 **Background playback** — lock screen, notification, Bluetooth headsets, car head units
- ⏩ **The podcast essentials** — playback speed (0.5×–3.0×), sleep timer, configurable skip intervals
- ▶️ **Auto-advance in series order** — end of a season continues into the next; end of a series returns home
- 🎚️ **Audio track selection** (available on the L3 fallback path only)

**Content and sync**

- 🌐 **Multiple servers, multiple endpoints per server** — LAN / Tailscale / public address, all **probed concurrently, first success wins**. LAN at home, public address away, no manual switching
- ↔️ **Two-way progress sync** — pause on your phone, pick up on your TV
- 📚 **Library browsing with paging and search** — built for real-sized libraries (the development library holds 8,000+ episodes)
- ⭐ **Favorites and played markers**
- 📴 **Offline cache** — cache-first with background revalidation, so opening the app without a connection shows last known content rather than a blank screen
- 🔄 **Silent home refresh** — "Continue Listening" and "Next Up" resync when you return to the home screen or bring the app back to the foreground

**Other**

- 🩺 **Diagnostic log export** — written to app-private storage, shareable in one tap, never records credentials
- 🔐 **Self-signed certificate support** — trusted through a user-confirmed fingerprint allowlist; TLS validation is **never** globally disabled

---

## How it works

This section records a few non-obvious design decisions — they explain why the project looks the way it does.

### The audio degradation chain

When playing an item, these are tried in order:

| Level | Approach | Result |
|---|---|---|
| **L1** | Request `/Audio/{id}/universal`; the server transcodes to audio only | ~100 Kbps, no video bytes sent |
| **L3** | Request `/Videos/{id}/stream` and disable the video track in the client's `TrackSelector` | Known-good fallback |

An L1 failure must degrade **silently** — the user should never see an error for it.

> **Why there is no L2.** The original design had an L2 level: pick an audio-only rendition out of the HLS master playlist. A spike proved Jellyfin **does not offer** such a rendition, so the chain went from three levels to two. This was the project's first instance of "measurements win over plans."

### Transcoded streams don't support range requests, so seeking isn't seeking

The server's transcoded stream responds with `Accept-Ranges: none`, which makes `player.seekTo()` unreliable on it.

JellyCast instead **re-resolves the URL with a new `startTimeTicks` and re-prepares** on every seek. As a consequence, the player reports a position *relative to the current transcoded stream*, while the **absolute position within the item** is tracked separately by the playback engine — and that is what the lock-screen scrubber, the lyric highlighting, and progress reporting all read.

### Subtitles are fetched, parsed, and rendered by the client

Because the stream carries audio only, **the subtitle track isn't in it**. Subtitles are therefore fetched over a separate HTTP request, parsed in-app (SRT / VTT / ASS), and rendered as the lyrics UI. That keeps them working at every degradation level.

Any subtitle failure degrades to "no subtitles" and **never affects playback**.

### "Multiple servers" and "multiple endpoints" are two different layers

One **Server** owns several **Endpoints** (LAN / Tailscale / public). On connect, every endpoint is probed concurrently and the first to succeed wins. That is what makes "works at home and away without touching anything" true.

### Time units

Jellyfin measures time in **ticks** (1 tick = 100 ns). The conversion happens **exactly once**, in the DTO → model mapping layer; ticks never appear in business logic or UI code.

---

## Requirements

| | |
|---|---|
| Device | Android 8.0 (API 26) or newer |
| Server | Jellyfin 10.10 or newer (tested against 10.10.7) |
| Build | JDK 17 · Android SDK 36 · Gradle 9.5 (provided by the wrapper) |

---

## Building

```bash
git clone https://github.com/<your-account>/jellycast.git
cd jellycast

# Debug build
./gradlew :app:assembleDebug

# Install onto a connected device
./gradlew installDebug
```

### Tests

```bash
./gradlew test                              # all JVM unit tests
./gradlew :core:subtitle:testDebugUnitTest  # a single module
./gradlew connectedDebugAndroidTest         # requires a device or emulator
```

End-to-end tests need a **real Jellyfin server**. Copy `testing.properties.example` to `testing.properties` and fill in the address and account — that file is gitignored, and when it is missing the end-to-end tests are **skipped** via JUnit `Assume` rather than failed.

### Release signing

The keystore and its passwords **never enter version control**. The build script reads them in this order, and produces an **unsigned** build rather than failing if neither is present:

1. Environment variables `JELLYCAST_STORE_FILE` / `JELLYCAST_STORE_PASSWORD` / `JELLYCAST_KEY_ALIAS` / `JELLYCAST_KEY_PASSWORD` (for CI)
2. `keystore.properties` in the project root (for local builds; gitignored, template at `keystore.properties.example`)

```bash
./gradlew signingStatus        # report whether signing is configured, without leaking passwords
./gradlew :app:assembleRelease
```

---

## Project layout

```
:app                  entry point, navigation, dependency wiring
:core:model           pure data models (no Android dependencies, unit-testable on the JVM)
:core:network         Jellyfin API, auth, endpoint selection, certificate policy
:core:database        Room: offline cache, progress retry queue
:core:datastore       DataStore: server list, user preferences
:core:player          playback engine, degradation chain, MediaSession, queue, progress reporting
:core:subtitle        subtitle fetching and parsing (SRT / VTT / ASS)
:core:diagnostics     diagnostic logging
:core:designsystem    theme, mini player bar, cover cards
:feature:server       server management and login
:feature:home         "now listening" home screen
:feature:library      series / season / episode / movie browsing
:feature:player       full-screen player and lyrics view
:feature:settings     settings
```

**Boundary rule:** every module has a single responsibility and exposes only interfaces. `:core:player` **knows nothing about the Jellyfin API** — it accepts an already-resolved playback URL plus metadata.

The core logic — endpoint selection, degradation decisions, subtitle parsing, lyric line lookup — is plain Kotlin and unit-testable without a real server.

---

## Tech stack

Kotlin 2.4.10 · Jetpack Compose + Material 3 · **Media3 (ExoPlayer + MediaSessionService)** · Retrofit + OkHttp · kotlinx.serialization · Hilt · Room · DataStore · Coil · JUnit 5 + MockK + Turbine

---

## Status

**Usable.** The core feature set is implemented and in daily use on real hardware. Current version: `0.1.0`.

**Explicitly out of scope** for now: offline downloads · video rendering · music / audiobook libraries · casting · burned-in subtitle OCR

---

## License

**No license has been chosen yet.** Until a `LICENSE` file is added, all rights are reserved.

---

## Acknowledgements

- [Jellyfin](https://jellyfin.org) — the reason this project can exist
- [Media3 / ExoPlayer](https://github.com/androidx/media)
- Interaction design draws on [Xiaoyuzhou](https://www.xiaoyuzhoufm.com) and Spotify's podcast player
