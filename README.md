# Airplay Speaker

<p align="center">
  <strong>An AirPlay music receiver for Android TV with motion artwork and word-by-word lyrics.</strong>
</p>

<p align="center">
  <a href="https://github.com/l3kza08/Sonic-Cast/releases/latest">
    <img alt="Download APK" src="https://img.shields.io/badge/Download-Latest%20APK-ff765d?style=for-the-badge&logo=android&logoColor=white">
  </a>
</p>

<p align="center">
  <img alt="Android TV 7.0+" src="https://img.shields.io/badge/Android%20TV-7.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="CD-quality audio" src="https://img.shields.io/badge/Audio-16--bit%20%2F%2044.1%20kHz-8B5CF6">
  <img alt="Kotlin and Compose" src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Version 0.14.13" src="https://img.shields.io/badge/Version-0.14.13-ff765d">
  <img alt="GPL-3.0 license" src="https://img.shields.io/badge/License-GPL--3.0-blue">
</p>

Airplay Speaker turns an Android TV into a living-room AirPlay music receiver. It plays lossless ALAC audio at CD quality, displays track metadata and album artwork, supports background playback and remote transport controls, and presents synchronized lyrics in a television-first 1920×1080 interface.

**[Open the interactive website](https://l3kza08.github.io/airplay-speaker-web/)** · **[Download the latest APK](https://github.com/l3kza08/Sonic-Cast/releases/latest)**

## What’s new in v0.14.13

- **One-page lyrics transition** — hiding lyrics now moves the current artwork and metadata smoothly into the center instead of switching to a separate-looking screen.
- **Browsable Up Next carousel** — previous, current, and upcoming tracks use equally sized artwork; use Left or Right to browse a sender-provided queue and OK to start the selected track.
- **Cleaner lyrics control** — the small round lyrics button sits at the lower-right and hides after five seconds without remote input.
- **Context only when lyrics are hidden** — the `NOW PLAYING` label and selected white outline appear only in the artwork carousel view.
- **Smoother visual motion** — album-color background fields and motion artwork have been tuned for more fluid playback on Android TV devices with 2 GB of RAM.
- **More reliable queue parsing** — repeated DACP queue records are preserved, preventing missing or duplicated items when compatible senders expose their play queue.

> Queue browsing depends on the AirPlay sender exposing its DACP play queue. When it is unavailable, the current track remains centered without inventing unavailable tracks.

## Demo

<p align="center">
  <a href="docs/media/SonicCastTV-demo.mp4">
    <img src="docs/media/SonicCastTV-demo-poster.jpg" alt="Airplay Speaker running on Android TV" width="100%">
  </a>
</p>

<p align="center">
  <a href="docs/media/SonicCastTV-demo.mp4"><strong>▶ Watch the v0.14.8 15-second 1080p demo</strong></a>
</p>

> The v0.14.8 demo was captured from a real Android TV at 1920×1080 and 59.94 FPS. The recording has no audio.

## Highlights

### Audio and AirPlay

- **AirPlay music receiver** — classic RAOP mode works with Music and other AirPlay senders on iPhone, iPad, and macOS.
- **CD-quality audio** — receives ALAC and decodes it to 16-bit / 44.1 kHz stereo PCM.
- **Background playback** — keeps playing after Home is pressed, with a Media Session and media notification.
- **DACP controls** — supports play, pause, previous, next, and sender-controlled volume.
- **Optional video mode** — AirPlay Display advertising for mirroring and HLS can be enabled in Settings.

### Television-first design

- **Calm waiting screen** — shows only the AirPlay mark and listening status before a sender connects.
- **Minimal Now Playing view** — keeps artwork and lyrics centered without a permanent control bar.
- **Dynamic artwork background** — extracts album colors and blends them into a GPU-animated, blurred backdrop.
- **Motion artwork** — discovers and loops public Apple Music HLS artwork when an album provides it.
- **Motion artwork switch** — disables video artwork and the floating static-cover effect while preserving the normal cover for the UI and background palette.
- **Static cover companion** — loads a square normal cover alongside motion artwork so the background always matches the album.
- **Long-title marquee** — track, artist, and album text stays on one line and moves through soft edge fades when necessary.
- **Playback-aware equalizer** — the indicator beside the title moves only while audio is playing.
- **Low-memory tuning** — artwork, background effects, and lyric rendering are designed to remain responsive on devices with 2 GB of RAM.
- **Fallback artwork** — missing covers use a neutral gray tile with a music note.

### Synchronized lyrics

- **Word-by-word karaoke** — follows supplied word or syllable timing and applies a soft aura to the active text.
- **Display-synchronized motion** — lyric progress and rail transitions follow the display frame clock at up to 60 Hz.
- **Focused lyric rail** — normally shows only the active line and the upcoming line; completed lines move away smoothly.
- **Remote browsing** — pressing Up or Down reveals the complete lyric timeline. The view returns to the current lyric after browsing stops.
- **Plain lyrics fallback** — songs without timestamps display their full text as a readable scrolling document.
- **Duet placement** — alternating vocal parts can appear on the left and right when singer information is available.
- **Backing-vocal asides** — parenthesized lyrics can be separated into a smaller line above or below the main lyric.
- **Instrumental indicator** — three large breathing dots appear during a long intro or instrumental break, brightening in sequence before the next line.
- **End credits** — a `Written By` block can appear after the final lyric when composer metadata is available.
- **Pause accuracy** — lyric timing, rail motion, karaoke highlighting, the equalizer, and break dots all freeze immediately when playback pauses.

## Requirements

- Android 7.0 / API 24 or newer
- Android TV or an Android box with video and audio output
- The sender and receiver on the same Wi-Fi or wired LAN
- A 1920×1080 display at 60 Hz is recommended

## Install

1. Download the newest APK from [GitHub Releases](https://github.com/l3kza08/Sonic-Cast/releases/latest).
2. Install it with a USB drive, a file manager, or ADB:

```bash
adb connect <ANDROID_TV_IP>:5555
adb install -r Airplay-Speaker-v0.14.13.apk
```

The application appears in the Android TV app launcher as **Airplay Speaker**.

> The published APK uses a development signing key. Android may require the previous build to be uninstalled if that build was signed with a different key.

## Use

1. Open Airplay Speaker and wait for `LISTENING FOR AIRPLAY`.
2. Connect the iPhone, iPad, Mac, and Android TV to the same network.
3. Open Music or Control Center and choose **Airplay Speaker** from the AirPlay menu.
4. Start a track. Artwork, metadata, ambient color, and available lyrics appear automatically.
5. Press Up or Down on the TV remote to browse all lyrics, then stop interacting to return to live sync.

The receiver name, automatic startup, background playback, motion artwork, and optional video mode can be changed in **Settings**.

## Lyrics and artwork data

Lyrics and motion artwork are not bundled in the APK. The app looks them up when a track begins:

- [LyricsPlus](https://github.com/ibratabian17/lyricsplus) — preferred source for syllable, word, and line timing.
- [LRCLIB](https://lrclib.net) — fallback source for synchronized and plain lyrics.
- [iTunes Search API](https://performance-partners.apple.com/search-api) — identifies matching albums and high-resolution static artwork.
- Public Apple Music album pages and CDN — used to discover motion artwork when it is published for an album.

Only the current title, artist, album, and duration are used to locate a matching result. No account is required, the app includes no behavioral analytics, and retrieved lyrics or artwork are not permanently stored by Airplay Speaker.

## Build from source

Install Java 17 and Android SDK 36, then run:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The installable APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Native libraries for `armeabi-v7a`, `arm64-v8a`, and `x86_64` are included. Receiver and decoder source is retained under `app/src/main/cpp`.

## Project map

```text
app/src/main/kotlin/io/github/jqssun/airplay/
├── artwork/     # Static and Apple Music motion artwork
├── audio/       # Metadata, DACP, and playback state
├── lyrics/      # LyricsPlus, LRCLIB, parsing, and timing
├── renderer/    # Audio and video pipelines
├── service/     # Foreground AirPlay receiver service
└── ui/          # Compose UI for Android and Android TV
```

Native receiver components and retained source live under `app/src/main/cpp/` and `app/src/main/jniLibs/`.

## Known limitations

- DRM-protected media, including FairPlay-protected video, is not supported.
- Motion artwork and synchronized lyrics depend on external data availability for each track.
- Public Apple Music page structures and third-party lyrics APIs may change.
- Compatibility and latency vary by sender, Android TV hardware, and network quality.
- Dolby Atmos decoding and bitstream output are not supported; AirPlay music uses stereo PCM.

## Open-source credits

Airplay Speaker is a GPL-3.0 modification of [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server), based on upstream version 0.0.23. It also incorporates or builds upon:

- [Shairplay](https://github.com/juhovh/shairplay) — classic RAOP music receiver
- [UxPlay](https://github.com/FDH2/UxPlay) — AirPlay mirroring and HLS receiver
- [Apple ALAC](https://github.com/macosforge/alac) — lossless audio decoder
- [Media3](https://github.com/androidx/media) — HLS motion-artwork playback
- [AdvancedRPC](https://github.com/down-bad/advanced-rpc) — inspiration for the animated-artwork fallback

See [NOTICE.md](NOTICE.md) for component provenance and retained license information.

## License

This project is distributed under the [GNU General Public License v3.0](LICENSE).

Airplay Speaker is not affiliated with or endorsed by Apple Inc. AirPlay and Apple Music are trademarks of Apple Inc.
