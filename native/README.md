# Native implementation notes

These notes describe the current 1.0.0 source. They are maintenance references,
not a claim that a build has been tested in every game state. All hard-coded
addresses and offsets target the 32-bit New Vegas 1.4.0.525 runtime.

## Implementation map

| Area | Source | Notes |
| --- | --- | --- |
| Plugin lifecycle, sockets and snapshots | `plugin.cpp` | [Bridge](BRIDGE.md) |
| Activity blocking | `activity.h` | [Activities](ACTIVITY.md) |
| Stats, effects, reputation and XP | `plugin.cpp` | [Stats](STATS.md) |
| Inventory copies, details and actions | `plugin.cpp`, `inventory_*.h` | [Inventory](INVENTORY_COPIES.md) |
| Ammo switching and reload | `plugin.cpp` | [Ammo](AMMO.md) |
| Doctor's Bag targeting | `limbcare.h` | [Limb treatment](LIMBCARE.md) |
| World-map inheritance | `plugin.cpp` | [World maps](WORLD_MAP_INHERITANCE.md) |
| Off-screen local maps | `localmap.h` | [Local maps](LOCAL_MAPS.md) |
| Fast travel | `plugin.cpp` | [Fast travel](FAST_TRAVEL.md) |
| Quest-marker preview | `plugin.cpp` | [Quest preview](QUEST_MARKER_VIEW.md) |
| Notes and in-game audio | `plugin.cpp` | [Notes](NOTES.md) |
| Available radio stations | `plugin.cpp` | [Radio](RADIO.md) |
| Dialogue choices and skipping | `dialogue.h` | [Dialogue](DIALOGUE.md) |
| Native text prompts and console | `text_entry.h` | [Text entry](TEXT_ENTRY.md) |
| V.A.T.S. selection and attacks | `vats.h` | [V.A.T.S.](VATS.md) |
| Trade and container summaries | `barter_summary.h`, `loot_summary.h` | [Barter](BARTER_SUMMARY.md), [Looting](LOOT_SUMMARY.md) |
| General statistics | `plugin.cpp` | [General](GENERAL_STATS.md) |
| Challenges | `challenges.h` | [Challenges](CHALLENGES.md) |

## Runtime and ownership rules

The socket worker queues gameplay requests; the game-loop callback validates
them and calls the game. Engine-owned pointers stay in the plugin, rather than
being accepted from Android. Serialized snapshots are copied under the snapshot
mutex before network writes. Audio-duration callbacks have their own lock and
do not dereference note/save objects.

Most headers are included within `plugin.cpp`'s anonymous namespace and depend
on helpers declared earlier there; they are not independent translation units.
Treat native object layouts, calling conventions and ownership as part of the
interface. Keep size/offset assertions and stale-token checks when editing them.

Research used the [xNVSE API/SDK](https://github.com/xNVSE/NVSE/tree/6.4.9) and
[FNV decompilation reference](https://github.com/momusdigital/fnv_decomp).
Recovered names can be misleading; current call signatures and offsets in the
source are authoritative for this implementation. No decompiled source or game
assets are bundled here. See [build instructions](../BUILDING.md).
