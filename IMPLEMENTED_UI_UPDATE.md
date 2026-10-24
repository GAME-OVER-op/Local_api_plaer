# Implemented UI / UX update

Implemented from the selected plan:

1. File information side panel opened from the browser.
19. Per-file active download progress in the browser row.
20. Compact aggregate download bar with count, speed and total progress.
26. Scroll-to-top button for long folders.
33. Search result action: open containing folder.
35. Right slide-out/split panel with bookmarks, recent folders and recent videos.
36. List / tile (grid) browser modes.
40. Thin custom scroll-position indicator.
42. Breadcrumb path rebuilt as large touchable blocks, including root and short names like `0`.
43. Direction-aware folder navigation animation.
44. Lightweight skeleton loading view.
48. Return/scroll/highlight to the actually current video, including episode changes inside PlayerActivity.
49. Compact server state + latest successful request latency indicator.
59. Shallow folder metadata from server: direct child count and direct file byte total, capped for safety.
69. Animated bookmark button state.

Trusted server identity / changing DHCP address:

- server persists a stable `server_id` in its data directory;
- Magisk service defaults `MEDIA_NAME` to Android manufacturer + model when not configured;
- authorized `/identity` endpoint returns server id/name/port;
- unauthenticated discovery keeps the generic `media-server` identity;
- authorized discovery uses a signed `MEDIA_DISCOVER_V2` request based on the already paired device secret;
- every discovery call performs 3 background broadcast attempts and deduplicates responses;
- saved devices are shown by name on the main screen;
- tapping a saved device first verifies its last IP, then performs signed LAN discovery if DHCP changed it, verifies `/identity`, updates the saved IP, and connects;
- PlayerActivity rediscovery prefers exact `server_id`, preventing accidental switching to a different server with the same name.

Validation performed in this environment:

- all Android XML resources parse successfully;
- Java sources have no parser-level syntax errors in `javac` parsing;
- resource/id cross-check completed; only expected `android.R.*` resources are external;
- `service.sh` passes `sh -n`;
- full Android Gradle and Rust Cargo builds could not be executed because this environment does not contain Gradle/Android SDK/Rust toolchain.

## UI animation / APK action follow-up
- Folder contents now enter as a staggered right-to-left cascade per visible row instead of moving the whole list at once.
- Row title and subtitle fade/slide in independently with a small delay so text does not pop abruptly.
- Skeleton is delayed for fast LAN responses and appears only for noticeable loads.
- Skeleton rows now use a moving darker shimmer band from left to right, with slight phase offsets between rows.
- Shimmer animation is stopped when loading finishes, the activity pauses, or the activity is destroyed.
- APK info panel restored: before download it shows Download + Download and install; after a complete download it shows Download again + Install.

## Full modern UI refresh (2026-10-25)

The visual layer was rebuilt while keeping the existing server, browser, download and playback logic intact.

- Replaced the legacy ActionBar look with a unified custom app chrome across Main, Downloads and Settings.
- Added a new adaptive light/dark palette with indigo + teal accents, high-contrast surfaces, softer borders and consistent state colors.
- Rebuilt connection screen with a hero header, clearer form hierarchy, modern trusted/recent server cards and a persistent visual network state.
- Reworked Browser controls, search, breadcrumbs, list/grid cards, empty state, queue/download bars and side drawer into one visual system.
- Reworked Downloads with a dedicated header, card rows, animated empty state and consistent back navigation.
- Reorganized Settings into clear Playback / Appearance / Engine & cache groups, with modern fields, spinners, switches and reset action.
- Refreshed Player overlays: glass-style header/badges, buffering card, seek area and controls; show/hide transitions now animate instead of snapping.
- Added shared `UiMotion` behavior for screen entrance, staggered sections, press feedback, animated visibility, list layout animation and system bar contrast.
- Updated activity and player transitions to slower, smoother motion while respecting Android animation scale where browser motion is concerned.
- Preserved every pre-existing required layout ID used by Java code; new IDs were only added for the new app chrome.

Validation for this refresh:

- 76 Android resource XML files parsed successfully.
- All custom `R.id` references used by Java resolve to an ID in the layouts.
- All custom layout/drawable/anim/color/string/style/menu references resolve.
- Modified Java files pass a parser-level `javac` syntax smoke test; diagnostics are only unresolved Android classes because this environment has no Android SDK jar.
- A full Gradle Android build cannot be executed in this sandbox because Gradle and the Android SDK are not installed. The repository CI already provisions Gradle 8.7, Android 34 and build-tools 34.0.0 and remains the authoritative full build check.

## Post-update playback fixes

- Browser/server back button now uses a centered vector icon instead of a text glyph, removing baseline drift.
- Player volume feedback remains visible briefly after swipe release and is also shown for physical volume keys while player chrome is hidden.
- Frame-generation playback now retries delayed LibVLC audio-track initialization, reasserts audio focus/volume, and applies audio delay only when the audio ES is ready.
- Remote videos up to and including 50 MiB are fully downloaded before playback. Their known `/file` size is reused, they use a single main download chunk, and playback starts directly from the finalized local cache file without the old prepare/background hand-off.

## Picture controls and HDR protection (4.4)

- Added left-half vertical brightness gesture in the player. It adjusts only the player window (5–100%), leaves Android system brightness untouched, persists the last player value, and shows `☀ N%` even when playback chrome is hidden.
- Right-half vertical swipe remains volume and horizontal swipe remains seek; gesture direction is locked after the movement threshold to avoid diagonal conflicts.
- Added Settings → Image with an enable switch, Original/Cinema/Vivid/Custom presets and bounded contrast, image brightness, saturation and gamma controls.
- Picture correction uses VLC's `adjust` video filter with conservative ranges rather than pretending SDR contrast is HDR.
- Added automatic HDR metadata probing for HDR10/PQ, HLG and Dolby Vision on Android 7.0+. When HDR is detected, frame generation and SDR picture correction are disabled for that source and playback is restarted on VLC's direct video surface to preserve the HDR path.
- HDR display capability is checked on API 24+ in an isolated helper so minSdk 19 devices do not load newer display APIs.
- If VLC cannot start with picture adjustment, the player performs a one-time safe retry for that source without the adjustment filter instead of leaving playback unusable.
