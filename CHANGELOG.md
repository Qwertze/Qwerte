# Changelog

All notable changes to Qwerte are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Versions before 0.2.8 are listed on the [GitHub releases page](https://github.com/Qwertze/Qwertze/releases).

## [0.3.1-dev.1] - 2026-10-15

### Added
- stash-sorter: picking up items other players left on the ground in the stash area (`collect-ground`, `collect-range`). Only with no other player loaded, only items lying still for 3 seconds, never your own drops or items from blocks you dug.
- stash-sorter: scan-only mode (`sort-items` off). It reads unknown or stale containers and keeps the saved stash current, but never moves items. stash-overlay keeps working in it.

## [0.3.0] - 2026-10-15

Includes everything from 0.2.8, which wasn't released separately.

### Added
- stash-sorter: shulker compaction (`compact`, `compact-top-up-at`, `compact-exclude`, and the Compact skip pick mode).
- stash-sorter: auto-labelling (`auto-label`, `label-gap`) and the Labels report button.
- stash-sorter: watching chests and signs (`watch`, `watch-delay`, `watch-signs`, `sign-delay`).
- stash-sorter: giving way to you and other modules (`quiet`), overflow into unsigned chests (`unsigned-overflow`), and `debug`.
- stash-sorter: orphaned shulkers are reported in chat and listed in the module.
- auto-till and auto-dirt: work patterns (`work-pattern`, `row-direction`, `lane-width`).
- auto-till: ice water holes (`water-source` Ice, `ice-restock`) and glowstone lighting (`light-holes`, `light-air-place`, `glowstone-restock`).
- auto-till and auto-dirt: auto-hotbar with per-item slots, and restocking of bone meal, glowstone and hoes.

### Changed
- stash-sorter: items are identified by item ID and never by their anvil name. Labels show `Diamond Sword (named steak)`.
- stash-sorter: `End Blocks` only matches end blocks. Shulker boxes and elytra no longer match it.
- stash-sorter: own gear (tools, armor, food, totems, ender pearls and the like) is never sorted unless the sorter took it from the stash.
- stash-sorter: shulkers are never placed next to a hopper, on top of a stash chest, or where a player or mob stands, and are at least 5 seconds apart. After a placement problem, shulker work pauses for 3 minutes instead of for the rest of the run.
- stash-sorter: `Rescan` is now `Forget contents`. It doesn't read anything by itself.
- stash-sorter: in stay-on mode, the "Sorted" summary is only logged when it changes.
- auto-till and auto-dirt: wait while stash-sorter is mid-job. Stash-sorter gives way to them only while they are actually working.

### Fixed
- stash-sorter: no longer loops forever opening and closing the same chests. Only real sign text changes count, and stuck work is given up on.
- stash-sorter: stacks the kit builder moves into carry slots are treated as stash stacks.

## [0.2.8] - 2026-10-14

Not released separately. Included in 0.3.0.

### Added
- auto-till and auto-dirt: work patterns (Nearest, Rows, Spiral Out, Spiral In) with row direction and lane width.
- auto-till: ice water holes (`water-source` Ice) with ice restock.
- auto-till: glowstone lighting for water holes (`light-holes`, `light-air-place`).
- auto-till and auto-dirt: auto-hotbar with per-item slots. Protected items are never evicted.
- auto-till: container restock for bone meal, glowstone and hoes.

### Fixed
- auto-till: crash when the refill source changed mid-trip.
