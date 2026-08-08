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
