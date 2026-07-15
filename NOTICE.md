# Sonic Cast TV — Open-source notice

Sonic Cast TV 0.11.0 is a modified distribution of **AirPlay Receiver for
Android** by jqssun:

- Project: https://github.com/jqssun/android-airplay-server
- Based on: version 0.0.23, commit
  `70c414ade84fbc3715a58d4c299687bca99c4dfe`
- License: GNU General Public License version 3 (see `LICENSE`)

The Android application and native receiver also incorporate these upstream
components. Their license files and source trees are retained in
`app/src/main/cpp/third_party`:

- UxPlay — AirPlay/RAOP receiver implementation, GPL-3.0
- Shairplay — classic RAOP receiver implementation, commit
  `096b61ad14c90169f438e690d096e3fcf87e504e`, GNU LGPL 2.1 or later
- Apple ALAC reference decoder — Apache License 2.0
- libplist — GNU LGPL 2.1 or later
- openssl-cmake / OpenSSL build integration — licenses retained in its source

Synchronized lyrics are retrieved at runtime from the open-source LyricsPlus
service (https://github.com/ibratabian17/lyricsplus) and the open LRCLIB service
(https://lrclib.net). The application sends the current track title, artist,
album, and duration solely to locate a matching lyrics record. LyricsPlus is
queried first for precise syllable/word timing; LRCLIB is the automatic fallback.
Lyrics are not bundled in this distribution.

When track metadata is available, the application may query Apple's public
iTunes Search service with the title and artist to identify the matching album,
then read the album's public Apple Music page to locate square motion artwork.
If available, that Apple-hosted HLS video and its corresponding normal album
cover are loaded while the Now Playing screen is visible. The normal cover is
used locally for palette extraction and the blended background. Neither motion
artwork nor the normal cover is bundled in the application or persisted by
Sonic Cast TV. If no motion artwork is published or the request fails, the
sender-provided static cover remains visible with a local animation.

For the television word-following effect, LyricsPlus syllable timing and
Enhanced LRC word timestamps are preserved exactly. If a record contains only
ordinary LRCLIB line timestamps, word windows are estimated locally between
adjacent lines. No user account or analytics identifier is sent.

The prebuilt `libairplay_native.so`, `libcrypto.so`, and `libc++_shared.so`
files in `app/src/main/jniLibs` were extracted from the official upstream
0.0.23 Android release to make this customized build reproducible without a
large local NDK download. Matching receiver source and build configuration are
included in this source distribution.

`libclassic_raop_native.so` is built from the retained Shairplay source and a
small JNI adapter in `app/src/main/cpp/classic`. It is used by the default
music-only mode so the Music app on macOS can connect through classic AirPlay
and send 16-bit/44.1 kHz ALAC audio.

Sonic Cast TV is not affiliated with or endorsed by Apple Inc. AirPlay is a
trademark of Apple Inc. DRM-protected content is not supported.
