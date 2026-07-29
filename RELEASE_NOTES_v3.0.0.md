# Airplay Speaker v3.0.0

Airplay Speaker 3.0 is the cinematic motion-artwork, liquid-glass, lyric
reliability, and experimental AI karaoke update for Android TV. These notes
compare v3.0.0 with the previous GitHub release, v2.5.0.

## Added

- A full-screen motion-artwork layout that gives the moving album visual a
  dedicated left-hand stage and preserves the existing lyric and track-title
  interfaces.
- A Settings switch that returns motion artwork to the classic square-cover
  presentation.
- A feathered artwork compositor: the live movie remains sharp in the centre
  and blends through a blurred preview into the album-colour field at its
  perimeter.
- A single-layer, rounded lyric surface and centred track badge for the
  full-screen presentation.
- Experimental on-device AI Vocal Separation using UVR-MDXNET 9482 through
  Google AI Edge LiteRT on supported 64-bit Android TV hardware.
- GPU, OpenGL, and CPU inference attempts with a real-time performance guard,
  ordered buffering, and automatic fallback to Smart Vocal Focus.
- A bundled MIT model license and attribution notice.

## Improved

- Full-screen square video selection now permits supported renditions up to
  1920×1920 instead of restricting their height to 720 pixels.
- The video decoder uses fit scaling while one aspect-correct TextureView
  matrix performs the centred crop, removing a second resampling pass.
- Full-screen motion uses minimal overscan and drift so the source is not
  needlessly enlarged and softened on every frame.
- The artwork edge effect reuses the normal preview still instead of opening a
  second video decoder, protecting memory and decoder capacity on modest TVs.
- Public Apple Music motion artwork can select square, wide, tall, or ambient
  renditions while retaining a high-resolution static companion for the
  background palette and loading state.
- Apple lyric matching performs bounded retries across catalogue and metadata
  variants while retaining strict word, line, and untimed presentation modes.
- Thai and other multilingual lyric shaping, backing-vocal timelines, Duet
  validation, instrumental dots, remote browsing, and writer credits share a
  stable display-rate lyric clock.
- Waiting, settings, karaoke, track metadata, and lyric surfaces use a more
  consistent liquid-glass visual system and ten-foot focus treatment.

## Fixed

- The classic artwork/title tree is removed from composition while the
  full-screen motion layout is active, eliminating UI and video-surface
  overlap during transitions.
- The full-screen lyric panel no longer stacks multiple glass and black
  surfaces that could appear as doubled frames.
- Square motion artwork is no longer forced through a 1280×720 limit and then
  enlarged to fill a near-square 1080p pane.
- Motion artwork no longer receives both MediaCodec cropping and a TextureView
  crop.
- Lyric lookup, highlighting, backing vocals, and Duet placement retain the
  v2.5 safeguards against fabricated word timing, duplicate highlight layers,
  malformed all-right singer metadata, and missing untimed text.
- AI processing cannot overtake buffered audio; slow inference returns to the
  lightweight processor while preserving packet order.

## Audio and compatibility

- AirPlay music remains 16-bit / 44.1 kHz lossless ALAC before optional
  in-app processing.
- Lossless PCM, Spatial / Surround Enhancement, Smart Vocal Focus, and
  Experimental AI Vocal Separation remain separate user choices. Enabling a
  karaoke or spatial processor intentionally makes output processed rather
  than bit-perfect.
- The AI separator is disabled by default, requires a 64-bit process, adds
  several seconds of look-ahead latency, and may automatically fall back when
  a TV cannot infer each chunk in real time.
- Android TV 7.0 / API 24 or newer.
- Version code: 300
- Version name: 3.0.0
- Application ID: `com.airplayspeaker.tv`
- APK: `Airplay-Speaker-v3.0.0.apk`
- APK size: `80,227,601 bytes` (76.5 MiB)
- SHA-256:
  `77cade4a874533da57879f4263eb2c11711fc654bc42231634c5d8d214adade1`
- Development-signed APK using the same certificate as the v2.5 release, so it
  installs as an in-place update and retains local settings.

## Included source and model

The release contains the complete modified Android source, retained upstream
receiver sources and licenses, the LiteRT vocal-separation pipeline, and the
MIT-licensed UVR MDX-Net model attribution. Apple Music lyrics and motion
artwork are requested at runtime and are not bundled in the APK.
