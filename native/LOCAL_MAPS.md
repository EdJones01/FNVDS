# Live local maps

`localmap.h` uses the game's cell-level off-screen renderer and Direct3D 9
texture readback. There is no MapMenu-open prerequisite, detour library or
exported map-image file. The renderer's lock protects capture work; the app
receives DDS pixels over the existing bridge.

The current window is 5 by 5 tiles, with 4096 world units per tile. Tiles use
256-by-256 BGRA output. The centre and neighbouring tiles are prioritized.
Missing tiles are scheduled at intervals of at least 50 ms; once filled,
existing terrain refreshes at intervals of at least 250 ms, one tile at a time.
Actual completion depends on the game loop, renderer and available cells.

Exterior generation accepts only attached cells. It does not load distant
cells just to fill the map. Overlapping tiles are reused when the window moves
within the same area. Epoch checks discard renders from a reset context.

`LOCALON` requests live rendering and `LOCALOFF` disables it. Transfer uses
`LOCALBEGIN`/`LOCALEND` batches, `MAPDATA` tile payloads, `LOCALSTATUS`, and
normalized `LOCALPOS` updates. Grid positions allow the receiver to reuse
tiles after a window shift. This state is separate from world-map coordinates.

`BuildLocalMarkerSnapshot` in `plugin.cpp` supplies quest/custom destinations
and door-transition markers. Android draws their icons and dotted routes.
No local-map image is written into the game directory.
