# Tablet Player 4.2 / media-server 0.5.0

## Playback and history

- Every new episode invalidates the previous playback callbacks before closing its player and cache. Its path, name and resume position are selected before preparation, including direct-stream fallback.
- Cached episodes keep their remote episode queue. Finishing a prefetched file shows the next-episode dialog instead of closing the player.
- A watched check is persisted on libVLC's `Playing` event. There is no configured limit on the number of checked episodes. Skipping credits or manually selecting the next episode keeps the check.
- Watched state and saved position are independent and displayed in list, grid, file details and local downloads.
- New history keys contain server identity, path and file version. Legacy path-only history remains in preferences but is not reused, since its file version is unknown.
- The server reports a stat-based file-instance version (size, nanosecond mtime and, on Unix/Android, device, inode and ctime). Browsing does not hash entire videos. Renaming/recreating a file or changing its metadata can start new history even if its contents are identical.
- Local downloads have a sidecar linking them to the remote history key. Playback validates length and SHA-256 samples of the first, middle and last 64 KiB in the background. This is a bounded replacement check, not a full-file checksum. Unmanaged local files also include path and modification time in their key.
- Updated app and server must be installed together. `/file?path=...` is authenticated and returns `server_id`, `size` and `version`; `/list` and `/search` include entry versions.

## Downloads and cache

- Range requests use `If-Match`; direct playback includes the expected version in its URL. A mismatched version returns HTTP 412.
- Downloads use version-specific paths and a `.part` file. Completion checks size and rechecks the server version before publication. Incomplete files and sidecars are hidden from the downloads screen.
- Cancelled cache writers cannot delete or overwrite a newly selected episode's files. Entries use separate temporary directories and are retired only after their readers/writers stop.
- Magisk updates preserve `server_id` alongside configuration and approved clients.

## Browser

- New header, scrollable toolbar, search field, rounded list/grid cards, vector file icons and watched badges in both modes.
- Active breadcrumbs, direction-aware folder entrance, animated panels and queue/download controls, dimmed narrow-screen drawer and scrollable file information.
- Folder scroll positions are remembered. Outdated responses cannot replace newer navigation results.
- Effects use API-19-compatible drawing and property animation. Duration is reduced on low-RAM devices and property animation respects the system's disabled-animation setting.
- Shimmer is limited to 30 updates per second, reuses its shader, and stops while paused. Idle download polling does not rebuild file rows.
- Dark-theme browser text has a separate readable accent.

## Long jump button

Settings contains a long-jump interval in seconds (1–3600, default 90). The player button displays the actual configured interval and uses it for every press. The separate 10-second buttons are unchanged.

## Validation

Java/Rust parser checks, XML/resource checks, view-binding type checks, shell syntax and workflow YAML checks were performed without compiling the app or server locally. Runtime playback and layout still need verification on the tablet.

GitHub Actions now runs `server/tests/api_regression.py` against its host build. It checks metadata/list/search identity, Range responses, ETag, version guards, same-size replacement with preserved mtime, and path confinement. These integration tests are configured for CI and were not executed locally.

Recommended device checks: switch episodes repeatedly while preparing; allow a prefetched episode to finish; leave before credits; verify several simultaneous checks in list/grid; replace a server file with the same name and reopen; cancel/resume a download; rotate the browser with its drawer open; change the long-jump interval and press the labeled player button.

Commit:

`git commit -m "Fix episode playback and versioned watch history, modernize browser UI, and configure long jumps"`
