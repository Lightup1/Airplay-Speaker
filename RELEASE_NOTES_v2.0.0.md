# Airplay Speaker v2.0.0

Airplay Speaker 2.0 is a major lyric, karaoke, motion-artwork, and low-memory
stability update for Android TV. These notes compare v2.0.0 with the previous
GitHub release, v1.0.0.

## Added

- Apple-style, glyph-shaped karaoke aura with grapheme-safe highlighting for
  Thai text, combining marks, emoji, and non-Latin scripts.
- Layout-stable emphasis for sustained words, scaling the live glyph by up to
  14% without reflowing or shaking the lyric row.
- Independent timing streams for the primary lyric and compact backing vocal,
  including overlapping vocal parts.
- Karaoke Mode with adjustable Smart Vocal Focus for lightweight, real-time
  lead-vocal reduction on entry-level Android TV hardware.
- Apple Music Composition & Lyrics writer credits after the final line.
- High-frame-rate motion-artwork selection with a bounded local loop cache.

## Improved

- Lyrics now use an Apple Music-only policy: Word-by-word when Apple supplies
  word timing, Line Sync when only line timing exists, and readable plain text
  when no synchronization is published.
- Lyric animation follows a display-rate clock while compensating for queued
  audio, so the highlight tracks sound leaving the television more closely.
- Duet turns use confirmed singer/agent metadata and remain stable when a
  provider sends incomplete or malformed labels.
- Instrumental dots illuminate from left to right and reach full brightness at
  the next lyric hand-off.
- Motion artwork buffering, decoder fallback, and cache limits are tuned for
  smoother playback without unbounded memory growth.
- Lyric lookup, parsing, timing repair, and log persistence run away from the
  main UI thread with bounded caches for 2 GB TV devices.

## Fixed

- Backing vocals no longer invalidate a correctly timed main lyric when both
  vocal timelines overlap.
- Songs with malformed duet metadata no longer place every lyric on the right.
- Duplicate or near-identical provider rows no longer flash, hide, or stack a
  lyric section.
- Placeholder one-millisecond word endings are repaired from the next trusted
  onset without fabricating an entire karaoke timeline.
- Word highlighting no longer creates rectangular blocks, duplicated text, or
  whitespace glow.
- Lyric rows no longer shake from animated font metrics or rebound while
  centring on the next line.
- Loading exits after a bounded wait instead of leaving the spinner visible
  indefinitely when the lyric service is unavailable.

## Package

- Android TV 7.0 / API 24 or newer
- Version code: 200
- APK size: 45,053,782 bytes (43 MB)
- SHA-256: `02d21fdfcab02a1b779418c47d92597059bab5d911e5675f703af10ca6c790a3`
- Development-signed APK
