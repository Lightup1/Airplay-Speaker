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
  <img alt="Version 2.0.0" src="https://img.shields.io/badge/Version-2.0.0-ff765d">
  <img alt="GPL-3.0 license" src="https://img.shields.io/badge/License-GPL--3.0-blue">
</p>

Airplay Speaker turns an Android TV into a living-room AirPlay music receiver. It plays lossless ALAC audio at CD quality, displays track metadata and album artwork, supports background playback and remote transport controls, and presents synchronized lyrics in a television-first 1920×1080 interface.

**[Open the interactive website](https://l3kza08.github.io/airplay-speaker-web/)** · **[Download the latest APK](https://github.com/l3kza08/Sonic-Cast/releases/latest)**

## What’s new in v2.0.0

Version 2.0 is the cumulative television and lyric-engine update since the
previous GitHub release, v1.0.0.

- **Glyph-shaped karaoke aura** — the active grapheme receives a soft text-shadow bloom instead of a rectangular selection layer or circular block.
- **Layout-stable lyric breathing** — sustained words can grow by up to 14% without changing text measurement, wrapping, or the position of neighbouring words.
- **Independent vocal timelines** — the primary lyric and compact backing vocal keep separate Apple timing, so overlapping parts no longer disable or overwrite each other.
- **Honest Apple Music fallback** — published word timing uses Word-by-word mode, line timing remains Line Sync, and untimed songs display as a complete plain-text document.
- **Stable Duet placement** — singer/agent metadata controls left and right turns, with a final guard against malformed songs that place every line on the right.
- **Audible-output synchronization** — lyric timing compensates for the local AudioTrack queue and smooths small sender-progress jumps while still snapping on real seeks.
- **Karaoke Mode and Smart Vocal Focus** — a TV-first control adjusts lightweight lead-vocal reduction without interrupting playback on 2 GB devices.
- **Smoother motion artwork** — high-frame-rate Apple Music artwork, bounded caching, and TV-friendly buffering reduce visible stalls.
- **Low-memory reliability** — lyric parsing, timing repair, log persistence, and duplicate cleanup stay off the main UI path and use bounded caches.
- **Refined lyric presentation** — compact backing vocals, writer credits, remote lyric browsing, sequential instrumental dots, and a 7.5-second loading timeout are included.

## What’s new in v1.0.15

- **True radial karaoke aura** — removes Android's rectangular selection path from the renderer and replaces it with a soft multi-stop circular bloom behind the live grapheme.
- **Independent large and compact vocals** — precise Apple timing for the main lyric or parenthetical backing vocal remains active even when the other part is only line-timed.
- **Reliable backing-vocal routing** — Apple background-word indices are retained explicitly, so repeated words and punctuation cannot move compact timing into the large line.
- **Mixed-timing repair** — partial Apple word timelines retain their published onset and duration without shifting the main line to a later backing vocal.

## What’s new in v1.0.14

- **Single-layer Apple-style highlight** — removes the overlapping text shadow that could make a live syllable look duplicated, leaving one clean progressive fill and one restrained aura.
- **Grapheme-local emphasis** — only the visible Thai cluster, emoji, letter, or syllable under the playhead receives the paint-only growth effect; the full line no longer pulses.
- **No whitespace glow** — token separators are excluded from the highlight path, preventing the aura from jumping backwards at the end of a word.
- **Exact onset hand-off** — a word becomes active on its published Apple timestamp, eliminating the one-frame dim flash between adjacent words.

## What’s new in v1.0.13

- **Apple-style moving highlight head** — a soft radial aura now follows the exact grapheme crossing the Apple word timeline instead of lighting an entire duplicated text layer.
- **Layout-stable lyric breathing** — the live row grows and returns through a paint-only transform, preserving wrapping and eliminating the shake caused by animated font metrics.
- **Critically damped rail motion** — active-line centring no longer rebounds at the end of a transition, while the upcoming lyric remains softly dimmed and blurred.
- **Duration-aware emphasis** — sustained Apple words receive a wider, slower bloom; short syllables stay clean and responsive.

## What’s new in v1.0.12

- **Apple Music writing credits** — the final lyric now glides into a spacious `Written By:` credit populated from the matching Apple Music Composition & Lyrics section.
- **Accurate credit roles** — only Apple-listed songwriters, composers, and lyricists are shown; producers and performers are excluded and no missing names are invented.
- **Sender fallback** — if the public Apple page has no credit section, sender-provided composer metadata can still complete the end card.

## What’s new in v1.0.11

- **Apple Music-only lyrics** — every lyric lookup now uses the Apple Music source; all alternate lyric services and source-fusion code have been removed.
- **Honest synchronization modes** — Apple syllable timing enables Word-by-word karaoke, line timing highlights the complete active line, and untimed lyrics appear as a normal full-text document.
- **No fabricated karaoke timing** — the application no longer estimates word windows when Apple does not publish them.

## What’s new in v1.0.9

- **Clean single-layer karaoke glow** — only the grapheme crossing the playhead receives an aura, removing the stacked or duplicated-text appearance on sustained words.
- **Apple Music Duet placement** — confirmed vocalist turns appear on the left and right directly from Apple lyric metadata.
- **Safe duet validation** — Apple data must contain both vocal sides before it can move any lyric to the right, preventing malformed metadata from shifting an entire song.

## What’s new in v1.0.8

- **ANR-safe lyric processing** — Apple lyric parsing, timing repair and duplicate cleanup now run away from the UI thread, preventing multi-second freezes when the refined result arrives.
- **Low-cost karaoke renderer** — a single stable text layout replaces per-word composables while retaining grapheme-safe fill and paint-only aura.
- **Sequential instrumental cue** — the three dots illuminate across three distinct stages and are all fully bright at the lyric hand-off.
- **Missing-section guard** — malformed ends and near-duplicate timed rows are repaired before the live rail is built, preventing one-frame flashes and skipped sections.
- **Non-blocking diagnostics** — log history loads and saves in the background with a bounded 2,000-line cache instead of rewriting thousands of rows on the UI thread.
- **Apple-style stable word bloom** — the active grapheme lifts through brightness and aura without changing font metrics, eliminating line shake and rewrapping during karaoke.
- **Compact lyric recomposition** — completed and upcoming text is grouped into two stable spans while only the currently sung word receives progressive styling.
- **Continuous lyric clock** — stepped AudioTrack latency samples are interpolated at display rate, so karaoke no longer hesitates or jumps backwards as the local audio queue changes.
- **Living character highlight** — the grapheme beneath the karaoke sweep grows softly and returns to its resting size without splitting Thai marks or emoji.
- **Sustained-word aura** — words held for 760 ms or longer gain a broader glow that breathes with their timing while short lyrics remain clean.
- **Lighter lyric rendering** — invisible lyric rows skip expensive blur work, preserving smoother animation on 2 GB Android TV hardware.
- **Bounded lyric search** — the loading indicator exits within 7.5 seconds instead of remaining stuck when Apple Music is slow or unavailable.
- **Complete instrumental hand-off** — all three breathing dots reach full brightness before the next lyric enters.
- **AI feasibility tested on low-memory TV hardware** — real UVR-MDX stem separation was benchmarked on a 32-bit, 2 GB Android TV; the app keeps its real-time Smart Vocal Focus path because the neural model was far too slow for uninterrupted playback.
- **Audible-output lyric clock** — local AudioTrack queue time is measured and removed so highlighting follows sound leaving the television, not freshly decoded input.
- **Smoothed sender progress** — small RAOP timing jitter is corrected gradually, while real seeks and track changes still snap immediately.
- **Apple-onset repair** — precise word starts are retained when Apple supplies placeholder one-millisecond word endings.
- **Smart Vocal Focus** — analyses centre dominance, vocal-band energy and the stereo bed in real time; bass, upper air and sharp instrument attacks are protected while lead vocals are reduced.
- **Stable karaoke clock** — AudioTrack queue measurements are slew-limited so packet writes cannot make the active word jump backwards.
- **Bounded word timing** — malformed Apple durations are clipped at the next word and lyric onset, preventing a finished line from highlighting over the following line.
- **High-frame-rate motion artwork** — prefers 50–60 FPS Apple Music HLS variants when a matching album publishes one.
- **Display-synchronized presentation** — a lightweight GPU motion layer follows the television’s 60/120 Hz VSYNC, while 24/30 FPS sources remain correctly paced.
- **More resilient playback** — a larger bounded buffer, decoder fallback and 64 MB loop cache reduce visible stalls without unbounded RAM use.
- **Karaoke Mode** — press OK on the live lyrics screen to open a television-first vocal control inspired by Apple Music Sing.
- **Adjustable vocal reduction** — Up, Down, Volume + and Volume − change the lead-vocal level while the karaoke panel is open.
- **Bass-preserving centre reduction** — lightweight in-place stereo processing reduces centred vocals without stripping all centred low frequencies.
- **Smoother motion artwork** — short HLS cover loops use a bounded 64 MB disk cache, a longer rebuffer guard and a TV-friendly decode ceiling.
- **Honest audio status** — the output label shows when Karaoke or Spatial processing is active, since processed PCM is no longer bit-perfect.

> AirPlay music arrives as lossless 16-bit / 44.1 kHz ALAC. Some Android TV audio systems always run their hardware mixer at 48 kHz; the app now reports that device-side conversion, but Android does not offer a universal public API to bypass it on every television.

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
- **Spatial / Surround Enhancement** — optionally widens stereo sound inside the app; it is clearly identified as an enhancement rather than Dolby Atmos.
- **Smart Karaoke / Vocal Focus** — uses a multiband, vocal-aware centre reducer with an adjustable level and an allocation-free path suitable for 2 GB Android TV devices.
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
- **Plain Apple lyrics** — songs without timestamps display their full text as a readable scrolling document.
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
adb install -r Airplay-Speaker-v2.0.0.apk
```

The application appears in the Android TV app launcher as **Airplay Speaker**.

> The published APK uses a development signing key. Android may require the previous build to be uninstalled if that build was signed with a different key.

## Use

1. Open Airplay Speaker and wait for `LISTENING FOR AIRPLAY`.
2. Connect the iPhone, iPad, Mac, and Android TV to the same network.
3. Open Music or Control Center and choose **Airplay Speaker** from the AirPlay menu.
4. Start a track. Artwork, metadata, ambient color, and available lyrics appear automatically.
5. Press Up or Down on the TV remote to browse all lyrics, then stop interacting to return to live sync.
6. Press OK on the live lyrics screen to open Karaoke Mode. Use Up/Down or Volume +/− to adjust vocals, Left/Right to choose a control, and OK to toggle or confirm.

The receiver name, automatic startup, background playback, motion artwork, and optional video mode can be changed in **Settings**.

## Lyrics and artwork data

Lyrics and motion artwork are not bundled in the APK. The app looks them up when a track begins:

- Apple Music lyric data — the only lyric source, supporting word, line, Duet, backing-vocal, and plain-text presentation when supplied for the matched song.
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
├── lyrics/      # Apple Music lyric lookup, parsing, and timing
├── renderer/    # Audio and video pipelines
├── service/     # Foreground AirPlay receiver service
└── ui/          # Compose UI for Android and Android TV
```

Native receiver components and retained source live under `app/src/main/cpp/` and `app/src/main/jniLibs/`.

## Known limitations

- DRM-protected media, including FairPlay-protected video, is not supported.
- Motion artwork and synchronized lyrics depend on external data availability for each track.
- Public Apple Music page structures and lyric response formats may change.
- Compatibility and latency vary by sender, Android TV hardware, and network quality.
- Dolby Atmos decoding and bitstream output are not supported; the optional spatial setting widens AirPlay's stereo PCM without claiming Atmos metadata.

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
