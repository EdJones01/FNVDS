# General statistics

DATA / GENERAL mirrors the 43 miscellaneous statistics that vanilla StatsMenu lists, in ID order 0 through 42. Zero-valued statistics are included. This is separate from the existing STATS / REPUTATION panel.

For runtime 1.4.0.525, the game-loop snapshot reads the same table at `0x011C6D50` used by StatsMenu. It calls the game's integer getter at `0x005A3370` and localized display-name getter at `0x0078A260`. No menu is created, no counters are changed, and no additional plugin is required. Reads are bounded to 43 IDs and guarded against unavailable entries; unavailable reads are omitted rather than inventing a counter value.

The implementation was informed by the local FNV decompilation's `fallout/interface/menus/statsmenu.cpp` (list population), `fallout_shared/tesconditionfunctions.cpp` (value getter), `fallout/interface/menus/loadingmenu.cpp` (name getter), and `MiscStatManager__MiscStatID.h` (43 IDs). These describe the game's runtime behavior; FNVDS does not copy their menu implementation.

`STATE GENERALSTATS BEGIN`, `STATE GENERALSTAT <id> <integer> <base64 label>`, and `STATE GENERALSTATS END` publish one complete list through the existing snapshot connection. A waiting snapshot publishes an empty list, clearing previous-save statistics. Android updates the visible panel only when list contents change, avoiding a rebuild on every unchanged snapshot.

The list refreshes on the normal 250ms snapshot cadence while gameplay snapshots are available. It includes counters for quests, exploration, combat, consumables, crafting, repair, dialogue, stealing, companions, challenges and casino/Caravan games, including the counters below the initial Pip-Boy viewport.
