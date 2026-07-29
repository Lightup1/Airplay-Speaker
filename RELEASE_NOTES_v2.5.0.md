# Airplay Speaker v2.5.0

Airplay Speaker 2.5 is a complete product rebrand plus a lyric accuracy,
multilingual typography, and audio-reactive presentation update for Android TV.
These notes compare v2.5.0 with the previous GitHub release, v2.0.0.

## Added

- A real three-band Now Playing meter driven by decoded PCM energy rather than
  a looping decorative animation.
- Natural lyric token spacing for Thai, Lao, Khmer, Myanmar, Chinese, Japanese,
  Korean, Arabic, Cyrillic, and Latin writing systems.
- Grapheme-safe rendering for combining marks, emoji ZWJ sequences, flags, and
  right-to-left text.
- Regression coverage for Apple `Line` versus `Syllable` payloads,
  multilingual token reconstruction, title centring, and PCM band analysis.

## Improved

- The track title now owns a symmetric centred viewport. The equalizer is a
  separate fixed element that stays close to the title without shifting it.
- Long titles move through the existing edge fade while the equalizer remains
  stationary.
- The equalizer follows real bass, midrange, and treble energy, then returns to
  an idle baseline when playback pauses, the app is muted, or PCM stops.
- Word highlighting no longer spends part of a token's duration animating an
  invisible trailing space.
- Apple word timing preserves provider spacing and punctuation without adding
  duplicate spaces or splitting Unicode grapheme clusters.
- Apple lyric lookup now retries cleaned title and primary-artist queries
  across Thai and US catalogues, then checks up to five ranked track editions
  when the first Apple match has no lyric payload.
- Lookup has a bounded 18-second deadline, so an unavailable catalogue or
  provider cannot leave the lyric panel loading indefinitely.
- Lyric highlight and rail motion retain stable text metrics for smoother
  playback on 2 GB Android TV hardware.

## Fixed

- Apple `syncType: Line` responses are no longer treated as word-by-word when
  the service wraps a complete lyric row inside a one-item text array.
- Timing repair can no longer promote a whole-row line token into fabricated
  karaoke timing.
- Songs with line timing now highlight the complete active row; songs without
  timing remain a readable plain-text document.
- Thai tone marks and Indic/Arabic combining marks are preserved during word
  matching instead of being normalized away.
- The equalizer no longer scrolls together with a long track title or pushes a
  short title away from the exact centre.

## Rebrand

- GitHub repository: `l3kza08/Airplay-Speaker`
- Android application ID: `com.airplayspeaker.tv`
- App, website, download links, documentation, demo assets, and HTTP identity:
  `Airplay Speaker`

## Package

- Android TV 7.0 / API 24 or newer
- Version code: 250
- Version name: 2.5.0
- APK: `Airplay-Speaker-v2.5.0.apk`
- APK size: `45,130,863 bytes` (43.0 MiB)
- SHA-256: `a8d8e06d69a2b2fbb71b4b9ed406011a16275abe45bda8d651d8870d949b809b`
- Development-signed APK

Because v2.5 has a new application ID, it can initially coexist with an older
legacy installation. After confirming v2.5 works, remove the old package to
avoid two launcher entries. Local settings from the legacy package are not
migrated automatically.
